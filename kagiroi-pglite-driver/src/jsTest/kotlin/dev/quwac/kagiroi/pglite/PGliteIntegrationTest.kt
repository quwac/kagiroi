@file:Suppress("UnsafeCastFromDynamic", "UnusedParameter")

package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.select
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@JsModule("node:fs")
@JsNonModule
private external object NodeFileSystem {
    fun mkdtempSync(prefix: String): String

    fun rmSync(path: String, options: dynamic)
}

@JsModule("node:os")
@JsNonModule
private external object NodeOperatingSystem {
    fun tmpdir(): String
}

@JsModule("node:path")
@JsNonModule
private external object NodePath {
    fun join(vararg paths: String): String
}

class PGliteIntegrationTest {
    @Test
    fun inMemoryContract() = runTest {
        val database = configuredDatabase(PGliteStorage.Memory)
        try {
            pgliteDriverContract(database)
        } finally {
            verifyPGliteClose(database)
        }
    }

    @Test
    fun filesystemStoragePersistsAfterClose() = runTest {
        val directory = NodeFileSystem.mkdtempSync(NodePath.join(NodeOperatingSystem.tmpdir(), "kagiroi-pglite-"))
        try {
            val storage = PGliteStorage.FileSystem(NodePath.join(directory, "database"))
            configuredDatabase(storage).usePGlite { database ->
                database.executeRaw("CREATE TABLE pglite_persistence (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                database.executeRaw(
                    "INSERT INTO pglite_persistence (id, value) VALUES ($1, $2)",
                    listOf(1, "persisted"),
                )
            }
            configuredDatabase(storage).usePGlite { database ->
                assertEquals(
                    "persisted",
                    database.queryRaw("SELECT value FROM pglite_persistence WHERE id = $1", listOf(1)).toList().single()
                        .string("value"),
                )
            }
        } finally {
            NodeFileSystem.rmSync(directory, removalOptions())
        }
    }

    @Test
    fun createTablePersistsTableAndColumnComments() = runTest {
        val database = configuredDatabase(PGliteStorage.Memory)
        try {
            database.execute(createTable(PGliteCommentedUsers))

            assertEquals(
                "アプリケーション users",
                database.queryRaw(
                    "SELECT obj_description('pglite_commented_users'::regclass, 'pg_class') AS comment",
                ).toList().single().string("comment"),
            )
            assertEquals(
                "Primary user's email\nユーザーの主要メールアドレス",
                database.queryRaw(
                    "SELECT col_description('pglite_commented_users'::regclass, 2) AS comment",
                ).toList().single().string("comment"),
            )
        } finally {
            verifyPGliteClose(database)
        }
    }

    @Test
    fun createTableAndIndexPersistUniqueConstraintAndIndexComments() = runTest {
        val database = configuredDatabase(PGliteStorage.Memory)
        try {
            database.execute(createTable(PGliteCommentedConstraints))
            database.execute(
                createIndex("pglite_commented_email_idx", PGliteCommentedConstraints, comment = "Email lookup") {
                    on(PGliteCommentedConstraints.email)
                },
            )

            assertEquals(
                "Email must be unique",
                database.queryRaw(
                    "SELECT obj_description(oid, 'pg_constraint') AS comment " +
                        "FROM pg_constraint WHERE conname = 'uq_pglite_commented_email'",
                ).toList().single().string("comment"),
            )
            assertEquals(
                "Email lookup",
                database.queryRaw(
                    "SELECT obj_description('pglite_commented_email_idx'::regclass, 'pg_class') AS comment",
                ).toList().single().string("comment"),
            )
        } finally {
            verifyPGliteClose(database)
        }
    }

    @Test
    fun createsConfiguredIdentityAndGeneratesTheConfiguredValues() = runTest {
        val database = configuredDatabase(PGliteStorage.Memory)
        try {
            database.execute(createTable(PGliteConfiguredIdentityRows))
            database.executeRaw(
                "INSERT INTO pglite_configured_identity_rows (name) VALUES ($1), ($2), ($3)",
                listOf("one", "two", "three"),
            )

            assertEquals(
                CONFIGURED_IDENTITY_VALUES,
                database.queryRaw("SELECT id FROM pglite_configured_identity_rows ORDER BY id")
                    .toList()
                    .map { it.long("id") },
            )
        } finally {
            verifyPGliteClose(database)
        }
    }

    @Test
    fun executesCrossJoinWithTheNativeCartesianCardinality() = runTest {
        val database = configuredDatabase(PGliteStorage.Memory)
        try {
            database.execute(createTable(PGliteCrossUsers))
            database.execute(createTable(PGliteCrossRoles))
            database.executeRaw("INSERT INTO pglite_cross_users (id) VALUES ($1), ($2)", listOf(1, 2))
            database.executeRaw("INSERT INTO pglite_cross_roles (id) VALUES ($1), ($2), ($3)", listOf(1, 2, 3))

            val rows = database.select(PGliteCrossUsers.id, PGliteCrossRoles.id)
                .from(PGliteCrossUsers)
                .crossJoin(PGliteCrossRoles)
                .all()

            assertEquals(6, rows.size)
        } finally {
            verifyPGliteClose(database)
        }
    }

    @Test
    @Suppress("LongMethod") // Exercises one ordered ALTER lifecycle against the same database state.
    fun appliesACombinedTypedAlterTableBlock() = runTest {
        val database = configuredDatabase(PGliteStorage.Memory)
        try {
            database.execute(createTable(PGliteAlterOrganizations))
            database.execute(createTable(PGliteAlterUsers))
            database.executeRaw("INSERT INTO pglite_alter_organizations (id) VALUES ($1)", listOf(1))

            database.execute(
                alterTable(PGliteAlterUsers) {
                    addPrimaryKey(PGliteAlterUsers.id, name = "pk_pglite_alter_users")
                    setNotNull(PGliteAlterUsers.email)
                    setDefault(PGliteAlterUsers.status, "active")
                    addUnique(PGliteAlterUsers.email, name = "uq_pglite_alter_users_email")
                    addForeignKey(PGliteAlterUsers.organizationId, name = "fk_pglite_alter_users_organization")
                        .references(PGliteAlterOrganizations.id)
                    addCheck("ck_pglite_alter_users_quantity") { PGliteAlterUsers.quantity gt 0 }
                    alterColumnType(PGliteAlterUsers.status, PostgresTypes.varchar(32))
                },
            )

            database.executeRaw(
                "INSERT INTO pglite_alter_users (id, organization_id, email, quantity) VALUES ($1, $2, $3, $4)",
                listOf(1, 1, "user@example.com", 1),
            )
            assertEquals(
                "active",
                database.queryRaw("SELECT status FROM pglite_alter_users WHERE id = $1", listOf(1))
                    .toList()
                    .single()
                    .string("status"),
            )
            assertFailsWith<ConstraintViolation> {
                database.executeRaw(
                    "INSERT INTO pglite_alter_users (id, organization_id, email, quantity) VALUES ($1, $2, $3, $4)",
                    listOf(2, 1, "user@example.com", 1),
                )
            }
            assertFailsWith<ConstraintViolation> {
                database.executeRaw(
                    "INSERT INTO pglite_alter_users (id, organization_id, email, quantity) VALUES ($1, $2, $3, $4)",
                    listOf(3, 99, "foreign@example.com", 1),
                )
            }
            assertFailsWith<ConstraintViolation> {
                database.executeRaw(
                    "INSERT INTO pglite_alter_users (id, organization_id, email, quantity) VALUES ($1, $2, $3, $4)",
                    listOf(4, 1, "check@example.com", 0),
                )
            }
            assertFailsWith<ConstraintViolation> {
                database.executeRaw(
                    "INSERT INTO pglite_alter_users (id, organization_id, email, quantity) VALUES ($1, $2, $3, $4)",
                    listOf(5, 1, null, 1),
                )
            }

            database.execute(
                alterTable(PGliteAlterUsers) {
                    dropDefault(PGliteAlterUsers.status)
                    dropNotNull(PGliteAlterUsers.email)
                    dropUnique("uq_pglite_alter_users_email")
                    dropForeignKey("fk_pglite_alter_users_organization")
                    dropCheck("ck_pglite_alter_users_quantity")
                    dropPrimaryKey("pk_pglite_alter_users")
                },
            )
            database.executeRaw(
                "INSERT INTO pglite_alter_users (id, organization_id, email, quantity) VALUES ($1, $2, $3, $4)",
                listOf(1, 99, null, 0),
            )
        } finally {
            verifyPGliteClose(database)
        }
    }
}

private object PGliteCommentedUsers : Table("pglite_commented_users", comment = "アプリケーション users") {
    val id = int("id")
    val email = string("email").comment("Primary user's email\nユーザーの主要メールアドレス")
}

private object PGliteCommentedConstraints : Table("pglite_commented_constraints") {
    val email = string("email")

    init {
        unique(email, name = "uq_pglite_commented_email", comment = "Email must be unique")
    }
}

private object PGliteConfiguredIdentityRows : Table("pglite_configured_identity_rows") {
    val id = long("id")
        .identity(startWith = CONFIGURED_IDENTITY_START, incrementBy = CONFIGURED_IDENTITY_INCREMENT)
        .primaryKey()
    val name = string("name")
}

private const val CONFIGURED_IDENTITY_START = 100L
private const val CONFIGURED_IDENTITY_INCREMENT = 10L
private val CONFIGURED_IDENTITY_VALUES = listOf(100L, 110L, 120L)

private object PGliteAlterOrganizations : Table("pglite_alter_organizations") {
    val id = int("id").primaryKey()
}

private object PGliteAlterUsers : Table("pglite_alter_users") {
    val id = int("id")
    val organizationId = int("organization_id")
    val email = string("email").nullable()
    val status = string("status").nullable()
    val quantity = int("quantity")
}

private object PGliteCrossUsers : Table("pglite_cross_users") {
    val id = int("id").primaryKey()
}

private object PGliteCrossRoles : Table("pglite_cross_roles") {
    val id = int("id").primaryKey()
}

private suspend fun configuredDatabase(storage: PGliteStorage): PGliteDatabase = PGliteDatabase.open(storage) {
    install(PostgresKotlinxDateTime)
    install(PostgresQuwacBigNumber)
}

private suspend fun PGliteDatabase.usePGlite(block: suspend (PGliteDatabase) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}

private fun removalOptions(): dynamic = js("({ recursive: true, force: true })")

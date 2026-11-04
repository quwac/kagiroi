@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter")

package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.kotlinx.datetime.PostgresKotlinxDateTime
import dev.quwac.kagiroi.postgres.quwac.bignumber.PostgresQuwacBigNumber
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.execute
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals

@JsModule("node:fs")
private external object NodeFileSystem : JsAny {
    fun mkdtempSync(prefix: String): String

    fun rmSync(path: String, options: JsAny)
}

@JsModule("node:os")
private external object NodeOperatingSystem : JsAny {
    fun tmpdir(): String
}

@JsModule("node:path")
private external object NodePath : JsAny {
    fun join(vararg paths: String): String
}

/** Represents pglite integration test. */
class PGliteIntegrationTest {
    /** Provides the in memory contract result. */
    @Test
    fun inMemoryContract() = runTest {
        val database = configuredDatabase(PGliteStorage.Memory)
        try {
            pgliteDriverContract(database)
        } finally {
            verifyPGliteClose(database)
        }
    }

    /** Provides the filesystem storage persists after close result. */
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

    /** Creates the create table persists table and column comments result. */
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

    /** Creates the create table and index persist unique constraint and index comments result. */
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

    /** Creates the creates configured identity and generates the configured values result. */
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

private fun removalOptions(): JsAny = js("({ recursive: true, force: true })")

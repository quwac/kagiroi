package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.renamedFrom
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createIndexes
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.dropIndex
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteDdlTest {
    @Test
    fun ignoresRenameLineageWhenRenderingOrdinaryDdl() {
        assertEquals(
            SqliteDialect.render(createTable(SqliteRenameBaseline)).sql,
            SqliteDialect.render(createTable(SqliteRenameMetadata)).sql,
        )
        assertEquals(
            SqliteDialect.render(createIndexes(SqliteRenameBaseline).single()).sql,
            SqliteDialect.render(createIndexes(SqliteRenameMetadata).single()).sql,
        )
    }

    @Test
    fun rendersCreateAndDropTableWithSqliteStorageClasses() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS \"main\".\"user\" (" +
                "\"id\" TEXT NOT NULL PRIMARY KEY, \"active\" INTEGER NOT NULL, " +
                "\"payload\" BLOB NOT NULL, \"email\" TEXT UNIQUE, \"nickname\" TEXT)",
            SqliteDialect.render(createTable(SqliteDdlUsers, ifNotExists = true)).sql,
        )
        assertEquals(
            "DROP TABLE IF EXISTS \"main\".\"user\"",
            SqliteDialect.render(dropTable(SqliteDdlUsers, ifExists = true)).sql,
        )
    }

    @Test
    fun rendersNativeAlterOperationsAndRejectsUnsupportedAddColumnConstraints() {
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(alterTable(SqliteDdlUsers) { addColumn(SqliteDdlUsers.email) })
        }
        assertEquals(
            "ALTER TABLE \"main\".\"user\" ADD COLUMN \"nickname\" TEXT",
            SqliteDialect.render(alterTable(SqliteDdlUsers) { addColumn(SqliteDdlUsers.nickname) }).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(alterTable(SqliteDdlUsers) { addColumn(SqliteDdlUsers.active) })
        }
        assertEquals(
            "ALTER TABLE \"main\".\"user\" DROP COLUMN \"nickname\"",
            SqliteDialect.render(alterTable(SqliteDdlUsers) { dropColumn(SqliteDdlUsers.nickname) }).sql,
        )
        assertEquals(
            "ALTER TABLE \"main\".\"user\" RENAME COLUMN \"nickname\" TO \"display_name\"",
            SqliteDialect.render(
                alterTable(SqliteDdlUsers) { renameColumn(SqliteDdlUsers.nickname, "display_name") },
            ).sql,
        )
        assertEquals(
            "ALTER TABLE \"main\".\"user\" RENAME TO \"people\"",
            SqliteDialect.render(alterTable(SqliteDdlUsers) { renameTable("people") }).sql,
        )

        assertEquals(
            listOf(
                "ALTER TABLE \"main\".\"user\" ADD COLUMN \"nickname\" TEXT",
                "ALTER TABLE \"main\".\"user\" RENAME TO \"people\"",
            ),
            SqliteDialect.renderDdl(
                alterTable(SqliteDdlUsers) {
                    addColumn(SqliteDdlUsers.nickname)
                    renameTable("people")
                },
            ).statements.map { it.sql },
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.renderDdl(
                alterTable(SqliteDdlUsers) {
                    addColumn(SqliteDdlUsers.nickname)
                    setDefault(SqliteDdlUsers.active, true)
                },
            )
        }
    }

    @Test
    fun rendersSingleCompositeUniqueAndDropIndexSyntax() {
        assertEquals(
            "CREATE INDEX \"main\".\"users_email_idx\" ON \"user\" (\"email\")",
            SqliteDialect.render(
                createIndex("users_email_idx", SqliteDdlUsers) { on(SqliteDdlUsers.email) },
            ).sql,
        )
        assertEquals(
            "CREATE UNIQUE INDEX \"main\".\"users_active_email_idx\" ON \"user\" (\"active\", \"email\")",
            SqliteDialect.render(
                createIndex("users_active_email_idx", SqliteDdlUsers, unique = true) {
                    on(SqliteDdlUsers.active, SqliteDdlUsers.email)
                },
            ).sql,
        )
        assertEquals(
            "DROP INDEX \"main\".\"users_email_idx\"",
            SqliteDialect.render(dropIndex("users_email_idx", SqliteDdlUsers)).sql,
        )
    }

    @Test
    fun rendersExpressionPartialAndOrderedIndexes() {
        assertEquals(
            "CREATE INDEX \"main\".\"active_users_email_idx\" ON \"user\" " +
                "(LOWER(\"email\") DESC, \"active\" ASC) WHERE \"active\" = 1",
            SqliteDialect.render(
                createIndex("active_users_email_idx", SqliteDdlUsers) {
                    on(lower(SqliteDdlUsers.email).desc(), SqliteDdlUsers.active.asc())
                    where(SqliteDdlUsers.active eq true)
                },
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(
                createIndex("covering_users_email_idx", SqliteDdlUsers) {
                    on(SqliteDdlUsers.email)
                    include(SqliteDdlUsers.id)
                },
            )
        }
    }

    @Test
    fun preservesSchemaCommentsWithoutEmittingSqliteSourceComments() {
        val rendered = SqliteDialect.renderDdl(createTable(SqliteCommentedUsers))

        assertEquals("Application users", SqliteCommentedUsers.comment)
        assertEquals("Primary user's email", SqliteCommentedUsers.email.comment)
        assertEquals(
            listOf("CREATE TABLE \"commented_users\" (\"email\" TEXT NOT NULL)"),
            rendered.statements.map { it.sql },
        )
    }

    @Test
    fun retainsIndexAndUniqueConstraintCommentsWithoutSqlitePersistence() {
        assertEquals("Email must be unique", SqliteCommentedConstraints.uniqueConstraints.single().comment)
        assertEquals(
            listOf(
                "CREATE TABLE \"commented_constraints\" (\"email\" TEXT NOT NULL, " +
                    "CONSTRAINT \"uq_commented_constraints_email\" UNIQUE (\"email\"))",
            ),
            SqliteDialect.renderDdl(createTable(SqliteCommentedConstraints)).statements.map { it.sql },
        )
        assertEquals(
            listOf("CREATE INDEX \"commented_email_idx\" ON \"commented_constraints\" (\"email\")"),
            SqliteDialect.renderDdl(
                createIndex("commented_email_idx", SqliteCommentedConstraints, comment = "Email lookup") {
                    on(SqliteCommentedConstraints.email)
                },
            ).statements.map { it.sql },
        )
    }

    @Test
    fun rendersRowIdIdentityAndBothGeneratedStorageModes() {
        assertEquals(
            "CREATE TABLE \"generated_orders\" (" +
                "\"id\" INTEGER PRIMARY KEY, \"price\" INTEGER NOT NULL, \"quantity\" INTEGER NOT NULL, " +
                "\"virtual_total\" INTEGER GENERATED ALWAYS AS (\"price\" * \"quantity\") VIRTUAL NOT NULL, " +
                "\"stored_total\" INTEGER GENERATED ALWAYS AS (\"price\" * \"quantity\") STORED NOT NULL)",
            SqliteDialect.render(createTable(SqliteGeneratedOrders)).sql,
        )
        assertEquals(
            "ALTER TABLE \"generated_orders\" ADD COLUMN \"virtual_total\" INTEGER " +
                "GENERATED ALWAYS AS (\"price\" * \"quantity\") VIRTUAL NOT NULL",
            SqliteDialect.render(
                alterTable(SqliteGeneratedOrders) { addColumn(SqliteGeneratedOrders.virtualTotal) },
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(alterTable(SqliteGeneratedOrders) { addColumn(SqliteGeneratedOrders.storedTotal) })
        }
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(alterTable(SqliteGeneratedOrders) { addColumn(SqliteGeneratedOrders.id) })
        }
    }

    @Test
    fun preservesDefaultRowIdIdentityAndRejectsConfiguredIdentityOptions() {
        assertEquals(
            "CREATE TABLE \"identity_default\" (\"id\" INTEGER PRIMARY KEY)",
            SqliteDialect.render(createTable(sqliteIdentityTable("identity_default"))).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(createTable(sqliteIdentityTable("identity_start", startWith = 1000L)))
        }
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(createTable(sqliteIdentityTable("identity_increment", incrementBy = 10L)))
        }
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(
                createTable(sqliteIdentityTable("identity_start_increment", startWith = 1000L, incrementBy = 10L)),
            )
        }
        assertFailsWith<UnsupportedDdlOperation> {
            SqliteDialect.render(createTable(SqliteMultipleIdentityColumns))
        }
    }
}

private fun sqliteIdentityTable(name: String, startWith: Long? = null, incrementBy: Long? = null): Table =
    object : Table(name) {
        val id = long("id").identity(startWith = startWith, incrementBy = incrementBy).primaryKey()
    }

private object SqliteDdlUsers : Table("user", schemaName = "main") {
    val id = uuid("id").primaryKey()
    val active = boolean("active")
    val payload = bytes("payload")
    val email = string("email").nullable().unique()
    val nickname = string("nickname").nullable()
}

private object SqliteRenameBaseline : Table("rename_metadata") {
    val displayName = string("display_name").index("rename_metadata_display_name_idx")
}

private object SqliteRenameMetadata : Table("rename_metadata") {
    val displayName = string("display_name")
        .renamedFrom("name")
        .index("rename_metadata_display_name_idx") {
            renamedFrom("users_name_idx")
        }

    init {
        renamedFrom("users")
    }
}

private object SqliteCommentedUsers : Table("commented_users", comment = "Application users") {
    val email = string("email").comment("Primary user's email")
}

private object SqliteCommentedConstraints : Table("commented_constraints") {
    val email = string("email")

    init {
        unique(
            email,
            name = "uq_commented_constraints_email",
            comment = "Email must be unique",
        )
    }
}

private object SqliteGeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity()
    val price = int("price")
    val quantity = int("quantity")
    val virtualTotal = int("virtual_total")
        .generated(price * quantity, GeneratedColumnStorage.Virtual)
    val storedTotal = int("stored_total").generated(price * quantity, GeneratedColumnStorage.Stored)
}

private object SqliteMultipleIdentityColumns : Table("multiple_identity_columns") {
    val first = long("first").identity()
    val second = long("second").identity()
}

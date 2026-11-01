package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.SqlTypeCodec
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.dropIndex
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresDdlTest {
    @Test
    fun rendersCreateAndDropTableWithNativePostgresTypes() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS \"app\".\"user\" (" +
                "\"id\" UUID NOT NULL PRIMARY KEY, \"active\" BOOLEAN NOT NULL, " +
                "\"payload\" BYTEA NOT NULL, \"duration_value\" TEXT NOT NULL, \"email\" TEXT UNIQUE)",
            PostgresDialect.render(createTable(PostgresDdlUsers, ifNotExists = true)).sql,
        )
        assertEquals(
            "DROP TABLE IF EXISTS \"app\".\"user\"",
            PostgresDialect.render(dropTable(PostgresDdlUsers, ifExists = true)).sql,
        )
    }

    @Test
    fun rendersAllGenericAlterOperations() {
        assertEquals(
            "ALTER TABLE \"app\".\"user\" ADD COLUMN \"email\" TEXT UNIQUE",
            PostgresDialect.render(alterTable(PostgresDdlUsers) { addColumn(PostgresDdlUsers.email) }).sql,
        )
        assertEquals(
            "ALTER TABLE \"app\".\"user\" DROP COLUMN \"email\"",
            PostgresDialect.render(alterTable(PostgresDdlUsers) { dropColumn(PostgresDdlUsers.email) }).sql,
        )
        assertEquals(
            "ALTER TABLE \"app\".\"user\" RENAME COLUMN \"email\" TO \"contact\"",
            PostgresDialect.render(
                alterTable(PostgresDdlUsers) { renameColumn(PostgresDdlUsers.email, "contact") },
            ).sql,
        )
        assertEquals(
            "ALTER TABLE \"app\".\"user\" RENAME TO \"people\"",
            PostgresDialect.render(alterTable(PostgresDdlUsers) { renameTable("people") }).sql,
        )
    }

    @Test
    fun plansOrderedAlterOperationsAsOnePostgresStatementWhenLegal() {
        val statement = alterTable(PostgresDdlUsers) {
            setNotNull(PostgresDdlUsers.email)
            setDefault(PostgresDdlUsers.active, true)
        }

        assertEquals(
            listOf(
                "ALTER TABLE \"app\".\"user\" ALTER COLUMN \"email\" SET NOT NULL, " +
                    "ALTER COLUMN \"active\" SET DEFAULT TRUE",
            ),
            PostgresDialect.renderDdl(statement).statements.map { it.sql },
        )
    }

    @Test
    fun plansRenameAfterEarlierOperationsAsASeparatePostgresStatement() {
        val statement = alterTable(PostgresDdlUsers) {
            setNotNull(PostgresDdlUsers.email)
            renameTable("people")
        }

        assertEquals(
            listOf(
                "ALTER TABLE \"app\".\"user\" ALTER COLUMN \"email\" SET NOT NULL",
                "ALTER TABLE \"app\".\"user\" RENAME TO \"people\"",
            ),
            PostgresDialect.renderDdl(statement).statements.map { it.sql },
        )
    }

    @Test
    fun rendersAllNewAlterOperationsInTheirDeclaredOrder() {
        val statement = alterTable(PostgresDdlUsers) {
            addPrimaryKey(PostgresDdlUsers.id, name = "pk_user")
            addUnique(PostgresDdlUsers.email, name = "uq_user_email")
            addForeignKey(PostgresDdlUsers.id, name = "fk_user_organization")
                .references(PostgresDdlOrganizations.id)
            addCheck("ck_user_active") { PostgresDdlUsers.active eq true }
            alterColumnType(PostgresDdlUsers.email, PostgresTypes.varchar(255))
            setDefault(PostgresDdlUsers.active, true)
            dropDefault(PostgresDdlUsers.active)
            setNotNull(PostgresDdlUsers.email)
            dropNotNull(PostgresDdlUsers.email)
            dropPrimaryKey("pk_user")
            dropUnique("uq_user_email")
            dropForeignKey("fk_user_organization")
            dropCheck("ck_user_active")
        }

        assertEquals(
            listOf(
                "ALTER TABLE \"app\".\"user\" " +
                    "ADD CONSTRAINT \"pk_user\" PRIMARY KEY (\"id\"), " +
                    "ADD CONSTRAINT \"uq_user_email\" UNIQUE (\"email\"), " +
                    "ADD CONSTRAINT \"fk_user_organization\" FOREIGN KEY (\"id\") " +
                    "REFERENCES \"app\".\"organization\" (\"id\"), " +
                    "ADD CONSTRAINT \"ck_user_active\" CHECK (\"active\" = TRUE), " +
                    "ALTER COLUMN \"email\" TYPE CHARACTER VARYING(255), " +
                    "ALTER COLUMN \"active\" SET DEFAULT TRUE, " +
                    "ALTER COLUMN \"active\" DROP DEFAULT, " +
                    "ALTER COLUMN \"email\" SET NOT NULL, " +
                    "ALTER COLUMN \"email\" DROP NOT NULL, " +
                    "DROP CONSTRAINT \"pk_user\", DROP CONSTRAINT \"uq_user_email\", " +
                    "DROP CONSTRAINT \"fk_user_organization\", DROP CONSTRAINT \"ck_user_active\"",
            ),
            PostgresDialect.renderDdl(statement).statements.map { it.sql },
        )
    }

    @Test
    fun rendersIndexesWithPostgresSchemaRules() {
        assertEquals(
            "CREATE UNIQUE INDEX \"users_email_idx\" ON \"app\".\"user\" (\"email\", \"active\")",
            PostgresDialect.render(
                createIndex("users_email_idx", PostgresDdlUsers, unique = true) {
                    on(PostgresDdlUsers.email, PostgresDdlUsers.active)
                },
            ).sql,
        )
        assertEquals(
            "DROP INDEX \"app\".\"users_email_idx\"",
            PostgresDialect.render(dropIndex("users_email_idx", PostgresDdlUsers)).sql,
        )
    }

    @Test
    fun rendersExpressionPartialCoveringAndOrderedIndexes() {
        assertEquals(
            "CREATE UNIQUE INDEX \"users_email_lower_idx\" ON \"app\".\"user\" " +
                "((LOWER(\"email\")) ASC, \"active\" DESC) INCLUDE (\"id\") WHERE \"active\" = TRUE",
            PostgresDialect.render(
                createIndex("users_email_lower_idx", PostgresDdlUsers, unique = true) {
                    on(lower(PostgresDdlUsers.email).asc(), PostgresDdlUsers.active.desc())
                    include(PostgresDdlUsers.id)
                    where(PostgresDdlUsers.active eq true)
                },
            ).sql,
        )
    }

    @Test
    fun rendersIndexAndUniqueConstraintCommentsAsPostgresMetadataStatements() {
        assertEquals(
            listOf(
                "CREATE TABLE \"commented_constraints\" (\"email\" TEXT NOT NULL, " +
                    "CONSTRAINT \"uq_commented_constraints_email\" UNIQUE (\"email\"))",
                "COMMENT ON CONSTRAINT \"uq_commented_constraints_email\" ON " +
                    "\"commented_constraints\" IS 'Email must be unique'",
            ),
            PostgresDialect.renderDdl(createTable(PostgresCommentedConstraints)).statements.map { it.sql },
        )
        assertEquals(
            listOf(
                "CREATE INDEX \"commented_email_idx\" ON \"commented_constraints\" (\"email\")",
                "COMMENT ON INDEX \"commented_email_idx\" IS 'Email lookup'",
            ),
            PostgresDialect.renderDdl(
                createIndex("commented_email_idx", PostgresCommentedConstraints, comment = "Email lookup") {
                    on(PostgresCommentedConstraints.email)
                },
            ).statements.map { it.sql },
        )
    }

    @Test
    fun usesInstalledExtensionTypeMappingsForDdl() {
        val dialect = PostgresDialect.configured(listOf(CustomPostgresTypes))

        assertEquals(
            "CREATE TABLE \"custom_values\" (\"value\" DATE NOT NULL)",
            dialect.render(createTable(CustomValues)).sql,
        )
    }

    @Test
    fun rendersNativeParameterizedAndNamedPostgresTypes() {
        assertEquals(
            "CREATE TABLE \"postgres_extended_values\" (" +
                "\"code\" CHARACTER VARYING(40) NOT NULL, \"mask\" BIT(8) NOT NULL, " +
                "\"tags\" TEXT[] NOT NULL, \"state\" \"app\".\"order_state\" NOT NULL)",
            PostgresDialect.render(createTable(PostgresExtendedValues)).sql,
        )
    }

    @Test
    fun keepsPostgresSerialInsideTheDialectTypeCatalog() {
        assertEquals(
            "CREATE TABLE \"postgres_serial_values\" (\"id\" SERIAL NOT NULL PRIMARY KEY)",
            PostgresDialect.render(createTable(PostgresSerialValues)).sql,
        )
    }

    @Test
    fun retainsTheDeprecatedSnapshotTypeForExistingSchemas() {
        assertEquals("TXID_SNAPSHOT", PostgresTypes.txidSnapshot.sqlDeclaration)
    }

    @Test
    fun rendersCreateCommentsAsOrderedPostgresMetadataStatements() {
        assertEquals(
            listOf(
                "CREATE TABLE \"app\".\"commented_users\" (\"id\" UUID NOT NULL PRIMARY KEY, " +
                    "\"email\" TEXT NOT NULL, \"display_name\" TEXT NOT NULL)",
                "COMMENT ON TABLE \"app\".\"commented_users\" IS 'Application users'",
                "COMMENT ON COLUMN \"app\".\"commented_users\".\"id\" IS 'User identifier'",
                "COMMENT ON COLUMN \"app\".\"commented_users\".\"email\" IS " +
                    "'Primary user''s email\nユーザーの主要メールアドレス'",
                "COMMENT ON COLUMN \"app\".\"commented_users\".\"display_name\" IS " +
                    "'''; DROP TABLE users; --'",
            ),
            PostgresDialect.renderDdl(createTable(PostgresCommentedUsers)).statements.map { it.sql },
        )
    }

    @Test
    fun rendersIdentityAndStoredGeneratedColumnsAndRejectsVirtualStorage() {
        assertEquals(
            "CREATE TABLE \"generated_orders\" (" +
                "\"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY, " +
                "\"price\" INTEGER NOT NULL, \"quantity\" INTEGER NOT NULL, " +
                "\"total\" INTEGER GENERATED ALWAYS AS (\"price\" * \"quantity\") STORED NOT NULL)",
            PostgresDialect.render(createTable(PostgresGeneratedOrders)).sql,
        )
        assertEquals(
            "ALTER TABLE \"generated_orders\" ADD COLUMN \"total\" INTEGER " +
                "GENERATED ALWAYS AS (\"price\" * \"quantity\") STORED NOT NULL",
            PostgresDialect.render(
                alterTable(PostgresGeneratedOrders) { addColumn(PostgresGeneratedOrders.total) },
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            PostgresDialect.render(createTable(PostgresVirtualGeneratedOrders))
        }
    }

    @Test
    fun rendersIdentityStartAndIncrementOptionsWithoutChangingDefaultIdentitySql() {
        assertEquals(
            "CREATE TABLE \"identity_default\" (\"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY)",
            PostgresDialect.render(createTable(postgresIdentityTable("identity_default"))).sql,
        )
        assertEquals(
            "CREATE TABLE \"identity_start\" (\"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY " +
                "(START WITH 1000) NOT NULL PRIMARY KEY)",
            PostgresDialect.render(createTable(postgresIdentityTable("identity_start", startWith = 1000L))).sql,
        )
        assertEquals(
            "CREATE TABLE \"identity_increment\" (\"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY " +
                "(INCREMENT BY -10) NOT NULL PRIMARY KEY)",
            PostgresDialect.render(createTable(postgresIdentityTable("identity_increment", incrementBy = -10L))).sql,
        )
        assertEquals(
            "CREATE TABLE \"identity_start_increment\" (\"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY " +
                "(START WITH 1000 INCREMENT BY 10) NOT NULL PRIMARY KEY)",
            PostgresDialect.render(
                createTable(postgresIdentityTable("identity_start_increment", startWith = 1000L, incrementBy = 10L)),
            ).sql,
        )
    }
}

private fun postgresIdentityTable(name: String, startWith: Long? = null, incrementBy: Long? = null): Table =
    object : Table(name) {
        val id = long("id").identity(startWith = startWith, incrementBy = incrementBy).primaryKey()
    }

private object PostgresDdlUsers : Table("user", schemaName = "app") {
    val id = uuid("id").primaryKey()
    val active = boolean("active")
    val payload = bytes("payload")
    val durationValue = duration("duration_value")
    val email = string("email").nullable().unique()
}

private object PostgresDdlOrganizations : Table("organization", schemaName = "app") {
    val id = uuid("id").primaryKey()
}

private object CustomType : ColumnType<String>("Custom")

private object CustomValues : Table("custom_values") {
    val value = column("value", CustomType)
}

private object CustomPostgresTypes : PostgresExtension {
    override val typeMappings = listOf(PostgresTypeMapping(CustomType, PostgresType.DATE))
    override val typeCodecs: List<SqlTypeCodec<*, *>> = emptyList()
}

private object PostgresExtendedValues : Table("postgres_extended_values") {
    val code = column("code", PostgresTypes.varchar(40))
    val mask = column("mask", PostgresTypes.bit(8))
    val tags = column("tags", PostgresTypes.array(PostgresTypes.text))
    val state = column("state", PostgresTypes.namedText("app", "order_state"))
}

private object PostgresSerialValues : Table("postgres_serial_values") {
    val id = column("id", PostgresTypes.serial).primaryKey()
}

private object PostgresCommentedUsers : Table("commented_users", schemaName = "app", comment = "Application users") {
    val id = uuid("id").primaryKey().comment("User identifier")
    val email = string("email").comment("Primary user's email\nユーザーの主要メールアドレス")
    val displayName = string("display_name").comment("'; DROP TABLE users; --")
}

private object PostgresCommentedConstraints : Table("commented_constraints") {
    val email = string("email")

    init {
        unique(
            email,
            name = "uq_commented_constraints_email",
            comment = "Email must be unique",
        )
    }
}

private object PostgresGeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity()
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Stored)
}

private object PostgresVirtualGeneratedOrders : Table("postgres_virtual_generated_orders") {
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Virtual)
}

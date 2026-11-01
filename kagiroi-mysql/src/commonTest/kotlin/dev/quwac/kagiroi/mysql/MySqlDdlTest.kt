package dev.quwac.kagiroi.mysql

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
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.dropIndex
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MySqlDdlTest {
    @Test
    fun rendersCreateAndDropTableWithMySqlStorageMappings() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `app`.`user` (" +
                "`id` CHAR(36) NOT NULL PRIMARY KEY, `binary_id` BINARY(16) NOT NULL, " +
                "`name` TEXT NOT NULL, `active` BOOLEAN NOT NULL, `payload` BLOB NOT NULL, " +
                "`signed` BIGINT NOT NULL, `unsigned` BIGINT UNSIGNED NOT NULL, `duration_value` TEXT NOT NULL, " +
                "`nickname` TEXT)",
            MySqlDialect.render(createTable(MySqlDdlUsers, ifNotExists = true)).sql,
        )
        assertEquals(
            "DROP TABLE IF EXISTS `app`.`user`",
            MySqlDialect.render(dropTable(MySqlDdlUsers, ifExists = true)).sql,
        )
    }

    @Test
    fun rendersAllGenericAlterOperations() {
        assertEquals(
            "ALTER TABLE `app`.`user` ADD COLUMN `nickname` TEXT",
            MySqlDialect.render(alterTable(MySqlDdlUsers) { addColumn(MySqlDdlUsers.nickname) }).sql,
        )
        assertEquals(
            "ALTER TABLE `app`.`user` DROP COLUMN `nickname`",
            MySqlDialect.render(alterTable(MySqlDdlUsers) { dropColumn(MySqlDdlUsers.nickname) }).sql,
        )
        assertEquals(
            "ALTER TABLE `app`.`user` RENAME COLUMN `nickname` TO `display_name`",
            MySqlDialect.render(
                alterTable(MySqlDdlUsers) { renameColumn(MySqlDdlUsers.nickname, "display_name") },
            ).sql,
        )
        assertEquals(
            "ALTER TABLE `app`.`user` RENAME TO `app`.`people`",
            MySqlDialect.render(alterTable(MySqlDdlUsers) { renameTable("people") }).sql,
        )
        assertEquals(
            listOf(
                "ALTER TABLE `app`.`user` ADD CONSTRAINT `uq_user_active` UNIQUE (`active`), " +
                    "ALTER COLUMN `active` SET DEFAULT TRUE",
            ),
            MySqlDialect.renderDdl(
                alterTable(MySqlDdlUsers) {
                    addUnique(MySqlDdlUsers.active, name = "uq_user_active")
                    setDefault(MySqlDdlUsers.active, true)
                },
            ).statements.map { it.sql },
        )
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.renderDdl(alterTable(MySqlDdlUsers) { setNotNull(MySqlDdlUsers.nickname) })
        }
    }

    @Test
    fun rendersIndexesWithRequiredTableForDrop() {
        assertEquals(
            "CREATE UNIQUE INDEX `users_active_idx` ON `app`.`user` (`active`)",
            MySqlDialect.render(
                createIndex("users_active_idx", MySqlDdlUsers, unique = true) {
                    on(MySqlDdlUsers.active)
                },
            ).sql,
        )
        assertEquals(
            "DROP INDEX `users_active_idx` ON `app`.`user`",
            MySqlDialect.render(dropIndex("users_active_idx", MySqlDdlUsers)).sql,
        )
    }

    @Test
    fun rendersBoundedFunctionalKeyPartsAndRejectsPartialAndIncludeClauses() {
        assertEquals(
            "CREATE INDEX `code_lower_idx` ON `mysql_extended_values` ((LOWER(`code`)) DESC)",
            MySqlDialect.render(
                createIndex("code_lower_idx", MySqlExtendedValues) {
                    on(lower(MySqlExtendedValues.code).desc())
                },
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(
                createIndex("active_code_idx", MySqlExtendedValues) {
                    on(MySqlExtendedValues.code)
                    where(MySqlExtendedValues.quantity eq 1u)
                },
            )
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(
                createIndex("covering_code_idx", MySqlExtendedValues) {
                    on(MySqlExtendedValues.code)
                    include(MySqlExtendedValues.quantity)
                },
            )
        }
    }

    @Test
    fun rejectsTextKeysWithoutAnExplicitPrefixLength() {
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(createTable(MySqlTextKey))
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(createIndex("users_name_idx", MySqlDdlUsers) { on(MySqlDdlUsers.name) })
        }
    }

    @Test
    fun rendersParameterizedAndVendorSpecificMySqlTypes() {
        assertEquals(
            "CREATE TABLE `mysql_extended_values` (" +
                "`code` VARCHAR(40) NOT NULL, `quantity` MEDIUMINT UNSIGNED NOT NULL, " +
                "`amount` DECIMAL(20,6) NOT NULL, `flags` BIT(16) NOT NULL, `metadata` JSON NOT NULL, " +
                "`state` ENUM('draft','done') NOT NULL, `labels` SET('new','priority') NOT NULL, " +
                "`shape` POINT NOT NULL)",
            MySqlDialect.render(createTable(MySqlExtendedValues)).sql,
        )
    }

    @Test
    fun rejectsIndexesThatNeedVendorSpecificSyntax() {
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(createIndex("metadata_idx", MySqlExtendedValues) { on(MySqlExtendedValues.metadata) })
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(createIndex("shape_idx", MySqlExtendedValues) { on(MySqlExtendedValues.shape) })
        }
    }

    @Test
    fun keepsMySqlSerialInsideTheDialectTypeCatalog() {
        assertEquals(
            "CREATE TABLE `mysql_serial_values` (`id` SERIAL NOT NULL PRIMARY KEY)",
            MySqlDialect.render(createTable(MySqlSerialValues)).sql,
        )
    }

    @Test
    fun rendersTableAndColumnCommentsInsideCreateTable() {
        assertEquals(
            "CREATE TABLE `commented_users` (`id` CHAR(36) NOT NULL PRIMARY KEY COMMENT 'User identifier', " +
                "`email` TEXT NOT NULL COMMENT 'Primary user''s email\nユーザーの主要メールアドレス', " +
                "`display_name` TEXT NOT NULL COMMENT '''; DROP TABLE users; --') " +
                "COMMENT = 'Application users'",
            MySqlDialect.render(createTable(MySqlCommentedUsers)).sql,
        )
    }

    @Test
    fun rendersIndexAndUniqueConstraintCommentsInsideMySqlDdl() {
        assertEquals(
            "CREATE TABLE `commented_constraints` (`email` VARCHAR(40) NOT NULL, " +
                "CONSTRAINT `uq_commented_constraints_email` UNIQUE (`email`) COMMENT 'Email must be unique')",
            MySqlDialect.render(createTable(MySqlCommentedConstraints)).sql,
        )
        assertEquals(
            "CREATE INDEX `commented_email_idx` ON `commented_constraints` (`email`) COMMENT 'Email lookup'",
            MySqlDialect.render(
                createIndex("commented_email_idx", MySqlCommentedConstraints, comment = "Email lookup") {
                    on(MySqlCommentedConstraints.email)
                },
            ).sql,
        )
    }

    @Test
    fun rendersAutoIncrementAndBothGeneratedStorageModes() {
        assertEquals(
            "CREATE TABLE `generated_orders` (" +
                "`id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, `price` INT NOT NULL, `quantity` INT NOT NULL, " +
                "`virtual_total` INT GENERATED ALWAYS AS (`price` * `quantity`) VIRTUAL NOT NULL, " +
                "`stored_total` INT GENERATED ALWAYS AS (`price` * `quantity`) STORED NOT NULL)",
            MySqlDialect.render(createTable(MySqlGeneratedOrders)).sql,
        )
        assertEquals(
            "ALTER TABLE `generated_orders` ADD COLUMN `stored_total` INT " +
                "GENERATED ALWAYS AS (`price` * `quantity`) STORED NOT NULL",
            MySqlDialect.render(alterTable(MySqlGeneratedOrders) { addColumn(MySqlGeneratedOrders.storedTotal) }).sql,
        )
    }

    @Test
    fun rendersIdentityStartAsATableOptionAndRejectsUnsupportedIncrementOptions() {
        assertEquals(
            "CREATE TABLE `identity_default` (`id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY)",
            MySqlDialect.render(createTable(mySqlIdentityTable("identity_default"))).sql,
        )
        assertEquals(
            "CREATE TABLE `identity_start` (`id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY) AUTO_INCREMENT = 1000",
            MySqlDialect.render(createTable(mySqlIdentityTable("identity_start", startWith = 1000L))).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(createTable(mySqlIdentityTable("identity_increment", incrementBy = 10L)))
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MySqlDialect.render(
                createTable(mySqlIdentityTable("identity_start_increment", startWith = 1000L, incrementBy = 10L)),
            )
        }
    }
}

private fun mySqlIdentityTable(name: String, startWith: Long? = null, incrementBy: Long? = null): Table =
    object : Table(name) {
        val id = long("id").identity(startWith = startWith, incrementBy = incrementBy).primaryKey()
    }

private object MySqlDdlUsers : Table("user", schemaName = "app") {
    val id = uuid("id").primaryKey()
    val binaryId = binaryUuid("binary_id")
    val name = string("name")
    val active = boolean("active")
    val payload = bytes("payload")
    val signed = long("signed")
    val unsigned = ulong("unsigned")
    val durationValue = duration("duration_value")
    val nickname = string("nickname").nullable()
}

private object MySqlTextKey : Table("text_key") {
    val value = string("value").unique()
}

private object MySqlExtendedValues : Table("mysql_extended_values") {
    val code = column("code", MySqlTypes.varchar(40))
    val quantity = column("quantity", MySqlTypes.mediumIntUnsigned)
    val amount = column("amount", MySqlTypes.decimal(20, 6))
    val flags = column("flags", MySqlTypes.bit(16))
    val metadata = column("metadata", MySqlTypes.json)
    val state = column("state", MySqlTypes.enum("draft", "done"))
    val labels = column("labels", MySqlTypes.set("new", "priority"))
    val shape = column("shape", MySqlTypes.point)
}

private object MySqlSerialValues : Table("mysql_serial_values") {
    val id = column("id", MySqlTypes.serial).primaryKey()
}

private object MySqlCommentedUsers : Table("commented_users", comment = "Application users") {
    val id = uuid("id").primaryKey().comment("User identifier")
    val email = string("email").comment("Primary user's email\nユーザーの主要メールアドレス")
    val displayName = string("display_name").comment("'; DROP TABLE users; --")
}

private object MySqlCommentedConstraints : Table("commented_constraints") {
    val email = column("email", MySqlTypes.varchar(40))

    init {
        unique(
            email,
            name = "uq_commented_constraints_email",
            comment = "Email must be unique",
        )
    }
}

private object MySqlGeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity()
    val price = column("price", MySqlTypes.integer)
    val quantity = column("quantity", MySqlTypes.integer)
    val virtualTotal = column("virtual_total", MySqlTypes.integer)
        .generated(price * quantity, GeneratedColumnStorage.Virtual)
    val storedTotal = column("stored_total", MySqlTypes.integer)
        .generated(price * quantity, GeneratedColumnStorage.Stored)
}

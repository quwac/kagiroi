package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.mysql.MySqlStandardTypes
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlCompatibleKotlinxDateTimeMappings
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.concat
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.dropIndex
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.isDistinctFrom
import dev.quwac.kagiroi.sql.isNotDistinctFrom
import dev.quwac.kagiroi.sql.isNotNull
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.leftJoinUsing
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.or
import dev.quwac.kagiroi.sql.rightJoin
import dev.quwac.kagiroi.sql.rightJoinUsing
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MariaDbDialectTest {
    @Test
    fun rendersNullSafeComparisonsWithMariaDbNativeEquality() {
        val rendered = SqlRenderer(MariaDbDialect).render(
            select(MariaDbUsers.id)
                .from(MariaDbUsers)
                .where(
                    (MariaDbUsers.name isNotDistinctFrom "Ada") and
                        (MariaDbUsers.name isDistinctFrom "Grace"),
                ),
        )

        assertEquals(
            "SELECT `app`.`user`.`id` FROM `app`.`user` WHERE " +
                "((`app`.`user`.`na``me` <=> ?) AND NOT (`app`.`user`.`na``me` <=> ?))",
            rendered.sql,
        )
        assertEquals(listOf("Ada", "Grace"), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersNativeInnerLeftAndRightUsingJoins() {
        val employee = MariaDbUsers.alias("employee")
        val manager = MariaDbUsers.alias("manager")
        val renderer = SqlRenderer(MariaDbDialect)
        val inner = select(employee[MariaDbUsers.id], manager[MariaDbUsers.name])
            .from(employee)
            .innerJoinUsing(manager) { using(employee[MariaDbUsers.id], manager[MariaDbUsers.id]) }
        val left = select(employee[MariaDbUsers.id], manager[MariaDbUsers.name].optional)
            .from(employee)
            .leftJoinUsing(manager) {
                using(employee[MariaDbUsers.id], manager[MariaDbUsers.id])
                using(employee[MariaDbUsers.name], manager[MariaDbUsers.name])
            }
        val right = select(employee[MariaDbUsers.id].optional, manager[MariaDbUsers.name])
            .from(employee)
            .rightJoinUsing(manager) { using(employee[MariaDbUsers.id], manager[MariaDbUsers.id]) }

        assertEquals(
            "SELECT `employee`.`id`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "INNER JOIN `app`.`user` AS `manager` USING (`id`)",
            renderer.render(inner).sql,
        )
        assertEquals(
            "SELECT `employee`.`id`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "LEFT JOIN `app`.`user` AS `manager` USING (`id`, `na``me`)",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT `employee`.`id`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "RIGHT JOIN `app`.`user` AS `manager` USING (`id`)",
            renderer.render(right).sql,
        )
    }

    @Test
    fun rendersCrossJoinWithoutOn() {
        val employee = MariaDbUsers.alias("employee")
        val coworker = MariaDbUsers.alias("coworker")
        val query = select(employee[MariaDbUsers.id], coworker[MariaDbUsers.name])
            .from(employee)
            .crossJoin(coworker)

        assertEquals(
            "SELECT `employee`.`id`, `coworker`.`na``me` FROM `app`.`user` AS `employee` " +
                "CROSS JOIN `app`.`user` AS `coworker`",
            SqlRenderer(MariaDbDialect).render(query).sql,
        )
    }

    @Test
    fun rendersLeftAndRightJoinsAndRejectsFullOuterJoin() {
        val employee = MariaDbUsers.alias("employee")
        val manager = MariaDbUsers.alias("manager")
        val renderer = SqlRenderer(MariaDbDialect)
        val left = select(employee[MariaDbUsers.name], manager[MariaDbUsers.name].optional)
            .from(employee)
            .leftJoin(manager, on = employee[MariaDbUsers.id] eq manager[MariaDbUsers.id])
        val right = select(employee[MariaDbUsers.name].optional, manager[MariaDbUsers.name])
            .from(employee)
            .rightJoin(manager, on = employee[MariaDbUsers.id] eq manager[MariaDbUsers.id])

        assertEquals(
            "SELECT `employee`.`na``me`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "LEFT JOIN `app`.`user` AS `manager` ON `employee`.`id` = `manager`.`id`",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT `employee`.`na``me`, `manager`.`na``me` FROM `app`.`user` AS `employee` " +
                "RIGHT JOIN `app`.`user` AS `manager` ON `employee`.`id` = `manager`.`id`",
            renderer.render(right).sql,
        )

        val full = select(employee[MariaDbUsers.id].optional, manager[MariaDbUsers.id].optional)
            .from(employee)
            .fullOuterJoin(manager, on = employee[MariaDbUsers.id] eq manager[MariaDbUsers.id])
        val failure = assertFailsWith<UnsupportedCapability> { renderer.render(full) }

        assertEquals("full-join", failure.capabilityName)
    }

    @Test
    fun rendersPortableFunctionNamesWithCharacterLength() {
        val normalized = lower(MariaDbUsers.name).alias("normalized_name")
        val characterLength = length(MariaDbUsers.name).alias("name_length")
        val rowCount = count().alias("row_count")

        assertEquals(
            "SELECT LOWER(`app`.`user`.`na``me`) AS `normalized_name`, " +
                "CHAR_LENGTH(`app`.`user`.`na``me`) AS `name_length`, COUNT(*) AS `row_count` FROM `app`.`user` " +
                "GROUP BY LOWER(`app`.`user`.`na``me`), CHAR_LENGTH(`app`.`user`.`na``me`)",
            SqlRenderer(MariaDbDialect).render(
                select(normalized, characterLength, rowCount).from(MariaDbUsers).groupBy(normalized, characterLength),
            ).sql,
        )
    }

    @Test
    fun rendersMariaDbSpecificSubstringConcatAndCurrentTimestamp() {
        val rendered = SqlRenderer(MariaDbDialect).render(
            select(
                substring(MariaDbUsers.name, 1, 2).alias("prefix"),
                concat(MariaDbUsers.name, MariaDbUsers.name).alias("doubled"),
                currentTimestamp().alias("observed_at"),
            ).from(MariaDbUsers),
        )

        assertEquals(
            "SELECT SUBSTRING(`app`.`user`.`na``me`, ?, ?) AS `prefix`, " +
                "CONCAT(`app`.`user`.`na``me`, `app`.`user`.`na``me`) AS `doubled`, " +
                "UTC_TIMESTAMP(6) AS `observed_at` FROM `app`.`user`",
            rendered.sql,
        )
        assertEquals(listOf(1, 2), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersMariaDbCurrentDateAndTime() {
        assertEquals("CURRENT_DATE", MariaDbDialect.renderFunction(SqlFunction.CurrentDate, emptyList()))
        assertEquals("CURRENT_TIME", MariaDbDialect.renderFunction(SqlFunction.CurrentTime, emptyList()))
        assertEquals("UTC_TIMESTAMP(6)", MariaDbDialect.renderFunction(SqlFunction.CurrentTimestamp, emptyList()))
    }

    @Test
    fun rendersParameterizedCrudPredicatesAndPagination() {
        val select = SqlRenderer(MariaDbDialect).render(
            select(MariaDbUsers.id, MariaDbUsers.name)
                .from(MariaDbUsers)
                .where((MariaDbUsers.id eq 42L) and (MariaDbUsers.nickname.isNotNull() or (MariaDbUsers.name eq "Ada")))
                .limit(10)
                .offset(5),
        )
        assertEquals(
            "SELECT `app`.`user`.`id`, `app`.`user`.`na``me` FROM `app`.`user` " +
                "WHERE (`app`.`user`.`id` = ? AND (`app`.`user`.`nickname` IS NOT NULL OR " +
                "`app`.`user`.`na``me` = ?)) LIMIT ? OFFSET ?",
            select.sql,
        )
        assertEquals(listOf(42L, "Ada", 10, 5), select.parameters.map { it.value })

        assertEquals(
            "INSERT INTO `app`.`user` (`id`, `na``me`) VALUES (?, ?)",
            SqlRenderer(MariaDbDialect).render(
                insertInto(MariaDbUsers).values {
                    set(MariaDbUsers.id, 7L)
                    set(MariaDbUsers.name, "Lin")
                },
            ).sql,
        )
        assertEquals(
            "UPDATE `app`.`user` SET `na``me` = ? WHERE `app`.`user`.`id` = ?",
            SqlRenderer(MariaDbDialect).render(
                update(MariaDbUsers) { set(MariaDbUsers.name, "Lin") }.where(MariaDbUsers.id eq 7L),
            ).sql,
        )
        assertEquals(
            "DELETE FROM `app`.`user` WHERE `app`.`user`.`id` = ?",
            SqlRenderer(MariaDbDialect).render(deleteFrom(MariaDbUsers).where(MariaDbUsers.id eq 7L)).sql,
        )
    }

    @Test
    fun rendersGenericDdlWithMariaDbPhysicalMappings() {
        assertEquals(
            "CREATE TABLE IF NOT EXISTS `app`.`user` (`id` BIGINT NOT NULL PRIMARY KEY, `uuid` UUID NOT NULL, " +
                "`legacy_uuid` CHAR(36) NOT NULL, `binary_uuid` BINARY(16) NOT NULL, " +
                "`na``me` TEXT NOT NULL, `active` BOOLEAN NOT NULL, " +
                "`signed` BIGINT NOT NULL, `unsigned` BIGINT UNSIGNED NOT NULL, `payload` BLOB NOT NULL, " +
                "`instant_value` DATETIME(6) NOT NULL, `duration_value` TEXT NOT NULL, `nickname` TEXT)",
            MariaDbDialect.render(createTable(MariaDbUsers, ifNotExists = true)).sql,
        )
        assertEquals("DROP TABLE IF EXISTS `app`.`user`", MariaDbDialect.render(dropTable(MariaDbUsers, true)).sql)
        assertEquals(
            "ALTER TABLE `app`.`user` RENAME COLUMN `nickname` TO `display_name`",
            MariaDbDialect.render(alterTable(MariaDbUsers) { renameColumn(MariaDbUsers.nickname, "display_name") }).sql,
        )
        assertEquals(
            listOf(
                "ALTER TABLE `app`.`user` ADD CONSTRAINT `uq_user_active` UNIQUE (`active`), " +
                    "ALTER COLUMN `active` SET DEFAULT TRUE",
            ),
            MariaDbDialect.renderDdl(
                alterTable(MariaDbUsers) {
                    addUnique(MariaDbUsers.active, name = "uq_user_active")
                    setDefault(MariaDbUsers.active, true)
                },
            ).statements.map { it.sql },
        )
        assertEquals(
            "CREATE UNIQUE INDEX `user_active_idx` ON `app`.`user` (`active`)",
            MariaDbDialect.render(
                createIndex("user_active_idx", MariaDbUsers, unique = true) { on(MariaDbUsers.active) },
            ).sql,
        )
        assertEquals(
            "DROP INDEX `user_active_idx` ON `app`.`user`",
            MariaDbDialect.render(dropIndex("user_active_idx", MariaDbUsers)).sql,
        )
    }

    @Test
    fun rejectsTextKeysWithoutAnExplicitPrefixLength() {
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(createTable(MariaDbTextKey))
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(createIndex("user_name_idx", MariaDbUsers) { on(MariaDbUsers.name) })
        }
    }

    @Test
    fun escapesBackticksWithoutPromisingAnsiQuotesAcrossSqlModes() {
        assertEquals("`select`", MariaDbDialect.quoteIdentifier("select"))
        assertEquals("`a``b`", MariaDbDialect.quoteIdentifier("a`b"))
    }

    @Test
    fun keepsNativeUuidMappingWhenCompatibleExtensionsAreConfigured() {
        val configured = MariaDbDialect.configured(listOf(MySqlStandardTypes))

        assertEquals(
            MariaDbDialect.render(createTable(MariaDbUsers, ifNotExists = true)).sql,
            configured.render(createTable(MariaDbUsers, ifNotExists = true)).sql,
        )
        val registry = MariaDbTypeRegistry()
        assertEquals(MariaDbType.UUID, registry.typeOf(MariaDbUsers.uuid.storageType))
        assertEquals("CHAR(36)", registry.sqlNameOf(MariaDbUsers.legacyUuid))
        assertEquals("BINARY(16)", registry.sqlNameOf(MariaDbUsers.binaryUuid))
        assertEquals(MariaDbType.DATETIME, registry.typeOf(MariaDbUsers.instantValue.storageType))
    }

    @Test
    fun mapsOffsetAndIanaTimeZoneToTextThroughTheCompatibleDatetimeExtension() {
        val configured = MariaDbDialect.configured(listOf(MySqlKotlinxDateTime))

        assertEquals(
            "CREATE TABLE `time_zone_values` (`offset_value` TEXT NOT NULL, `time_zone_value` TEXT NOT NULL)",
            configured.render(createTable(MariaDbTimeZoneValues)).sql,
        )
        val registry = MariaDbTypeRegistry(listOf(MySqlKotlinxDateTime))
        assertEquals(MariaDbType.TEXT, registry.typeOf(MariaDbTimeZoneValues.offsetValue.storageType))
        assertEquals(MariaDbType.TEXT, registry.typeOf(MariaDbTimeZoneValues.timeZoneValue.storageType))
    }

    @Test
    fun mapsSharedPhysicalLocalTemporalColumnsExplicitly() {
        assertEquals(
            "CREATE TABLE `mariadb_physical_temporal_values` (`date_value` DATE NOT NULL, " +
                "`time_value` TIME(6) NOT NULL, `date_time_value` DATETIME(6) NOT NULL)",
            MariaDbDialect.render(createTable(MariaDbPhysicalTemporalValues)).sql,
        )
    }

    @Test
    fun mapsEverySupportedCoreTypeIncludingDuration() {
        val registry = MariaDbTypeRegistry()

        assertEquals(MariaDbType.TEXT, registry.typeOf(MariaDbCoreTypes.stringValue.storageType))
        assertEquals(MariaDbType.BOOLEAN, registry.typeOf(MariaDbCoreTypes.booleanValue.storageType))
        assertEquals(MariaDbType.TINYINT, registry.typeOf(MariaDbCoreTypes.byteValue.storageType))
        assertEquals(MariaDbType.TINYINT_UNSIGNED, registry.typeOf(MariaDbCoreTypes.ubyteValue.storageType))
        assertEquals(MariaDbType.SMALLINT, registry.typeOf(MariaDbCoreTypes.shortValue.storageType))
        assertEquals(MariaDbType.SMALLINT_UNSIGNED, registry.typeOf(MariaDbCoreTypes.ushortValue.storageType))
        assertEquals(MariaDbType.INTEGER, registry.typeOf(MariaDbCoreTypes.intValue.storageType))
        assertEquals(MariaDbType.INTEGER_UNSIGNED, registry.typeOf(MariaDbCoreTypes.uintValue.storageType))
        assertEquals(MariaDbType.BIGINT, registry.typeOf(MariaDbCoreTypes.longValue.storageType))
        assertEquals(MariaDbType.BIGINT_UNSIGNED, registry.typeOf(MariaDbCoreTypes.ulongValue.storageType))
        assertEquals(MariaDbType.FLOAT, registry.typeOf(MariaDbCoreTypes.floatValue.storageType))
        assertEquals(MariaDbType.DOUBLE, registry.typeOf(MariaDbCoreTypes.doubleValue.storageType))
        assertEquals(MariaDbType.BLOB, registry.typeOf(MariaDbCoreTypes.bytesValue.storageType))
        assertEquals(MariaDbType.UUID, registry.typeOf(MariaDbCoreTypes.uuidValue.storageType))
        assertEquals(MariaDbType.DATETIME, registry.typeOf(MariaDbCoreTypes.instantValue.storageType))
        assertEquals(MariaDbType.TEXT, registry.typeOf(MariaDbCoreTypes.durationValue.storageType))
    }

    @Test
    fun rendersMariaDbSpecificAndCompatiblePhysicalTypes() {
        assertEquals(
            "CREATE TABLE `mariadb_extended_values` (" +
                "`native_uuid` UUID NOT NULL, `network_v4` INET4 NOT NULL, `network_v6` INET6 NOT NULL, " +
                "`metadata` JSON NOT NULL, `document` XMLTYPE NOT NULL, `embedding` VECTOR(3) NOT NULL, " +
                "`shared_integer` MEDIUMINT UNSIGNED NOT NULL, `amount` DECIMAL(20,8) NOT NULL)",
            MariaDbDialect.render(createTable(MariaDbExtendedValues)).sql,
        )
    }

    @Test
    fun keepsMySqlNativeJsonOutOfMariaDbDdlAndRejectsSpecialIndexes() {
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(createTable(MySqlJsonValues))
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(
                createIndex("json_index", MariaDbExtendedValues) { on(MariaDbExtendedValues.metadata) },
            )
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(
                createIndex("vector_index", MariaDbExtendedValues) { on(MariaDbExtendedValues.embedding) },
            )
        }
    }

    @Test
    fun rejectsGenericFunctionalPartialAndIncludeIndexes() {
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(
                createIndex("user_name_lower_idx", MariaDbUsers) { on(lower(MariaDbUsers.name)) },
            )
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(
                createIndex("active_users_idx", MariaDbUsers) {
                    on(MariaDbUsers.active)
                    where(MariaDbUsers.active eq true)
                },
            )
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(
                createIndex("covering_users_idx", MariaDbUsers) {
                    on(MariaDbUsers.id)
                    include(MariaDbUsers.name)
                },
            )
        }
    }

    @Test
    fun keepsMariaDbSerialAsACompatibleDialectPhysicalType() {
        assertEquals(
            "CREATE TABLE `mariadb_serial_values` (`id` SERIAL NOT NULL PRIMARY KEY)",
            MariaDbDialect.render(createTable(MariaDbSerialValues)).sql,
        )
    }

    @Test
    fun rendersTableAndColumnCommentsInsideCreateTable() {
        assertEquals(
            "CREATE TABLE `commented_users` (`id` BIGINT NOT NULL PRIMARY KEY COMMENT 'User identifier', " +
                "`email` TEXT NOT NULL COMMENT 'Primary user''s email\nユーザーの主要メールアドレス', " +
                "`display_name` TEXT NOT NULL COMMENT '''; DROP TABLE users; --') " +
                "COMMENT = 'Application users'",
            MariaDbDialect.render(createTable(MariaDbCommentedUsers)).sql,
        )
    }

    @Test
    fun rendersIndexAndUniqueConstraintCommentsInsideMariaDbDdl() {
        assertEquals(
            "CREATE TABLE `commented_constraints` (`email` VARCHAR(40) NOT NULL, " +
                "CONSTRAINT `uq_commented_constraints_email` UNIQUE (`email`) COMMENT 'Email must be unique')",
            MariaDbDialect.render(createTable(MariaDbCommentedConstraints)).sql,
        )
        assertEquals(
            "CREATE INDEX `commented_email_idx` ON `commented_constraints` (`email`) COMMENT 'Email lookup'",
            MariaDbDialect.render(
                createIndex("commented_email_idx", MariaDbCommentedConstraints, comment = "Email lookup") {
                    on(MariaDbCommentedConstraints.email)
                },
            ).sql,
        )
    }

    @Test
    fun rendersAutoIncrementAndMariaDbPersistentGeneratedColumns() {
        assertEquals(
            "CREATE TABLE `generated_orders` (" +
                "`id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, `price` INT NOT NULL, `quantity` INT NOT NULL, " +
                "`virtual_total` INT GENERATED ALWAYS AS (`price` * `quantity`) VIRTUAL, " +
                "`stored_total` INT GENERATED ALWAYS AS (`price` * `quantity`) PERSISTENT)",
            MariaDbDialect.render(createTable(MariaDbGeneratedOrders)).sql,
        )
        assertEquals(
            "ALTER TABLE `generated_orders` ADD COLUMN `stored_total` INT " +
                "GENERATED ALWAYS AS (`price` * `quantity`) PERSISTENT",
            MariaDbDialect.render(
                alterTable(MariaDbGeneratedOrders) { addColumn(MariaDbGeneratedOrders.storedTotal) },
            ).sql,
        )
    }

    @Test
    fun rendersIdentityStartAsATableOptionAndRejectsUnsupportedIncrementOptions() {
        assertEquals(
            "CREATE TABLE `identity_default` (`id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY)",
            MariaDbDialect.render(createTable(mariaDbIdentityTable("identity_default"))).sql,
        )
        assertEquals(
            "CREATE TABLE `identity_start` (`id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY) AUTO_INCREMENT = 1000",
            MariaDbDialect.render(createTable(mariaDbIdentityTable("identity_start", startWith = 1000L))).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(createTable(mariaDbIdentityTable("identity_increment", incrementBy = 10L)))
        }
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.render(
                createTable(mariaDbIdentityTable("identity_start_increment", startWith = 1000L, incrementBy = 10L)),
            )
        }
    }
}

private fun mariaDbIdentityTable(name: String, startWith: Long? = null, incrementBy: Long? = null): Table =
    object : Table(name) {
        val id = long("id").identity(startWith = startWith, incrementBy = incrementBy).primaryKey()
    }

private object MariaDbUsers : Table("user", schemaName = "app") {
    val id = long("id").primaryKey()
    val uuid = uuid("uuid")
    val legacyUuid = charUuid("legacy_uuid")
    val binaryUuid = binaryUuid("binary_uuid")
    val name = string("na`me")
    val active = boolean("active")
    val signed = long("signed")
    val unsigned = ulong("unsigned")
    val payload = bytes("payload")
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val nickname = string("nickname").nullable()
}

private object MariaDbTextKey : Table("text_key") {
    val value = string("value").unique()
}

private object MariaDbTimeZoneValues : Table("time_zone_values") {
    val offsetValue = utcOffset("offset_value")
    val timeZoneValue = timeZone("time_zone_value")
}

private object MariaDbPhysicalTemporalValues : Table("mariadb_physical_temporal_values") {
    val dateValue = column("date_value", MariaDbTypes.date(MySqlCompatibleKotlinxDateTimeMappings.localDate))
    val timeValue = column("time_value", MariaDbTypes.time(6, MySqlCompatibleKotlinxDateTimeMappings.localTime(6)))
    val dateTimeValue = column(
        "date_time_value",
        MariaDbTypes.dateTime(6, MySqlCompatibleKotlinxDateTimeMappings.localDateTime(6)),
    )
}

private object MariaDbCoreTypes : Table("core_types") {
    val stringValue = string("string_value")
    val booleanValue = boolean("boolean_value")
    val byteValue = byte("byte_value")
    val ubyteValue = ubyte("ubyte_value")
    val shortValue = short("short_value")
    val ushortValue = ushort("ushort_value")
    val intValue = int("int_value")
    val uintValue = uint("uint_value")
    val longValue = long("long_value")
    val ulongValue = ulong("ulong_value")
    val floatValue = float("float_value")
    val doubleValue = double("double_value")
    val bytesValue = bytes("bytes_value")
    val uuidValue = uuid("uuid_value")
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
}

private object MariaDbExtendedValues : Table("mariadb_extended_values") {
    val nativeUuid = column("native_uuid", MariaDbTypes.uuid)
    val networkV4 = column("network_v4", MariaDbTypes.inet4)
    val networkV6 = column("network_v6", MariaDbTypes.inet6)
    val metadata = column("metadata", MariaDbTypes.json)
    val document = column("document", MariaDbTypes.xmlType)
    val embedding = column("embedding", MariaDbTypes.vector(3))
    val sharedInteger = column("shared_integer", MariaDbTypes.mediumIntUnsigned)
    val amount = column("amount", MariaDbTypes.decimal(20, 8))
}

private object MySqlJsonValues : Table("mysql_json_values") {
    val metadata = column("metadata", MySqlTypes.json)
}

private object MariaDbSerialValues : Table("mariadb_serial_values") {
    val id = column("id", MariaDbTypes.serial).primaryKey()
}

private object MariaDbCommentedUsers : Table("commented_users", comment = "Application users") {
    val id = long("id").primaryKey().comment("User identifier")
    val email = string("email").comment("Primary user's email\nユーザーの主要メールアドレス")
    val displayName = string("display_name").comment("'; DROP TABLE users; --")
}

private object MariaDbCommentedConstraints : Table("commented_constraints") {
    val email = column("email", MySqlTypes.varchar(40))

    init {
        unique(
            email,
            name = "uq_commented_constraints_email",
            comment = "Email must be unique",
        )
    }
}

private object MariaDbGeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity()
    val price = column("price", MariaDbTypes.integer)
    val quantity = column("quantity", MariaDbTypes.integer)
    val virtualTotal = column("virtual_total", MariaDbTypes.integer)
        .generated(price * quantity, GeneratedColumnStorage.Virtual)
    val storedTotal = column("stored_total", MariaDbTypes.integer)
        .generated(price * quantity, GeneratedColumnStorage.Stored)
}

package dev.quwac.kagiroi.mysql.node

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.kotlinx.datetime.localDate
import dev.quwac.kagiroi.kotlinx.datetime.localDateTime
import dev.quwac.kagiroi.kotlinx.datetime.localTime
import dev.quwac.kagiroi.kotlinx.datetime.timeZone
import dev.quwac.kagiroi.kotlinx.datetime.utcOffset
import dev.quwac.kagiroi.mariadb.MariaDbDialect
import dev.quwac.kagiroi.mariadb.MariaDbTypes
import dev.quwac.kagiroi.mariadb.binaryUuid
import dev.quwac.kagiroi.mariadb.charUuid
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlCompatibleKotlinxDateTimeMappings
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlKotlinxDateTime
import dev.quwac.kagiroi.mysql.quwac.bignumber.MySqlQuwacBigNumber
import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Opt-in direct MariaDB contract through the shared mysql2 protocol adapter. */
class NodeMariaDbCompatibilityIntegrationTest {
    @Test
    fun mariaDbTypedDdlRejectsBigNumberWithoutPrecisionAndScale() {
        assertFailsWith<UnsupportedDdlOperation> {
            MariaDbDialect.configured(listOf(MySqlQuwacBigNumber)).render(createTable(MariaDbBigNumbers))
        }
    }

    @Test
    fun distributionWindowFunctions() = runTest {
        val url = mariaDbTestUrl() ?: return@runTest
        verifyDistributionWindowSql(configuredMariaDb(url))
    }

    @Test
    fun mariaDbContractAndMariaSpecificSql() = runTest {
        val url = mariaDbTestUrl() ?: return@runTest
        nodeMySqlDriverContract(configuredMariaDb(url))
        nodeMySqlPoolContract(url)
        verifyMariaDbSpecificSql(configuredMariaDb(url))
        verifyMariaDbStandardTypeMappings(configuredMariaDb(url))
        verifyMariaDbUuidStorage(configuredMariaDb(url))
        verifyMariaDbPhysicalTypes(configuredMariaDb(url))
        verifyMySqlCompatibleIndexAndUniqueComments(configuredMariaDb(url))
        verifyMariaDbDuplicateKeyUpdate(configuredMariaDb(url))
    }
}

internal suspend fun verifyMySqlCompatibleIndexAndUniqueComments(database: NodeMySqlDatabase) {
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_commented_constraints")
        database.execute(createTable(MySqlCompatibleCommentedConstraints))
        database.execute(
            createIndex("idx_kagiroi_commented_email", MySqlCompatibleCommentedConstraints, comment = "Email lookup") {
                on(MySqlCompatibleCommentedConstraints.email)
            },
        )

        assertEquals(
            listOf("Email lookup", "Email must be unique"),
            database.queryRaw(
                "SELECT index_comment AS kagiroi_comment FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY index_name",
                listOf(MySqlCompatibleCommentedConstraints.tableName),
            ).toList().map { row -> row.string("kagiroi_comment") },
        )
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_commented_constraints")
        database.close()
    }
}

private fun configuredMariaDb(url: String): NodeMySqlDatabase = NodeMySqlDatabase(url) {
    pool {
        maxConnections = 4
        acquisitionTimeout = 5.seconds
    }
    install(MySqlKotlinxDateTime)
    install(MySqlQuwacBigNumber)
    dialect(MariaDbDialect.configured(listOf(MySqlKotlinxDateTime, MySqlQuwacBigNumber)))
}

private suspend fun verifyMariaDbSpecificSql(database: NodeMySqlDatabase) {
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_returning")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_json")
        database.executeRaw("DROP SEQUENCE IF EXISTS kagiroi_mariadb_sequence")
        database.executeRaw("CREATE TABLE kagiroi_mariadb_json (payload JSON NOT NULL)")
        database.executeRaw("INSERT INTO kagiroi_mariadb_json (payload) VALUES (?)", listOf("{\"id\":1}"))
        assertEquals(
            "{\"id\":1}",
            database.queryRaw("SELECT payload FROM kagiroi_mariadb_json").single().string("payload"),
        )
        val invalidJson = assertFailsWith<ConstraintViolation> {
            database.executeRaw("INSERT INTO kagiroi_mariadb_json (payload) VALUES (?)", listOf("not-json"))
        }
        assertEquals(ConstraintKind.CHECK, invalidJson.kind)
        assertEquals("23000", invalidJson.metadata.sqlState)
        assertEquals("4025", invalidJson.metadata.attributes["errno"])

        database.executeRaw("CREATE SEQUENCE kagiroi_mariadb_sequence START WITH 41")
        assertEquals(
            41L,
            database.queryRaw("SELECT NEXT VALUE FOR kagiroi_mariadb_sequence AS value").single().long("value"),
        )

        database.executeRaw("CREATE TABLE kagiroi_mariadb_returning (id INT PRIMARY KEY, name VARCHAR(255))")
        assertEquals(
            7L,
            database.queryRaw(
                "INSERT INTO kagiroi_mariadb_returning (id, name) VALUES (?, ?) RETURNING id",
                listOf(7, "Ada"),
            ).single().long("id"),
        )
        assertEquals(
            7L,
            database.queryRaw("DELETE FROM kagiroi_mariadb_returning WHERE id = ? RETURNING id", listOf(7))
                .single().long("id"),
        )
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_returning")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_json")
        database.executeRaw("DROP SEQUENCE IF EXISTS kagiroi_mariadb_sequence")
        database.close()
    }
}

private suspend fun verifyMariaDbUuidStorage(database: NodeMySqlDatabase) {
    val nativeId = Uuid.parse("123e4567-e89b-12d3-a456-426614174010")
    val charId = Uuid.parse("123e4567-e89b-12d3-a456-426614174011")
    val binaryId = Uuid.parse("123e4567-e89b-12d3-a456-426614174012")
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_uuid_storage")
        database.execute(createTable(MariaDbUuidStorage))
        database.insertInto(MariaDbUuidStorage).values {
            set(MariaDbUuidStorage.nativeId, nativeId)
            set(MariaDbUuidStorage.charId, charId)
            set(MariaDbUuidStorage.binaryId, binaryId)
        }.execute()

        val row = database.select(
            MariaDbUuidStorage.nativeId,
            MariaDbUuidStorage.charId,
            MariaDbUuidStorage.binaryId,
        ).from(MariaDbUuidStorage).one()
        assertEquals(nativeId, row[MariaDbUuidStorage.nativeId])
        assertEquals(charId, row[MariaDbUuidStorage.charId])
        assertEquals(binaryId, row[MariaDbUuidStorage.binaryId])

        val types = database.queryRaw(
            "SELECT column_name AS kagiroi_column_name, data_type AS kagiroi_data_type " +
                "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
            listOf(MariaDbUuidStorage.tableName),
        ).toList().associate { result ->
            requireNotNull(result.string("kagiroi_column_name")) to
                requireNotNull(result.string("kagiroi_data_type")).lowercase()
        }
        assertEquals("uuid", types["native_id"])
        assertEquals("char", types["char_id"])
        assertEquals("binary", types["binary_id"])
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_uuid_storage")
        database.close()
    }
}

private suspend fun verifyMariaDbStandardTypeMappings(database: NodeMySqlDatabase) {
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_standard_types")
        database.execute(createTable(MariaDbStandardTypes))
        insertMariaDbStandardTypes(database)
        assertMariaDbStandardTypeValues(database)
        assertMariaDbStandardTypePhysicalMappings(database)
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_standard_types")
        database.close()
    }
}

private suspend fun verifyMariaDbPhysicalTypes(database: NodeMySqlDatabase) {
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_physical_types")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_inet4_types")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_vector_types")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_xml_types")

        assertMariaDbBasePhysicalTypes(database)

        val version = requireNotNull(
            database.queryRaw("SELECT VERSION() AS kagiroi_version").single().string("kagiroi_version"),
        )
        verifyMariaDbVersionSpecificPhysicalTypes(database, version)
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_xml_types")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_vector_types")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_inet4_types")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_physical_types")
        database.close()
    }
}

private suspend fun assertMariaDbBasePhysicalTypes(database: NodeMySqlDatabase) {
    database.execute(createTable(MariaDbPhysicalTypes))
    database.insertInto(MariaDbPhysicalTypes).values {
        set(MariaDbPhysicalTypes.networkV6, "2001:db8::1")
        set(MariaDbPhysicalTypes.metadata, "{\"id\":1}")
        set(MariaDbPhysicalTypes.amount, "123456789012.123456789012345678")
        set(MariaDbPhysicalTypes.exactInteger, MariaDbPhysicalValues.exactInteger)
        set(MariaDbPhysicalTypes.exactAmount, MariaDbPhysicalValues.exactAmount)
        set(MariaDbPhysicalTypes.point, MariaDbPhysicalValues.point)
        set(MariaDbPhysicalTypes.flags, MariaDbPhysicalValues.flags)
        set(MariaDbPhysicalTypes.localDate, MariaDbPhysicalValues.localDate)
        set(MariaDbPhysicalTypes.localTime, MariaDbPhysicalValues.localTime)
        set(MariaDbPhysicalTypes.localDateTime, MariaDbPhysicalValues.localDateTime)
    }.execute()
    val row = database.select(
        MariaDbPhysicalTypes.networkV6,
        MariaDbPhysicalTypes.metadata,
        MariaDbPhysicalTypes.amount,
        MariaDbPhysicalTypes.exactInteger,
        MariaDbPhysicalTypes.exactAmount,
        MariaDbPhysicalTypes.point,
        MariaDbPhysicalTypes.flags,
        MariaDbPhysicalTypes.localDate,
        MariaDbPhysicalTypes.localTime,
        MariaDbPhysicalTypes.localDateTime,
    ).from(MariaDbPhysicalTypes).one()
    assertEquals("2001:db8::1", row[MariaDbPhysicalTypes.networkV6])
    assertEquals("{\"id\":1}", row[MariaDbPhysicalTypes.metadata])
    assertEquals("123456789012.123456789012345678", row[MariaDbPhysicalTypes.amount])
    assertEquals(MariaDbPhysicalValues.exactInteger, row[MariaDbPhysicalTypes.exactInteger])
    assertEquals(MariaDbPhysicalValues.exactAmount, row[MariaDbPhysicalTypes.exactAmount])
    assertContentEquals(MariaDbPhysicalValues.point, row[MariaDbPhysicalTypes.point])
    assertContentEquals(MariaDbPhysicalValues.flags, row[MariaDbPhysicalTypes.flags])
    assertEquals(MariaDbPhysicalValues.localDate, row[MariaDbPhysicalTypes.localDate])
    assertEquals(MariaDbPhysicalValues.localTime, row[MariaDbPhysicalTypes.localTime])
    assertEquals(MariaDbPhysicalValues.localDateTime, row[MariaDbPhysicalTypes.localDateTime])
}

private suspend fun verifyMariaDbVersionSpecificPhysicalTypes(database: NodeMySqlDatabase, version: String) {
    if (version.isAtLeastMariaDb(10, 10)) verifyMariaDbInet4Type(database)
    if (version.isAtLeastMariaDb(11, 7)) verifyMariaDbVectorType(database)
    if (version.isAtLeastMariaDb(12, 3)) verifyMariaDbXmlType(database)
}

private suspend fun verifyMariaDbInet4Type(database: NodeMySqlDatabase) {
    database.execute(createTable(MariaDbInet4Types))
    database.insertInto(MariaDbInet4Types).values { set(MariaDbInet4Types.networkV4, "192.0.2.1") }.execute()
    assertEquals(
        "192.0.2.1",
        database.select(MariaDbInet4Types.networkV4).from(MariaDbInet4Types).one()[MariaDbInet4Types.networkV4],
    )
}

private suspend fun verifyMariaDbVectorType(database: NodeMySqlDatabase) {
    database.execute(createTable(MariaDbVectorTypes))
    database.insertInto(MariaDbVectorTypes).values {
        set(MariaDbVectorTypes.embedding, MariaDbPhysicalValues.vector)
    }.execute()
    assertContentEquals(
        MariaDbPhysicalValues.vector,
        database.select(MariaDbVectorTypes.embedding).from(MariaDbVectorTypes).one()[MariaDbVectorTypes.embedding],
    )
}

private suspend fun verifyMariaDbXmlType(database: NodeMySqlDatabase) {
    database.execute(createTable(MariaDbXmlTypes))
    database.insertInto(MariaDbXmlTypes).values { set(MariaDbXmlTypes.document, "<root/>") }.execute()
    assertEquals(
        "<root/>",
        database.select(MariaDbXmlTypes.document).from(MariaDbXmlTypes).one()[MariaDbXmlTypes.document],
    )
}

private suspend fun insertMariaDbStandardTypes(database: NodeMySqlDatabase) {
    database.insertInto(MariaDbStandardTypes).values {
        set(MariaDbStandardTypes.textValue, "Ada")
        set(MariaDbStandardTypes.booleanValue, true)
        set(MariaDbStandardTypes.byteValue, Byte.MIN_VALUE)
        set(MariaDbStandardTypes.shortValue, Short.MIN_VALUE)
        set(MariaDbStandardTypes.intValue, Int.MIN_VALUE)
        set(MariaDbStandardTypes.longValue, Long.MIN_VALUE)
        set(MariaDbStandardTypes.ubyteValue, UByte.MAX_VALUE)
        set(MariaDbStandardTypes.ushortValue, UShort.MAX_VALUE)
        set(MariaDbStandardTypes.uintValue, UInt.MAX_VALUE)
        set(MariaDbStandardTypes.ulongValue, ULong.MAX_VALUE)
        set(MariaDbStandardTypes.floatValue, 1.25f)
        set(MariaDbStandardTypes.doubleValue, 1.234567890123)
        set(MariaDbStandardTypes.bytesValue, MariaDbStandardValues.bytes)
        set(MariaDbStandardTypes.uuidValue, MariaDbStandardValues.id)
        set(MariaDbStandardTypes.instantValue, MariaDbStandardValues.instant)
        set(MariaDbStandardTypes.durationValue, MariaDbStandardValues.duration)
        set(MariaDbStandardTypes.dateValue, MariaDbStandardValues.date)
        set(MariaDbStandardTypes.timeValue, MariaDbStandardValues.time)
        set(MariaDbStandardTypes.dateTimeValue, MariaDbStandardValues.dateTime)
        set(MariaDbStandardTypes.offsetValue, MariaDbStandardValues.offset)
        set(MariaDbStandardTypes.timeZoneValue, MariaDbStandardValues.timeZone)
        set(MariaDbStandardTypes.nullableValue, null)
    }.execute()
}

private suspend fun assertMariaDbStandardTypeValues(database: NodeMySqlDatabase) {
    val row = database.select(
        MariaDbStandardTypes.textValue,
        MariaDbStandardTypes.booleanValue,
        MariaDbStandardTypes.byteValue,
        MariaDbStandardTypes.shortValue,
        MariaDbStandardTypes.intValue,
        MariaDbStandardTypes.longValue,
        MariaDbStandardTypes.ubyteValue,
        MariaDbStandardTypes.ushortValue,
        MariaDbStandardTypes.uintValue,
        MariaDbStandardTypes.ulongValue,
        MariaDbStandardTypes.floatValue,
        MariaDbStandardTypes.doubleValue,
        MariaDbStandardTypes.bytesValue,
        MariaDbStandardTypes.uuidValue,
        MariaDbStandardTypes.instantValue,
        MariaDbStandardTypes.durationValue,
        MariaDbStandardTypes.dateValue,
        MariaDbStandardTypes.timeValue,
        MariaDbStandardTypes.dateTimeValue,
        MariaDbStandardTypes.offsetValue,
        MariaDbStandardTypes.timeZoneValue,
        MariaDbStandardTypes.nullableValue,
    ).from(MariaDbStandardTypes).one()
    assertEquals("Ada", row[MariaDbStandardTypes.textValue])
    assertEquals(true, row[MariaDbStandardTypes.booleanValue])
    assertEquals(Byte.MIN_VALUE, row[MariaDbStandardTypes.byteValue])
    assertEquals(Short.MIN_VALUE, row[MariaDbStandardTypes.shortValue])
    assertEquals(Int.MIN_VALUE, row[MariaDbStandardTypes.intValue])
    assertEquals(Long.MIN_VALUE, row[MariaDbStandardTypes.longValue])
    assertEquals(UByte.MAX_VALUE, row[MariaDbStandardTypes.ubyteValue])
    assertEquals(UShort.MAX_VALUE, row[MariaDbStandardTypes.ushortValue])
    assertEquals(UInt.MAX_VALUE, row[MariaDbStandardTypes.uintValue])
    assertEquals(ULong.MAX_VALUE, row[MariaDbStandardTypes.ulongValue])
    assertEquals(1.25f, row[MariaDbStandardTypes.floatValue])
    assertEquals(1.234567890123, row[MariaDbStandardTypes.doubleValue])
    assertContentEquals(MariaDbStandardValues.bytes, row[MariaDbStandardTypes.bytesValue])
    assertEquals(MariaDbStandardValues.id, row[MariaDbStandardTypes.uuidValue])
    assertEquals(MariaDbStandardValues.instant, row[MariaDbStandardTypes.instantValue])
    assertEquals(MariaDbStandardValues.duration, row[MariaDbStandardTypes.durationValue])
    assertEquals(MariaDbStandardValues.date, row[MariaDbStandardTypes.dateValue])
    assertEquals(MariaDbStandardValues.time, row[MariaDbStandardTypes.timeValue])
    assertEquals(MariaDbStandardValues.dateTime, row[MariaDbStandardTypes.dateTimeValue])
    assertEquals(MariaDbStandardValues.offset, row[MariaDbStandardTypes.offsetValue])
    assertEquals(MariaDbStandardValues.timeZone, row[MariaDbStandardTypes.timeZoneValue])
    assertNull(row[MariaDbStandardTypes.nullableValue])
}

private suspend fun assertMariaDbStandardTypePhysicalMappings(database: NodeMySqlDatabase) {
    val types = database.queryRaw(
        "SELECT column_name AS kagiroi_column_name, data_type AS kagiroi_data_type, " +
            "column_type AS kagiroi_column_type, is_nullable AS kagiroi_is_nullable " +
            "FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
        listOf(MariaDbStandardTypes.tableName),
    ).toList().associate { result ->
        requireNotNull(result.string("kagiroi_column_name")) to MariaDbColumnType(
            requireNotNull(result.string("kagiroi_data_type")).lowercase(),
            requireNotNull(result.string("kagiroi_column_type")).lowercase(),
            requireNotNull(result.string("kagiroi_is_nullable")),
        )
    }
    assertEquals("text", types["text_value"]?.dataType)
    assertEquals("tinyint", types["boolean_value"]?.dataType)
    assertEquals("tinyint", types["byte_value"]?.dataType)
    assertEquals("smallint", types["short_value"]?.dataType)
    assertEquals("int", types["int_value"]?.dataType)
    assertEquals("bigint", types["long_value"]?.dataType)
    assertEquals("tinyint", types["ubyte_value"]?.dataType)
    assertEquals("smallint", types["ushort_value"]?.dataType)
    assertEquals("int", types["uint_value"]?.dataType)
    assertEquals("bigint", types["ulong_value"]?.dataType)
    assertEquals("float", types["float_value"]?.dataType)
    assertEquals("double", types["double_value"]?.dataType)
    assertEquals("blob", types["bytes_value"]?.dataType)
    assertEquals("uuid", types["uuid_value"]?.dataType)
    assertEquals("datetime", types["instant_value"]?.dataType)
    assertEquals("text", types["duration_value"]?.dataType)
    assertEquals("date", types["date_value"]?.dataType)
    assertEquals("time", types["time_value"]?.dataType)
    assertEquals("datetime", types["date_time_value"]?.dataType)
    assertEquals("text", types["offset_value"]?.dataType)
    assertEquals("text", types["time_zone_value"]?.dataType)
    assertEquals("YES", types["nullable_value"]?.nullable)
    listOf("ubyte_value", "ushort_value", "uint_value", "ulong_value").forEach { name ->
        check("unsigned" in requireNotNull(types[name]).columnType)
    }
}

private data class MariaDbColumnType(val dataType: String, val columnType: String, val nullable: String)

private fun String.isAtLeastMariaDb(minimumMajor: Int, minimumMinor: Int): Boolean {
    val version = substringBefore('-').split('.')
    val major = version.getOrNull(0)?.toIntOrNull() ?: return false
    val minor = version.getOrNull(1)?.toIntOrNull() ?: return false
    return major > minimumMajor || (major == minimumMajor && minor >= minimumMinor)
}

private object MariaDbStandardValues {
    val bytes = byteArrayOf(0, 1, -1)
    val id = Uuid.parse("123e4567-e89b-12d3-a456-426614174020")
    val instant = Instant.parse("2040-01-02T03:04:05.123456Z")
    val duration = Duration.parseIsoString("-PT900H0.000000001S")
    val date = LocalDate.parse("2040-01-02")
    val time = LocalTime.parse("03:04:05.123456")
    val dateTime = LocalDateTime.parse("2040-01-02T03:04:05.123456")
    val offset = UtcOffset.parse("+09:00")
    val timeZone = TimeZone.of("Asia/Tokyo")
}

private object MariaDbPhysicalValues {
    val exactInteger: BigInteger = BigInteger.parse("99999999999999999999999999999999999999999999999999999999999999999")
    val exactAmount: BigDecimal = BigDecimal.parse("123456789012.12345678901234567890123456789012345678")
    val point = byteArrayOf(
        0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, -16, 63, 0, 0, 0, 0, 0, 0, 0, 64,
    )
    val flags = byteArrayOf(0x2a)
    val localDate = LocalDate.parse("2040-01-02")
    val localTime = LocalTime.parse("03:04:05.123456")
    val localDateTime = LocalDateTime.parse("2040-01-02T03:04:05.123456")
    val vector = byteArrayOf(0, 0, -128, 63, 0, 0, 0, 64, 0, 0, 64, 64)
}

private object MariaDbUuidStorage : Table("kagiroi_mariadb_uuid_storage") {
    val nativeId = uuid("native_id").primaryKey()
    val charId = charUuid("char_id").unique()
    val binaryId = binaryUuid("binary_id").unique()
}

private object MariaDbStandardTypes : Table("kagiroi_mariadb_standard_types") {
    val textValue = string("text_value")
    val booleanValue = boolean("boolean_value")
    val byteValue = byte("byte_value")
    val shortValue = short("short_value")
    val intValue = int("int_value")
    val longValue = long("long_value")
    val ubyteValue = ubyte("ubyte_value")
    val ushortValue = ushort("ushort_value")
    val uintValue = uint("uint_value")
    val ulongValue = ulong("ulong_value")
    val floatValue = float("float_value")
    val doubleValue = double("double_value")
    val bytesValue = bytes("bytes_value")
    val uuidValue = uuid("uuid_value")
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val dateValue = localDate("date_value")
    val timeValue = localTime("time_value")
    val dateTimeValue = localDateTime("date_time_value")
    val offsetValue = utcOffset("offset_value")
    val timeZoneValue = timeZone("time_zone_value")
    val nullableValue = string("nullable_value").nullable()
}

private object MariaDbPhysicalTypes : Table("kagiroi_mariadb_physical_types") {
    val networkV6 = column("network_v6", MariaDbTypes.inet6)
    val metadata = column("metadata", MariaDbTypes.json)
    val amount = column("amount", MariaDbTypes.decimal(30, 18))
    val exactInteger = column("exact_integer", MariaDbTypes.decimal(65, 0, BigNumberTextMappings.integer))
    val exactAmount = column("exact_amount", MariaDbTypes.decimal(50, 38, BigNumberTextMappings.decimal))
    val point = column("point", MariaDbTypes.point)
    val flags = column("flags", MariaDbTypes.bit(8))
    val localDate = column("local_date", MariaDbTypes.date(MySqlCompatibleKotlinxDateTimeMappings.localDate))
    val localTime = column("local_time", MariaDbTypes.time(6, MySqlCompatibleKotlinxDateTimeMappings.localTime(6)))
    val localDateTime = column(
        "local_date_time",
        MariaDbTypes.dateTime(6, MySqlCompatibleKotlinxDateTimeMappings.localDateTime(6)),
    )
}

private object MariaDbInet4Types : Table("kagiroi_mariadb_inet4_types") {
    val networkV4 = column("network_v4", MariaDbTypes.inet4)
}

private object MariaDbVectorTypes : Table("kagiroi_mariadb_vector_types") {
    val embedding = column("embedding", MariaDbTypes.vector(3))
}

private object MariaDbXmlTypes : Table("kagiroi_mariadb_xml_types") {
    val document = column("document", MariaDbTypes.xmlType)
}

private object MariaDbBigNumbers : Table("kagiroi_mariadb_big_numbers") {
    val amount = bigDecimal("amount")
}

private object MySqlCompatibleCommentedConstraints : Table("kagiroi_commented_constraints") {
    val email = column("email", MySqlTypes.varchar(64))

    init {
        unique(email, name = "uq_kagiroi_commented_email", comment = "Email must be unique")
    }
}

internal expect fun mariaDbTestUrl(): String?

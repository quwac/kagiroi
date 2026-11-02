package dev.quwac.kagiroi.mysql.node

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.mysql.kotlinx.datetime.MySqlCompatibleKotlinxDateTimeMappings
import dev.quwac.kagiroi.quwac.bignumber.BigNumberTextMappings
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Direct mysql2 verification for native MySQL declarations that have no generic schema analogue. */
@Suppress("LongMethod") // The physical-type contract intentionally verifies one complete row lifecycle.
internal suspend fun nodeMySqlPhysicalTypeContract(database: NodeMySqlDatabase) {
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_node_mysql_physical_types")
        database.execute(createTable(MySqlPhysicalTypes))
        database.execute(
            createIndex("kagiroi_node_mysql_lower_code_idx", MySqlPhysicalTypes) {
                on(lower(MySqlPhysicalTypes.code).desc())
            },
        )
        assertEquals(
            1L,
            database.queryRaw(
                "SELECT COUNT(*) AS kagiroi_count FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                listOf(MySqlPhysicalTypes.tableName, "kagiroi_node_mysql_lower_code_idx"),
            ).toList().single().long("kagiroi_count"),
        )
        database.insertInto(MySqlPhysicalTypes).values {
            set(MySqlPhysicalTypes.code, "A-42")
            set(MySqlPhysicalTypes.unsignedMedium, MySqlPhysicalValues.MAXIMUM_UNSIGNED_MEDIUM)
            set(MySqlPhysicalTypes.amount, "123456789012.123456")
            set(MySqlPhysicalTypes.exactInteger, MySqlPhysicalValues.exactInteger)
            set(MySqlPhysicalTypes.exactAmount, MySqlPhysicalValues.exactAmount)
            set(MySqlPhysicalTypes.flags, MySqlPhysicalValues.flags)
            set(MySqlPhysicalTypes.metadata, "{\"id\":1}")
            set(MySqlPhysicalTypes.state, "draft")
            set(MySqlPhysicalTypes.labels, "new,priority")
            set(MySqlPhysicalTypes.charId, MySqlPhysicalValues.uuid)
            set(MySqlPhysicalTypes.binaryId, MySqlPhysicalValues.uuid)
            set(MySqlPhysicalTypes.point, MySqlPhysicalValues.point)
            set(MySqlPhysicalTypes.localDate, MySqlPhysicalValues.localDate)
            set(MySqlPhysicalTypes.localTime, MySqlPhysicalValues.localTime)
            set(MySqlPhysicalTypes.localDateTime, MySqlPhysicalValues.localDateTime)
        }.execute()

        val row = database.select(
            MySqlPhysicalTypes.code,
            MySqlPhysicalTypes.unsignedMedium,
            MySqlPhysicalTypes.amount,
            MySqlPhysicalTypes.exactInteger,
            MySqlPhysicalTypes.exactAmount,
            MySqlPhysicalTypes.flags,
            MySqlPhysicalTypes.metadata,
            MySqlPhysicalTypes.state,
            MySqlPhysicalTypes.labels,
            MySqlPhysicalTypes.charId,
            MySqlPhysicalTypes.binaryId,
            MySqlPhysicalTypes.point,
            MySqlPhysicalTypes.localDate,
            MySqlPhysicalTypes.localTime,
            MySqlPhysicalTypes.localDateTime,
        ).from(MySqlPhysicalTypes).one()
        assertEquals("A-42", row[MySqlPhysicalTypes.code])
        assertEquals(MySqlPhysicalValues.MAXIMUM_UNSIGNED_MEDIUM, row[MySqlPhysicalTypes.unsignedMedium])
        assertEquals("123456789012.123456", row[MySqlPhysicalTypes.amount])
        assertEquals(MySqlPhysicalValues.exactInteger, row[MySqlPhysicalTypes.exactInteger])
        assertEquals(MySqlPhysicalValues.exactAmount, row[MySqlPhysicalTypes.exactAmount])
        assertContentEquals(MySqlPhysicalValues.flags, row[MySqlPhysicalTypes.flags])
        assertEquals("{\"id\": 1}", row[MySqlPhysicalTypes.metadata])
        assertEquals("draft", row[MySqlPhysicalTypes.state])
        assertEquals("new,priority", row[MySqlPhysicalTypes.labels])
        assertEquals(MySqlPhysicalValues.uuid, row[MySqlPhysicalTypes.charId])
        assertEquals(MySqlPhysicalValues.uuid, row[MySqlPhysicalTypes.binaryId])
        assertContentEquals(MySqlPhysicalValues.point, row[MySqlPhysicalTypes.point])
        assertEquals(MySqlPhysicalValues.localDate, row[MySqlPhysicalTypes.localDate])
        assertEquals(MySqlPhysicalValues.localTime, row[MySqlPhysicalTypes.localTime])
        assertEquals(MySqlPhysicalValues.localDateTime, row[MySqlPhysicalTypes.localDateTime])
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_node_mysql_physical_types")
        database.close()
    }
}

private object MySqlPhysicalTypes : Table("kagiroi_node_mysql_physical_types") {
    val code = column("code", MySqlTypes.varchar(40))
    val unsignedMedium = column("unsigned_medium", MySqlTypes.mediumIntUnsigned)
    val amount = column("amount", MySqlTypes.decimal(30, 6))
    val exactInteger = column("exact_integer", MySqlTypes.decimal(30, 0, BigNumberTextMappings.integer))
    val exactAmount = column("exact_amount", MySqlTypes.decimal(30, 18, BigNumberTextMappings.decimal))
    val flags = column("flags", MySqlTypes.bit(8))
    val metadata = column("metadata", MySqlTypes.json)
    val state = column("state", MySqlTypes.enum("draft", "done"))
    val labels = column("labels", MySqlTypes.set("new", "priority"))
    val charId = column("char_id", MySqlTypes.charUuid)
    val binaryId = column("binary_id", MySqlTypes.binaryUuid)
    val point = column("point", MySqlTypes.point)
    val localDate = column("local_date", MySqlTypes.date(MySqlCompatibleKotlinxDateTimeMappings.localDate))
    val localTime = column("local_time", MySqlTypes.time(6, MySqlCompatibleKotlinxDateTimeMappings.localTime(6)))
    val localDateTime = column(
        "local_date_time",
        MySqlTypes.dateTime(6, MySqlCompatibleKotlinxDateTimeMappings.localDateTime(6)),
    )
}

private object MySqlPhysicalValues {
    const val MAXIMUM_UNSIGNED_MEDIUM: UInt = 16_777_215u
    val exactInteger: BigInteger = BigInteger.parse("999999999999999999999999999999")
    val exactAmount: BigDecimal = BigDecimal.parse("123456789012.123456789012345678")
    val flags: ByteArray = byteArrayOf(0x2a)
    val uuid: Uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174040")
    val localDate: LocalDate = LocalDate.parse("2040-01-02")
    val localTime: LocalTime = LocalTime.parse("03:04:05.123456")
    val localDateTime: LocalDateTime = LocalDateTime.parse("2040-01-02T03:04:05.123456")
    val point: ByteArray = byteArrayOf(
        0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, -16, 63, 0, 0, 0, 0, 0, 0, 0, 64,
    )
}

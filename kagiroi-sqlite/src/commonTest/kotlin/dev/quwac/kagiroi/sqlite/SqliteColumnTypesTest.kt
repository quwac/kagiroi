package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.columnMapping
import dev.quwac.kagiroi.sql.SqlTypeCodecRegistry
import dev.quwac.kagiroi.sql.createTable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteColumnTypesTest {
    @Test
    fun rendersExplicitStorageClassDeclarationsWithoutChangingScalarCodecs() {
        assertEquals(
            "CREATE TABLE \"physical_values\" (\"integer_value\" INTEGER NOT NULL, " +
                "\"real_value\" REAL NOT NULL, \"text_value\" TEXT NOT NULL, \"blob_value\" BLOB NOT NULL)",
            SqliteDialect.render(createTable(PhysicalValues)).sql,
        )

        val codec = SqliteValueCodec(SqlTypeCodecRegistry(emptyList()))
        assertEquals(SqlitePhysicalValue.Integer("42"), codec.binding(42L, PhysicalValues.integerValue))
        assertEquals(SqlitePhysicalValue.Real(1.5), codec.binding(1.5, PhysicalValues.realValue))
        assertEquals(SqlitePhysicalValue.Text("text"), codec.binding("text", PhysicalValues.textValue))
        val bytes = codec.binding(byteArrayOf(1, 2), PhysicalValues.blobValue) as SqlitePhysicalValue.Blob
        assertContentEquals(byteArrayOf(1, 2), bytes.value)
    }

    @Test
    fun exposesOnlyDeclarationsWithOneLosslessScalarContract() {
        assertEquals(
            listOf(
                SqliteDeclaredType.INTEGER,
                SqliteDeclaredType.REAL,
                SqliteDeclaredType.TEXT,
                SqliteDeclaredType.BLOB,
            ),
            SqliteDeclaredType.entries,
        )
    }

    @Test
    fun rendersNamedTypesWithoutAcceptingRawTypeSyntax() {
        assertEquals(
            "CREATE TABLE \"sqlite_named_values\" (\"label\" \"VARCHAR\"(40) NOT NULL, " +
                "\"quantity\" \"DECIMAL\"(20,6) NOT NULL)",
            SqliteDialect.render(createTable(SqliteNamedValues)).sql,
        )
        assertFailsWith<IllegalArgumentException> {
            SqliteTypes.namedText(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            SqliteTypes.namedText("DECIMAL", secondParameter = 6)
        }
    }
}

private object PhysicalValues : Table("physical_values") {
    val integerValue = column("integer_value", SqliteTypes.integer)
    val realValue = column("real_value", SqliteTypes.real)
    val textValue = column("text_value", SqliteTypes.text)
    val blobValue = column("blob_value", SqliteTypes.blob)
}

private object SqliteNamedValues : Table("sqlite_named_values") {
    val label = column("label", SqliteTypes.namedText("VARCHAR", firstParameter = 40))
    val quantity = column(
        "quantity",
        SqliteTypes.named(
            typeName = "DECIMAL",
            storageType = IntColumnType,
            mapping = columnMapping(fromStorage = { it }, toStorage = { it }),
            firstParameter = 20,
            secondParameter = 6,
        ),
    )
}

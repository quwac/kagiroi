package dev.quwac.kagiroi.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ColumnTypeConversionTest {
    @Test
    fun exportsTypedConversionSeparatelyFromThePhysicalSchema() {
        val converted = schema(ConversionTable(true)).project().encode()
        val plain = schema(ConversionTable(false)).project().encode()
        assertEquals(
            Json.parseToJsonElement(plain).jsonObject["schema"],
            Json.parseToJsonElement(converted).jsonObject["schema"],
        )
        assertTrue(converted.contains("\"formatVersion\":4"))
        assertTrue(converted.contains("\"typeConversions\""))
        assertTrue(converted.contains("\"from\":{\"id\":\"kagiroi.int32\""))
    }

    @Test
    fun rejectsOtherTableReferencesAndRepeatedConversions() {
        val other = ConversionTable(false)
        val target = ConversionTable(false)
        assertFailsWith<IllegalArgumentException> { target.value.typeUsing(IntColumnType) { other.value } }
        val converted = ConversionTable(true)
        assertFailsWith<IllegalArgumentException> {
            converted.value.typeUsing(IntColumnType) { cast(it, longType()) }
        }
    }
}

private class ConversionTable(convert: Boolean) : Table("records") {
    val value = long("value").let { column ->
        if (convert) column.typeUsing(IntColumnType) { cast(it, longType()) } else column
    }
}

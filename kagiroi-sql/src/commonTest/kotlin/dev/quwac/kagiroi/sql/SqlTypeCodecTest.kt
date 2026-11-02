package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.StringColumnType
import dev.quwac.kagiroi.schema.columnMapping
import dev.quwac.kagiroi.schema.scalarMappedColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SqlTypeCodecTest {
    @Test
    fun resolvesStorageMappingsDeclaredByTheColumnType() {
        val codecs = SqlTypeCodecRegistry(emptyList())

        assertSame(StringColumnType, codecs.storageType(EmailAddressColumnType))
        assertEquals(
            EncodedSqlValue(StringColumnType, "ada@example.test"),
            codecs.encode(EmailAddressColumnType, EmailAddress("ada@example.test")),
        )
        assertEquals(
            DecodedSqlValue(EmailAddress("ada@example.test")),
            codecs.decode(EmailAddressColumnType, "ada@example.test"),
        )
    }
}

private data class EmailAddress(val value: String)

private val EmailAddressColumnType: ColumnType<EmailAddress> = scalarMappedColumnType(
    name = "EmailAddress",
    storageType = StringColumnType,
    mapping = columnMapping(fromStorage = ::EmailAddress, toStorage = EmailAddress::value),
)

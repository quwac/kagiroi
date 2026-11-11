package dev.quwac.kagiroi.mysql.hyperdrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class HyperdriveMySqlConnectionStringTest {
    @Test
    fun decodesHyperdriveFieldsAndKeepsDriverOptionsOutOfTheUri() {
        assertEquals(
            listOf("::1", "user@name", "p:a/ss", "db name", "3307", "true", "true", "undefined", "undefined"),
            connectionFields(
                uri(
                    authority = "user%40name:p%3Aa%2Fss@[::1]:3307",
                    path = "db%20name?ssl-mode=DISABLED&rowsAsArray=false",
                ),
            ),
        )
        assertEquals("3306", connectionFields(uri())[4])
    }

    @Test
    fun rejectsOtherProtocolsAndInvalidUrls() {
        assertFails { connectionFields(uri(protocol = "postgres")) }
        assertFails { connectionFields("invalid") }
    }
}

internal expect fun connectionFields(connectionString: String): List<String>

private fun uri(protocol: String = "mysql", authority: String = "user:password@localhost", path: String = "db") =
    "$protocol://$authority/$path"

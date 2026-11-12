package dev.quwac.kagiroi.oracle.node

import dev.quwac.kagiroi.oracle.kotlinx.datetime.OracleKotlinxDateTime
import dev.quwac.kagiroi.oracle.mergeInto
import dev.quwac.kagiroi.oracle.quwac.bignumber.OracleQuwacBigNumber
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeOracleIntegrationTest {
    @Test
    fun nativeContextAndPoolReuse() = runTest {
        val settings = integrationSettings() ?: return@runTest
        val database = NodeOracleDatabase {
            user = settings.user
            password = settings.password
            connectString = settings.connectString
            pool { maxConnections = 1 }
        }
        try {
            oracleRlsContextContract(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun executesNativeMerge() = runTest {
        val settings = integrationSettings() ?: return@runTest
        val database = configuredDatabase(settings)
        try {
            database.executeRaw(
                "CREATE TABLE \"kagiroi_merge_target\" (\"id\" NUMBER PRIMARY KEY, \"name\" VARCHAR2(30))",
            )
            database.executeRaw(
                "CREATE TABLE \"kagiroi_merge_source\" (\"id\" NUMBER PRIMARY KEY, \"name\" VARCHAR2(30))",
            )
            database.executeRaw("INSERT INTO \"kagiroi_merge_target\" VALUES (1, 'old')")
            database.executeRaw("INSERT INTO \"kagiroi_merge_source\" VALUES (1, 'new')")
            database.executeRaw("INSERT INTO \"kagiroi_merge_source\" VALUES (2, 'added')")
            val source = MergeSource.alias("s")
            database.mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
                whenMatchedUpdate { set(MergeTarget.name, source[MergeSource.name]) }
                whenNotMatchedInsert {
                    set(
                        MergeTarget.id,
                        source[MergeSource.id],
                    )
                    set(MergeTarget.name, source[MergeSource.name])
                }
            }.execute()
            assertEquals(
                2L,
                database.queryRaw(
                    "SELECT COUNT(*) AS \"value\" FROM \"kagiroi_merge_target\" WHERE \"name\" IN ('new', 'added')",
                ).toList().single().long("value"),
            )
        } finally {
            database.executeRaw("DROP TABLE \"kagiroi_merge_source\"")
            database.executeRaw("DROP TABLE \"kagiroi_merge_target\"")
            database.close()
        }
    }

    @Test
    fun oracle23Contract() = runTest {
        val settings = integrationSettings() ?: return@runTest
        oracleIntegrationContract(configuredDatabase(settings))
    }
}

private data class OracleIntegrationSettings(val connectString: String, val user: String, val password: String)

private fun integrationSettings(): OracleIntegrationSettings? {
    val connectString = environmentVariable("KAGIROI_ORACLE_TEST_CONNECT_STRING") ?: return null
    val user = environmentVariable("KAGIROI_ORACLE_TEST_USER") ?: return null
    val password = environmentVariable("KAGIROI_ORACLE_TEST_PASSWORD") ?: return null
    return OracleIntegrationSettings(connectString, user, password)
}

private fun configuredDatabase(settings: OracleIntegrationSettings): NodeOracleDatabase = NodeOracleDatabase {
    connectString = settings.connectString
    user = settings.user
    password = settings.password
    pool { maxConnections = 4 }
    install(OracleQuwacBigNumber)
    install(OracleKotlinxDateTime)
}

@Suppress("UnusedParameter")
private fun environmentVariable(name: String): String? =
    js("typeof process !== 'undefined' && typeof process.env[name] === 'string' ? process.env[name] : null") as? String

private object MergeTarget : Table("kagiroi_merge_target") {
    val id = int("id")
    val name = string("name")
}
private object MergeSource : Table("kagiroi_merge_source") {
    val id = int("id")
    val name = string("name")
}

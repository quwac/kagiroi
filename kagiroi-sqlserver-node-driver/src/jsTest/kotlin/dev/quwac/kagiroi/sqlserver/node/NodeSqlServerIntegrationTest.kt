package dev.quwac.kagiroi.sqlserver.node

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.dedicatedSession
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sqlserver.mergeInto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class NodeSqlServerIntegrationTest {
    @Test
    fun nativeRlsAndPoolReuse() = runTest {
        val host = environmentVariable("KAGIROI_SQLSERVER_TEST_HOST") ?: return@runTest
        val database = NodeSqlServerDatabase {
            this.host = host
            port = environmentVariable("KAGIROI_SQLSERVER_TEST_PORT")?.toInt() ?: 1433
            this.database = environmentVariable("KAGIROI_SQLSERVER_TEST_DATABASE") ?: "master"
            user = environmentVariable("KAGIROI_SQLSERVER_TEST_USER") ?: "sa"
            password = environmentVariable("KAGIROI_SQLSERVER_TEST_PASSWORD") ?: "Kagiroi!Passw0rd"
            pool { maxConnections = 1 }
            tls { mode = NodeSqlServerTlsMode.REQUIRE }
        }
        try {
            sqlServerRlsContract(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun dedicatedSessionKeepsExplicitTransactionOnOneConnection() = runTest {
        val host = environmentVariable("KAGIROI_SQLSERVER_TEST_HOST") ?: return@runTest
        val database = NodeSqlServerDatabase {
            this.host = host
            port = environmentVariable("KAGIROI_SQLSERVER_TEST_PORT")?.toInt() ?: 1433
            this.database = environmentVariable("KAGIROI_SQLSERVER_TEST_DATABASE") ?: "master"
            user = environmentVariable("KAGIROI_SQLSERVER_TEST_USER") ?: "sa"
            password = environmentVariable("KAGIROI_SQLSERVER_TEST_PASSWORD") ?: "Kagiroi!Passw0rd"
            tls { mode = NodeSqlServerTlsMode.REQUIRE }
        }
        try {
            database.dedicatedSession {
                val first = queryRaw("SELECT @@SPID AS session_id").toList().single().long("session_id")
                executeRaw("BEGIN TRANSACTION")
                executeRaw("CREATE TABLE #kagiroi_session_contract (id INT NOT NULL)")
                executeRaw("INSERT INTO #kagiroi_session_contract VALUES (1)")
                assertEquals(
                    1L,
                    queryRaw("SELECT COUNT(*) AS value FROM #kagiroi_session_contract").toList().single().long("value"),
                )
                assertEquals(first, queryRaw("SELECT @@SPID AS session_id").toList().single().long("session_id"))
                executeRaw("ROLLBACK TRANSACTION")
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun executesNativeMerge() = runTest {
        val host = environmentVariable("KAGIROI_SQLSERVER_TEST_HOST") ?: return@runTest
        val database = NodeSqlServerDatabase {
            this.host = host
            port = environmentVariable("KAGIROI_SQLSERVER_TEST_PORT")?.toInt() ?: 1433
            this.database = environmentVariable("KAGIROI_SQLSERVER_TEST_DATABASE") ?: "master"
            user = environmentVariable("KAGIROI_SQLSERVER_TEST_USER") ?: "sa"
            password = environmentVariable("KAGIROI_SQLSERVER_TEST_PASSWORD") ?: "Kagiroi!Passw0rd"
            tls { mode = NodeSqlServerTlsMode.REQUIRE }
        }
        try {
            database.dedicatedSession {
                executeRaw("CREATE TABLE [#merge_target] ([id] BIGINT PRIMARY KEY, [name] NVARCHAR(30))")
                executeRaw("CREATE TABLE [#merge_source] ([id] BIGINT PRIMARY KEY, [name] NVARCHAR(30))")
                executeRaw("INSERT INTO [#merge_target] VALUES (1, 'old')")
                executeRaw("INSERT INTO [#merge_source] VALUES (1, 'new'), (2, 'added')")
                val source = MergeSource.alias("s")
                mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
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
                    queryRaw(
                        "SELECT COUNT(*) AS value FROM [#merge_target] WHERE [name] IN ('new', 'added')",
                    ).toList().single().long("value"),
                )
                executeRaw("UPDATE [#merge_source] SET [name] = 'derived'")
                val derived = dev.quwac.kagiroi.sql.select(
                    MergeSource.id,
                    MergeSource.name,
                ).from(MergeSource).asTable("d")
                mergeInto(MergeTarget, derived, MergeTarget.id eq derived[MergeSource.id]) {
                    whenMatchedUpdate { set(MergeTarget.name, derived[MergeSource.name]) }
                }.execute()
                assertEquals(
                    2L,
                    queryRaw(
                        "SELECT COUNT(*) AS value FROM [#merge_target] WHERE [name] = 'derived'",
                    ).toList().single().long("value"),
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun sqlServerContract() = runTest {
        val host = environmentVariable("KAGIROI_SQLSERVER_TEST_HOST") ?: return@runTest
        nodeSqlServerDriverContract(
            NodeSqlServerDatabase {
                this.host = host
                port = environmentVariable("KAGIROI_SQLSERVER_TEST_PORT")?.toInt() ?: 1433
                database = environmentVariable("KAGIROI_SQLSERVER_TEST_DATABASE") ?: "master"
                user = environmentVariable("KAGIROI_SQLSERVER_TEST_USER") ?: "sa"
                password = environmentVariable("KAGIROI_SQLSERVER_TEST_PASSWORD") ?: "Kagiroi!Passw0rd"
                pool {
                    maxConnections = 4
                    connectionTimeout = 10.seconds
                }
                tls {
                    mode = NodeSqlServerTlsMode.REQUIRE
                }
            },
        )
    }
}

@Suppress("UnusedParameter")
private fun environmentVariable(name: String): String? =
    js("typeof process !== 'undefined' ? process.env[name] : undefined") as? String

private object MergeTarget : Table("#merge_target") {
    val id = long("id")
    val name = string("name")
}
private object MergeSource : Table("#merge_source") {
    val id = long("id")
    val name = string("name")
}

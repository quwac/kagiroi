package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.DatabaseClosed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/** Direct pool lifecycle, reuse, acquisition timeout, and close contract. */
internal suspend fun nodeMySqlPoolContract(url: String) {
    val reuseDatabase = NodeMySqlDatabase(url) {
        pool {
            maxConnections = 1
            maxIdleConnections = 1
        }
    }
    val firstConnection = reuseDatabase.queryRaw("SELECT CONNECTION_ID() AS connection_id")
        .toList().single().long("connection_id")
    val secondConnection = reuseDatabase.queryRaw("SELECT CONNECTION_ID() AS connection_id")
        .toList().single().long("connection_id")
    assertEquals(firstConnection, secondConnection)
    reuseDatabase.close()
    reuseDatabase.close()
    assertFailsWith<DatabaseClosed> { reuseDatabase.executeRaw("SELECT 1") }

    verifyCloseDrainsLeasedTransaction(url)

    val exhaustedDatabase = NodeMySqlDatabase(url) {
        pool {
            maxConnections = 1
            acquisitionTimeout = POOL_ACQUISITION_TIMEOUT
        }
    }
    val acquired = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    coroutineScope {
        val holder = launch {
            exhaustedDatabase.transaction {
                queryRaw("SELECT 1").toList()
                acquired.complete(Unit)
                release.await()
            }
        }
        acquired.await()
        try {
            assertFailsWith<ConnectionFailure> {
                exhaustedDatabase.queryRaw("SELECT 1").toList()
            }
        } finally {
            release.complete(Unit)
            holder.join()
            exhaustedDatabase.close()
        }
    }
}

private suspend fun verifyCloseDrainsLeasedTransaction(url: String) = coroutineScope {
    val database = NodeMySqlDatabase(url)
    val acquired = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val transaction = launch {
        database.transaction {
            queryRaw("SELECT 1").toList()
            acquired.complete(Unit)
            release.await()
            queryRaw("SELECT 2").toList()
        }
    }
    acquired.await()
    val close = async { database.close() }
    yield()
    assertEquals(false, close.isCompleted)
    release.complete(Unit)
    transaction.join()
    close.await()
    assertFailsWith<DatabaseClosed> { database.executeRaw("SELECT 1") }
}

private val POOL_ACQUISITION_TIMEOUT = 200.milliseconds

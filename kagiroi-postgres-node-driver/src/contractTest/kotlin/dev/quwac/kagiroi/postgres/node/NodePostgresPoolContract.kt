package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.sql.ConnectionFailure
import dev.quwac.kagiroi.sql.DatabaseClosed
import dev.quwac.kagiroi.sql.dedicatedSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/** Direct pool lifecycle, reuse, acquisition timeout, and close contract. */
internal suspend fun nodePostgresPoolContract(url: String) {
    val reuseDatabase = NodePostgresDatabase(url) {
        pool {
            minConnections = 1
            maxConnections = 1
        }
    }
    val firstProcess = reuseDatabase.queryRaw("SELECT pg_backend_pid() AS pid").toList().single().long("pid")
    val secondProcess = reuseDatabase.queryRaw("SELECT pg_backend_pid() AS pid").toList().single().long("pid")
    assertEquals(firstProcess, secondProcess)
    val original = IllegalStateException("dedicated session failure")
    val propagated = assertFailsWith<IllegalStateException> {
        reuseDatabase.dedicatedSession {
            executeRaw("SELECT 1")
            throw original
        }
    }
    assertSame(original, propagated)
    assertEquals(firstProcess, reuseDatabase.queryRaw("SELECT pg_backend_pid() AS pid").toList().single().long("pid"))
    reuseDatabase.close()
    reuseDatabase.close()
    assertFailsWith<DatabaseClosed> { reuseDatabase.executeRaw("SELECT 1") }

    verifyCloseDrainsLeasedTransaction(url)

    val exhaustedDatabase = NodePostgresDatabase(url) {
        pool {
            maxConnections = 1
            connectionTimeout = POOL_ACQUISITION_TIMEOUT
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
    val database = NodePostgresDatabase(url)
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

package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.onConflict
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PGliteInsertConflictIntegrationTest {
    @Test
    @Suppress("LongMethod")
    fun executesConflictUpdateDoNothingReturningAndCteInsertSelect() = runTest {
        val database = PGliteDatabase.inMemory()
        try {
            database.execute(createTable(PGliteUpsertRows))
            database.execute(createTable(PGliteUpsertSource))
            database.insertInto(PGliteUpsertRows).values {
                set(PGliteUpsertRows.id, 1L)
                set(PGliteUpsertRows.email, "ada@example.test")
                set(PGliteUpsertRows.name, "Ada")
                set(PGliteUpsertRows.revision, 1L)
            }.execute()

            val updated = database.insertInto(PGliteUpsertRows).values {
                set(PGliteUpsertRows.id, 2L)
                set(PGliteUpsertRows.email, "ada@example.test")
                set(PGliteUpsertRows.name, "Grace")
                set(PGliteUpsertRows.revision, 2L)
            }.onConflict(PGliteUpsertRows.email) {
                doUpdate {
                    set(PGliteUpsertRows.name, excluded(PGliteUpsertRows.name))
                    where(excluded(PGliteUpsertRows.revision) gt PGliteUpsertRows.revision)
                }
            }.returning(PGliteUpsertRows.id, PGliteUpsertRows.name).one()
            assertEquals(1L, updated[PGliteUpsertRows.id])
            assertEquals("Grace", updated[PGliteUpsertRows.name])

            val unchanged = database.insertInto(PGliteUpsertRows).values {
                set(PGliteUpsertRows.id, 3L)
                set(PGliteUpsertRows.email, "ada@example.test")
                set(PGliteUpsertRows.name, "Ignored")
                set(PGliteUpsertRows.revision, 3L)
            }.onConflict {
                doNothing()
            }.execute()
            assertEquals(0L, unchanged.affectedRows)

            database.insertInto(PGliteUpsertSource).values {
                set(PGliteUpsertSource.id, 4L)
                set(PGliteUpsertSource.email, "lin@example.test")
                set(PGliteUpsertSource.name, "Lin")
                set(PGliteUpsertSource.revision, 1L)
            }.execute()
            val staged = select(
                PGliteUpsertSource.id,
                PGliteUpsertSource.email,
                PGliteUpsertSource.name,
                PGliteUpsertSource.revision,
            ).from(PGliteUpsertSource).where(PGliteUpsertSource.id eq 4L).asCte("staged_upsert_rows")
            val stagedId = staged[PGliteUpsertSource.id]
            val stagedEmail = staged[PGliteUpsertSource.email]
            val stagedName = staged[PGliteUpsertSource.name]
            val stagedRevision = staged[PGliteUpsertSource.revision]
            val inserted = with(staged) {
                database.insertInto(
                    PGliteUpsertRows,
                    PGliteUpsertRows.id,
                    PGliteUpsertRows.email,
                    PGliteUpsertRows.name,
                    PGliteUpsertRows.revision,
                ).from(select(stagedId, stagedEmail, stagedName, stagedRevision).from(staged))
            }.onConflict(PGliteUpsertRows.email) {
                doNothing()
            }.returning(PGliteUpsertRows.id).one()
            assertEquals(4L, inserted[PGliteUpsertRows.id])
        } finally {
            database.close()
        }
    }
}

private object PGliteUpsertRows : Table("pglite_upsert_rows") {
    val id = long("id").primaryKey()
    val email = string("email").unique()
    val name = string("name")
    val revision = long("revision")
}

private object PGliteUpsertSource : Table("pglite_upsert_source") {
    val id = long("id").primaryKey()
    val email = string("email")
    val name = string("name")
    val revision = long("revision")
}

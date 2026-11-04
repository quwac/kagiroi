package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.sqlServerMerge
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteInsertConflictTest {
    private val renderer = SqlRenderer(SqliteDialect)

    @Test
    fun rendersUpdateExcludedPredicateAndReturning() {
        val query = insertInto(UpsertSqliteUsers).values {
            set(UpsertSqliteUsers.id, 1L)
            set(UpsertSqliteUsers.email, "ada@example.test")
            set(UpsertSqliteUsers.name, "Ada")
            set(UpsertSqliteUsers.revision, 2L)
        }.onConflict(UpsertSqliteUsers.email) {
            doUpdate {
                set(UpsertSqliteUsers.name, excluded(UpsertSqliteUsers.name))
                where(excluded(UpsertSqliteUsers.revision) gt UpsertSqliteUsers.revision)
            }
        }.returning(UpsertSqliteUsers.id, UpsertSqliteUsers.name)

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"email\", \"name\", \"revision\") VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (\"email\") DO UPDATE SET \"name\" = excluded.\"name\" " +
                "WHERE excluded.\"revision\" > \"users\".\"revision\" RETURNING \"id\", \"name\"",
            renderer.render(query).sql,
        )
    }

    @Test
    fun insertsSelectWithRendererOnlyWhereTrueWorkaroundWhenNeeded() {
        val noWhere = insertInto(UpsertSqliteUsers, UpsertSqliteUsers.id, UpsertSqliteUsers.email)
            .from(select(UpsertSqliteArchive.id, UpsertSqliteArchive.email).from(UpsertSqliteArchive))
            .onConflict(UpsertSqliteUsers.email) { doNothing() }
        val withWhere = insertInto(UpsertSqliteUsers, UpsertSqliteUsers.id, UpsertSqliteUsers.email)
            .from(
                select(
                    UpsertSqliteArchive.id,
                    UpsertSqliteArchive.email,
                ).from(UpsertSqliteArchive).where(UpsertSqliteArchive.active eq true),
            )
            .onConflict(UpsertSqliteUsers.email) { doNothing() }

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"email\") SELECT \"archive\".\"id\", \"archive\".\"email\" " +
                "FROM \"archive\" WHERE TRUE ON CONFLICT (\"email\") DO NOTHING",
            renderer.render(noWhere).sql,
        )
        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"email\") SELECT \"archive\".\"id\", \"archive\".\"email\" " +
                "FROM \"archive\" WHERE \"archive\".\"active\" = ? ON CONFLICT (\"email\") DO NOTHING",
            renderer.render(withWhere).sql,
        )
    }

    @Test
    fun rejectsReplaceAndMergeOnUnsupportedDialects() {
        val unsupported = SqlRenderer(object : SqlDialect {
            override fun quoteIdentifier(identifier: String) = identifier
            override fun parameterMarker(position: Int) = "?"
        })
        val replace = replaceInto(UpsertSqliteUsers).values { set(UpsertSqliteUsers.id, 1L) }
        assertFailsWith<UnsupportedCapability> { unsupported.render(replace) }
        val source = UpsertSqliteArchive.alias("source")
        val merge = sqlServerMerge(UpsertSqliteUsers, source, UpsertSqliteUsers.id eq source[UpsertSqliteArchive.id]) {
            whenMatchedDelete()
        }
        assertFailsWith<UnsupportedCapability> { renderer.render(merge) }
    }

    @Test
    fun rendersNativeReplace() {
        val query = replaceInto(UpsertSqliteUsers).values {
            set(UpsertSqliteUsers.id, 1L)
            set(UpsertSqliteUsers.email, "one@example.test")
        }
        assertEquals(
            "REPLACE INTO \"users\" (\"id\", \"email\") VALUES (?, ?)",
            renderer.render(query).sql,
        )
    }

    @Test
    fun rendersReplaceFromTypedSelect() {
        val query = replaceInto(UpsertSqliteUsers, UpsertSqliteUsers.id, UpsertSqliteUsers.email)
            .from(select(UpsertSqliteArchive.id, UpsertSqliteArchive.email).from(UpsertSqliteArchive))
        assertEquals(
            "REPLACE INTO \"users\" (\"id\", \"email\") " +
                "SELECT \"archive\".\"id\", \"archive\".\"email\" FROM \"archive\"",
            renderer.render(query).sql,
        )
    }

    @Test
    fun rendersOrderedClausesAndRejectsClausesAfterTargetless() {
        val base = insertInto(UpsertSqliteUsers).values {
            set(UpsertSqliteUsers.id, 1L)
            set(UpsertSqliteUsers.email, "one@example.test")
        }
        val targeted = base.onConflict(UpsertSqliteUsers.id) { doNothing() }
            .onConflict(UpsertSqliteUsers.email) { doUpdate { set(UpsertSqliteUsers.name, "updated") } }
        val query = targeted.onConflict { doNothing() }
        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"email\") VALUES (?, ?) " +
                "ON CONFLICT (\"id\") DO NOTHING ON CONFLICT (\"email\") " +
                "DO UPDATE SET \"name\" = ? ON CONFLICT DO NOTHING",
            renderer.render(query).sql,
        )
        assertFailsWith<IllegalArgumentException> {
            query.onConflict(UpsertSqliteUsers.name) { doNothing() }
        }
    }

    @Test
    fun rendersOneTargetlessFinalClauseAndNativeMultiRowValues() {
        val query = insertInto(UpsertSqliteUsers).values {
            set(UpsertSqliteUsers.id, 1L)
            set(UpsertSqliteUsers.email, "one@example.test")
        }.values {
            set(UpsertSqliteUsers.id, 2L)
            set(UpsertSqliteUsers.email, "two@example.test")
        }.onConflict {
            doNothing()
        }

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"email\") VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING",
            renderer.render(query).sql,
        )
    }
}

private object UpsertSqliteUsers : Table("users") {
    val id = long("id")
    val email = string("email")
    val name = string("name")
    val revision = long("revision")
}

private object UpsertSqliteArchive : Table("archive") {
    val id = long("id")
    val email = string("email")
    val active = boolean("active")
}

package dev.quwac.kagiroi.postgres

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.with
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresInsertConflictTest {
    private val renderer = SqlRenderer(PostgresDialect)

    @Test
    fun rendersTargetlessAndColumnTargetDoNothing() {
        val targetless = insertInto(PostgresUsers).values {
            set(PostgresUsers.email, "ada@example.test")
        }.onConflict {
            doNothing()
        }
        val targeted = insertInto(PostgresUsers).values {
            set(PostgresUsers.organizationId, 7L)
            set(PostgresUsers.email, "ada@example.test")
        }.onConflict(PostgresUsers.organizationId, PostgresUsers.email) {
            doNothing()
        }

        assertEquals(
            "INSERT INTO \"app\".\"users\" (\"email\") VALUES ($1) ON CONFLICT DO NOTHING",
            renderer.render(targetless).sql,
        )
        assertEquals(
            "INSERT INTO \"app\".\"users\" (\"organization_id\", \"email\") VALUES ($1, $2) " +
                "ON CONFLICT (\"organization_id\", \"email\") DO NOTHING",
            renderer.render(targeted).sql,
        )
    }

    @Test
    fun rendersConstraintTargetUpdateExcludedCurrentRowPredicateAndReturning() {
        val query = insertInto(PostgresUsers).values {
            set(PostgresUsers.id, 1L)
            set(PostgresUsers.email, "ada@example.test")
            set(PostgresUsers.name, "Ada")
            set(PostgresUsers.revision, 3L)
        }.onConflictConstraint("uq_users_email") {
            doUpdate {
                set(PostgresUsers.name, excluded(PostgresUsers.name))
                set(PostgresUsers.revision, PostgresUsers.revision + excluded(PostgresUsers.revision))
                where(excluded(PostgresUsers.revision) gt PostgresUsers.revision)
            }
        }.returning(PostgresUsers.id, PostgresUsers.name)

        val rendered = renderer.render(query)

        assertEquals(
            "INSERT INTO \"app\".\"users\" (\"id\", \"email\", \"name\", \"revision\") " +
                "VALUES ($1, $2, $3, $4) ON CONFLICT ON CONSTRAINT \"uq_users_email\" DO UPDATE SET " +
                "\"name\" = excluded.\"name\", \"revision\" = (\"app\".\"users\".\"revision\" + " +
                "excluded.\"revision\") WHERE excluded.\"revision\" > \"app\".\"users\".\"revision\" " +
                "RETURNING \"id\", \"name\"",
            rendered.sql,
        )
        assertEquals(listOf(1L, "ada@example.test", "Ada", 3L), rendered.parameters.map { it.value })
    }

    @Test
    fun preservesValuesSelectAndWithCompositionAndParameterOrder() {
        val multiRow = insertInto(PostgresUsers).values {
            set(PostgresUsers.id, 1L)
            set(PostgresUsers.email, "one@example.test")
        }.values {
            set(PostgresUsers.id, 2L)
            set(PostgresUsers.email, "two@example.test")
        }.onConflict(PostgresUsers.email, where = PostgresUsers.active eq true) {
            doUpdate { set(PostgresUsers.name, "updated") }
        }
        val source = select(PostgresArchive.id, PostgresArchive.email)
            .from(PostgresArchive)
            .asCte("archive_users")
        val withInsert = with(source) {
            insertInto(PostgresUsers, PostgresUsers.id, PostgresUsers.email)
                .from(select(source[PostgresArchive.id], source[PostgresArchive.email]).from(source))
        }.onConflict(PostgresUsers.email) {
            doNothing()
        }

        val multi = renderer.render(multiRow)
        assertEquals(
            "INSERT INTO \"app\".\"users\" (\"id\", \"email\") VALUES ($1, $2), ($3, $4) " +
                "ON CONFLICT (\"email\") WHERE \"app\".\"users\".\"active\" = $5 DO UPDATE SET \"name\" = $6",
            multi.sql,
        )
        assertEquals(
            listOf(1L, "one@example.test", 2L, "two@example.test", true, "updated"),
            multi.parameters.map { it.value },
        )
        assertEquals(
            "WITH \"archive_users\" AS (SELECT \"app\".\"archive\".\"id\", \"app\".\"archive\".\"email\" " +
                "FROM \"app\".\"archive\") INSERT INTO \"app\".\"users\" (\"id\", \"email\") " +
                "SELECT \"archive_users\".\"id\", \"archive_users\".\"email\" FROM \"archive_users\" " +
                "ON CONFLICT (\"email\") DO NOTHING",
            renderer.render(withInsert).sql,
        )
    }

    @Test
    fun rejectsInvalidStateAndScopeBeforeRendering() {
        assertFailsWith<IllegalArgumentException> {
            insertInto(PostgresUsers).values { set(PostgresUsers.email, "ada@example.test") }.onConflict {
                doUpdate { set(PostgresUsers.name, "Ada") }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            insertInto(PostgresUsers).values { set(PostgresUsers.email, "ada@example.test") }
                .onConflict(PostgresUsers.email) { doUpdate { } }
        }
        assertFailsWith<IllegalArgumentException> {
            insertInto(PostgresUsers).values { set(PostgresUsers.email, "ada@example.test") }
                .onConflict(PostgresUsers.email) {
                    doUpdate {
                        set(PostgresUsers.name, "Ada")
                        set(PostgresUsers.name, "Grace")
                    }
                }
        }
        assertFailsWith<IllegalArgumentException> {
            insertInto(PostgresUsers).values { set(PostgresUsers.email, "ada@example.test") }
                .onConflict(PostgresUsers.email) {
                    doUpdate { set(PostgresUsers.name, excluded(PostgresArchive.email)) }
                }
        }
    }
}

private object PostgresUsers : Table("users", schemaName = "app") {
    val id = long("id")
    val organizationId = long("organization_id")
    val email = string("email").unique()
    val name = string("name")
    val revision = long("revision")
    val active = boolean("active")
}

private object PostgresArchive : Table("archive", schemaName = "app") {
    val id = long("id")
    val email = string("email")
}

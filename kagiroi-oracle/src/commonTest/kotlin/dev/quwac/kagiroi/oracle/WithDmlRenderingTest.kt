package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WithDmlRenderingTest {
    @Test
    fun rendersCteAtOraclesInsertSelectPositionAndRejectsOtherDmlKinds() {
        val targets = select(WithDmlUsers.id).from(WithDmlUsers).where(WithDmlUsers.name eq "source").asCte("targets")
        val targetId = targets[WithDmlUsers.id]
        val insert = with(targets) {
            insertInto(WithDmlArchive, WithDmlArchive.id).from(select(targetId).from(targets))
        }

        val insertSql = SqlRenderer(OracleDialect).render(insert).sql
        assertTrue(
            insertSql.startsWith(
                "INSERT INTO \"with_dml_archive\" (\"id\") WITH \"targets\" AS (SELECT",
            ),
            insertSql,
        )
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(OracleDialect).render(
                with(targets) {
                    update(WithDmlUsers) { set(WithDmlUsers.name, "processed") }
                        .where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
                },
            )
        }
        assertFailsWith<UnsupportedCapability> {
            SqlRenderer(OracleDialect).render(
                with(targets) {
                    deleteFrom(WithDmlUsers).where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
                },
            )
        }
    }

    @Test
    fun rendersRecursiveCteAtTheInsertSelectPosition() {
        val n = literal(1L).alias("n")
        val numbers = recursiveCte("numbers", select(n)) { self ->
            select(self[n] + 1L).from(self).where(self[n] lt 3L)
        }
        val sql = SqlRenderer(OracleDialect).render(
            with(numbers) {
                insertInto(WithDmlArchive, WithDmlArchive.id).from(select(numbers[n]).from(numbers))
            },
        ).sql

        assertTrue(
            sql.startsWith("INSERT INTO \"with_dml_archive\" (\"id\") WITH \"numbers\"(\"n\") AS (SELECT"),
            sql,
        )
    }

    @Test
    fun rejectsReturningIntoForCteScopedInsertSelect() {
        val execution = RecordingOracleExecution()
        val targets = select(WithDmlUsers.id).from(WithDmlUsers).asCte("targets")
        val targetId = targets[WithDmlUsers.id]

        assertFailsWith<UnsupportedCapability> {
            with(targets) {
                execution.insertInto(WithDmlArchive, WithDmlArchive.id).from(select(targetId).from(targets))
            }.returningInto(WithDmlArchive.id)
        }
    }
}

private class RecordingOracleExecution : SqlExecution {
    override val dialect = OracleDialect

    override suspend fun execute(statement: RenderedSql): ExecuteResult = error("This test only renders RETURNING INTO")

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> =
        error("This test only renders RETURNING INTO")

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
        error("This test only renders RETURNING INTO")

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = emptyFlow()
}

private object WithDmlUsers : Table("with_dml_users") {
    val id = long("id")
    val name = string("name")
}

private object WithDmlArchive : Table("with_dml_archive") {
    val id = long("id")
}

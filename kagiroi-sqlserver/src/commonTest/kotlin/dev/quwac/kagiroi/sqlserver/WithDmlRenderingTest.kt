package dev.quwac.kagiroi.sqlserver

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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class WithDmlRenderingTest {
    @Test
    fun rendersCteScopesBeforeTsqlDmlAndKeepsOutputPosition() {
        val targets = select(WithDmlUsers.id).from(WithDmlUsers).where(WithDmlUsers.name eq "source").asCte("targets")
        val targetId = targets[WithDmlUsers.id]
        val update = with(targets) {
            update(WithDmlUsers) { set(WithDmlUsers.name, "processed") }
                .where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
        }
        val delete = with(targets) {
            deleteFrom(WithDmlUsers).where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
        }
        val insert = with(targets) {
            insertInto(WithDmlArchive, WithDmlArchive.id).from(select(targetId).from(targets))
        }
        assertTrue(SqlRenderer(SqlServerDialect).render(update).sql.startsWith("WITH [targets] AS (SELECT"))
        assertTrue(SqlRenderer(SqlServerDialect).render(delete).sql.contains(") DELETE FROM [with_dml_users]"))
        assertTrue(SqlRenderer(SqlServerDialect).render(insert).sql.contains(") INSERT INTO [with_dml_archive]"))
    }

    @Test
    fun rendersRecursiveCteBeforeDeleteWithoutSyntheticSemicolon() {
        val n = literal(1L).alias("n")
        val numbers = recursiveCte("numbers", select(n)) { self ->
            select(self[n] + 1L).from(self).where(self[n] lt 3L)
        }
        val sql = SqlRenderer(SqlServerDialect).render(
            with(numbers) {
                deleteFrom(WithDmlUsers).where(WithDmlUsers.id.inSubquery(select(numbers[n]).from(numbers)))
            },
        ).sql

        assertTrue(sql.startsWith("WITH [numbers]([n]) AS (SELECT"))
        assertTrue(!sql.startsWith(";WITH"))
    }

    @Test
    fun keepsThePublicOutputApiOnACteScopedUpdate() = runTest {
        val execution = RecordingSqlServerExecution()
        val targets = select(WithDmlUsers.id).from(WithDmlUsers).asCte("targets")
        val targetId = targets[WithDmlUsers.id]

        with(targets) {
            execution.update(WithDmlUsers) { set(WithDmlUsers.name, "processed") }
                .where(WithDmlUsers.id.inSubquery(select(targetId).from(targets)))
        }.output(inserted(WithDmlUsers.name)).all()

        val sql = requireNotNull(execution.queried).sql
        assertTrue(sql.startsWith("WITH [targets] AS (SELECT"), sql)
        assertTrue(sql.contains("OUTPUT INSERTED.[name] WHERE"), sql)
    }
}

private class RecordingSqlServerExecution : SqlExecution {
    override val dialect = SqlServerDialect

    var queried: RenderedSql? = null

    override suspend fun execute(statement: RenderedSql): ExecuteResult = error("This test only renders OUTPUT")

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> {
        queried = statement
        return emptyFlow()
    }

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult =
        error("This test only renders OUTPUT")

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = error("This test only renders OUTPUT")
}

private object WithDmlUsers : Table("with_dml_users") {
    val id = long("id")
    val name = string("name")
}

private object WithDmlArchive : Table("with_dml_archive") {
    val id = long("id")
}

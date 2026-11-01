package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.Table
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A dialect-specific DML RETURNING INTO projection with generated private output-bind names. */
class SqlDmlReturningInto(columns: NonEmptyList<Column<*>>) {
    val columns: NonEmptyList<Column<*>> = columns
    val parameterNames: List<String> = columns.indices.map { index -> "kagiroi_ret_${index + 1}" }

    init {
        require(this.columns.distinct().size == this.columns.size) {
            "DML RETURNING INTO column appears more than once"
        }
    }
}

/** Buffered DML RETURNING INTO values decoded by the dialect driver's private execution bridge. */
class SqlDmlReturningIntoResult(val affectedRows: Long, val rows: List<SqlRow>)

/** Optional driver execution surface for dialect-specific DML RETURNING INTO out binds. */
interface SqlDmlReturningIntoExecution : SqlExecution {
    suspend fun executeDmlReturningInto(
        statement: RenderedSql,
        returning: SqlDmlReturningInto,
    ): SqlDmlReturningIntoResult
}

/** A typed dialect-specific DML RETURNING INTO result with strict cardinality helpers. */
class ExecutableDmlReturningIntoQuery internal constructor(
    private val execution: SqlDmlReturningIntoExecution,
    private val statement: RenderedSql,
    private val returning: SqlDmlReturningInto,
) {
    fun stream(): Flow<SqlRow> = flow {
        execution.executeDmlReturningInto(statement, returning).rows.forEach { row -> emit(row) }
    }

    suspend fun all(): List<SqlRow> = result().all()

    suspend fun <T> all(mapper: (SqlRow) -> T): List<T> = result().all(mapper)

    suspend fun one(): SqlRow = result().one()

    suspend fun <T> one(mapper: (SqlRow) -> T): T = result().one(mapper)

    suspend fun oneOrNull(): SqlRow? = result().oneOrNull()

    suspend fun <T> oneOrNull(mapper: (SqlRow) -> T): T? = result().oneOrNull(mapper)

    private fun result(): SqlQuery<SqlRow> = SqlQuery(stream())
}

fun <S : InsertQueryState.Renderable> ExecutableInsertQuery<S>.dmlReturningInto(
    returning: SqlDmlReturningInto,
): ExecutableDmlReturningIntoQuery {
    returning.requireFor(query.into)
    return execution.createDmlReturningInto(
        DmlReturningIntoRenderer(execution.dialect).render(query, returning),
        returning,
    )
}

fun <S : UpdateQueryState.Renderable> ExecutableUpdateQuery<S>.dmlReturningInto(
    returning: SqlDmlReturningInto,
): ExecutableDmlReturningIntoQuery {
    returning.requireFor(query.table)
    return execution.createDmlReturningInto(
        DmlReturningIntoRenderer(execution.dialect).render(query, returning),
        returning,
    )
}

fun <S : DeleteQueryState.Renderable> ExecutableDeleteQuery<S>.dmlReturningInto(
    returning: SqlDmlReturningInto,
): ExecutableDmlReturningIntoQuery {
    returning.requireFor(query.from)
    return execution.createDmlReturningInto(
        DmlReturningIntoRenderer(execution.dialect).render(query, returning),
        returning,
    )
}

private fun SqlExecution.createDmlReturningInto(
    statement: RenderedSql,
    returning: SqlDmlReturningInto,
): ExecutableDmlReturningIntoQuery {
    val returningExecution = this as? SqlDmlReturningIntoExecution
        ?: throw UnsupportedCapability("returning-into")
    return ExecutableDmlReturningIntoQuery(returningExecution, statement, returning)
}

private fun SqlDmlReturningInto.requireFor(table: Table) {
    columns.forEach { column ->
        require(column.table === table && table.hasColumn(column)) {
            "DML RETURNING INTO column is not registered by table $table: $column"
        }
    }
}

/** Renders a DML statement followed by a dialect-specific RETURNING INTO clause. */
internal class DmlReturningIntoRenderer(private val dialect: SqlDialect) {
    fun <S : InsertQueryState.Renderable> render(query: InsertQuery<S>, returning: SqlDmlReturningInto): RenderedSql =
        render(
            SqlRenderer(dialect).render(query),
            returning,
            SqlStatementKind.Insert,
            query.source is InsertSource.Query,
        )

    fun <S : UpdateQueryState.Renderable> render(query: UpdateQuery<S>, returning: SqlDmlReturningInto): RenderedSql =
        render(SqlRenderer(dialect).render(query), returning, SqlStatementKind.Update, insertUsesQuerySource = false)

    fun <S : DeleteQueryState.Renderable> render(query: DeleteQuery<S>, returning: SqlDmlReturningInto): RenderedSql =
        render(SqlRenderer(dialect).render(query), returning, SqlStatementKind.Delete, insertUsesQuerySource = false)

    private fun render(
        statement: RenderedSql,
        returning: SqlDmlReturningInto,
        kind: SqlStatementKind,
        insertUsesQuerySource: Boolean,
    ): RenderedSql {
        val returningDialect = dialect as? SqlDmlReturningIntoDialect
            ?: throw UnsupportedCapability("returning-into")
        dialect.validateDmlReturningInto(kind, insertUsesQuerySource)
        return statement.copy(sql = "${statement.sql} ${returningDialect.renderDmlReturningInto(returning)}")
    }
}

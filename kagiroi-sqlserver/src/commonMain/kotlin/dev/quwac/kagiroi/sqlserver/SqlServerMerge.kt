package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.SqlPredicate
import dev.quwac.kagiroi.sql.SqlServerMergeBuilder
import dev.quwac.kagiroi.sql.bindMerge
import dev.quwac.kagiroi.sql.sqlServerMerge

/** Builds a native SqlServer MERGE from a typed aliased table or derived query. */
fun mergeInto(target: Table, source: RelationSource, on: SqlPredicate, block: SqlServerMergeBuilder.() -> Unit) =
    sqlServerMerge(target, source, on, block)

/** Builds a native SqlServer MERGE bound to this execution target. */
fun SqlExecution.mergeInto(
    target: Table,
    source: RelationSource,
    on: SqlPredicate,
    block: SqlServerMergeBuilder.() -> Unit,
) = bindMerge(sqlServerMerge(target, source, on, block))

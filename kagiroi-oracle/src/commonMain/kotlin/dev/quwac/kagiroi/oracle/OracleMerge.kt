package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.RelationSource
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.OracleMergeBuilder
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.SqlPredicate
import dev.quwac.kagiroi.sql.bindMerge
import dev.quwac.kagiroi.sql.oracleMerge

/** Builds a native Oracle MERGE from a typed aliased table or derived query. */
fun mergeInto(target: Table, source: RelationSource, on: SqlPredicate, block: OracleMergeBuilder.() -> Unit) =
    oracleMerge(target, source, on, block)

/** Builds a native Oracle MERGE bound to this execution target. */
fun SqlExecution.mergeInto(
    target: Table,
    source: RelationSource,
    on: SqlPredicate,
    block: OracleMergeBuilder.() -> Unit,
) = bindMerge(oracleMerge(target, source, on, block))

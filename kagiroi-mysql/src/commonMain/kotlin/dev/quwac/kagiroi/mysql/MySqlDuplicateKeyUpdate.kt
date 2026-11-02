package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.sql.ExecutableInsertQuery
import dev.quwac.kagiroi.sql.InsertQuery
import dev.quwac.kagiroi.sql.InsertQueryState
import dev.quwac.kagiroi.sql.InsertedConflictUpdateBuilder
import dev.quwac.kagiroi.sql.withDuplicateKeyUpdate

/**
 * Adds MySQL `ON DUPLICATE KEY UPDATE` to a completed typed INSERT.
 *
 * MySQL chooses whichever primary or unique key is duplicated; this API intentionally accepts no
 * conflict target. Proposed row references render through a renderer-owned non-deprecated alias.
 */
fun <S> InsertQuery<S>.onDuplicateKeyUpdate(
    block: InsertedConflictUpdateBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable = withDuplicateKeyUpdate(block)

/** Execution-bound MySQL `ON DUPLICATE KEY UPDATE` extension. */
fun <S> ExecutableInsertQuery<S>.onDuplicateKeyUpdate(
    block: InsertedConflictUpdateBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable = withDuplicateKeyUpdate(block)

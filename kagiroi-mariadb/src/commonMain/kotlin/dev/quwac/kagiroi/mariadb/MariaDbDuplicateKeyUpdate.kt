package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.sql.ExecutableInsertQuery
import dev.quwac.kagiroi.sql.InsertQuery
import dev.quwac.kagiroi.sql.InsertQueryState
import dev.quwac.kagiroi.sql.InsertedConflictUpdateBuilder
import dev.quwac.kagiroi.sql.withDuplicateKeyUpdate

/**
 * Adds MariaDB `ON DUPLICATE KEY UPDATE` to a completed typed INSERT.
 *
 * MariaDB chooses the first matching primary or unique index; this API intentionally accepts no
 * conflict target. Proposed row references render as MariaDB's native `VALUES(column)` form.
 */
fun <S> InsertQuery<S>.onDuplicateKeyUpdate(
    block: InsertedConflictUpdateBuilder.() -> Unit,
): InsertQuery<S>
    where S : InsertQueryState.Renderable = withDuplicateKeyUpdate(block)

/** Execution-bound MariaDB `ON DUPLICATE KEY UPDATE` extension. */
fun <S> ExecutableInsertQuery<S>.onDuplicateKeyUpdate(
    block: InsertedConflictUpdateBuilder.() -> Unit,
): ExecutableInsertQuery<S>
    where S : InsertQueryState.Renderable = withDuplicateKeyUpdate(block)

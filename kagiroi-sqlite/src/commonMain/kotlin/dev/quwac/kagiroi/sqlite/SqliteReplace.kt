package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.WritableColumn
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.asNativeReplace
import dev.quwac.kagiroi.sql.insertInto

/** Starts a native REPLACE statement. */
fun replaceInto(table: Table) = insertInto(table).asNativeReplace()

/** Starts a native REPLACE ... SELECT with explicit target columns. */
fun replaceInto(table: Table, column: WritableColumn<*>, vararg columns: WritableColumn<*>) =
    insertInto(table, column, *columns).asNativeReplace()

/** Starts an execution-bound native REPLACE statement. */
fun SqlExecution.replaceInto(table: Table) = insertInto(table).asNativeReplace()

/** Starts an execution-bound native REPLACE ... SELECT. */
fun SqlExecution.replaceInto(table: Table, column: WritableColumn<*>, vararg columns: WritableColumn<*>) =
    insertInto(table, column, *columns).asNativeReplace()

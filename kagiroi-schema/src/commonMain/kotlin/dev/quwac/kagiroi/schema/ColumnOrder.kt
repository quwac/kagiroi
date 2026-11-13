package dev.quwac.kagiroi.schema

/**
 * Sets this column's ordering key in [Table.columns], schema exports, and CREATE TABLE.
 * Unspecified keys use zero-based declaration positions. Equal keys retain declaration order.
 * Negative values are allowed. This modifier does not rearrange an existing database table.
 */
fun <T> WritableColumn<T>.order(value: Int): WritableColumn<T> = withDefinition(definition.copy(order = value))

/** Sets a foreign key's column ordering key while retaining its constraint modifiers. */
fun <T> ForeignKeyColumn<T>.order(value: Int): ForeignKeyColumn<T> = withDefinition(definition.copy(order = value))

/** Sets a logical computed column's ordering key while keeping it read-only. */
fun <T> ComputedColumn<T>.order(value: Int): ComputedColumn<T> = withDefinition(definition.copy(order = value))

/** Sets a physical generated column's ordering key while keeping it read-only. */
fun <T> GeneratedColumn<T>.order(value: Int): GeneratedColumn<T> = withDefinition(definition.copy(order = value))

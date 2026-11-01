package dev.quwac.kagiroi.sqlite

import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.currentInstant
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.defaultExpression
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nextValue
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.sequence
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.createSequence
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.dropSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultSequenceDdlTest {
    @Test
    fun rendersLiteralDefaultsAndSetDefaultForeignKeys() {
        assertEquals(
            "CREATE TABLE \"default_child\" (\"parent_id\" INTEGER DEFAULT 0 NOT NULL, " +
                "FOREIGN KEY (\"parent_id\") REFERENCES \"default_parent\" (\"id\") " +
                "ON DELETE SET DEFAULT ON UPDATE SET DEFAULT)",
            SqliteDialect.render(createTable(SqliteDefaultChild)).sql,
        )
        assertEquals(
            "CREATE TABLE \"computed_default_rows\" (\"id\" INTEGER DEFAULT (10 + 1) NOT NULL)",
            SqliteDialect.render(createTable(SqliteComputedDefaultRows)).sql,
        )
        assertEquals(
            "CREATE TABLE \"current_instant_rows\" (\"created_at\" TEXT DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')) NOT NULL)",
            SqliteDialect.render(createTable(SqliteCurrentInstantRows)).sql,
        )
        assertEquals(
            "CREATE TABLE \"function_default_rows\" (\"normalized\" TEXT DEFAULT (LOWER('DEFAULT')) NOT NULL)",
            SqliteDialect.render(createTable(SqliteFunctionDefaultRows)).sql,
        )
    }

    @Test
    fun rejectsIndependentSequencesAndSequenceDefaults() {
        assertFailsWith<UnsupportedDdlOperation> { SqliteDialect.render(createSequence(SqliteNumbers)) }
        assertFailsWith<UnsupportedDdlOperation> { SqliteDialect.render(dropSequence(SqliteNumbers)) }
        assertFailsWith<UnsupportedDdlOperation> { SqliteDialect.render(createTable(SqliteSequenceDefaultRows)) }
    }
}

private object SqliteDefaultParent : Table("default_parent") {
    val id = long("id").primaryKey()
}

private object SqliteDefaultChild : Table("default_child") {
    val parentId = long("parent_id").default(0L)

    init {
        foreignKey(parentId).references(SqliteDefaultParent.id)
            .onDelete(ForeignKeyAction.SetDefault)
            .onUpdate(ForeignKeyAction.SetDefault)
    }
}

private val SqliteNumbers = sequence("default_numbers")

private object SqliteSequenceDefaultRows : Table("sequence_default_rows") {
    val id = long("id").default(SqliteNumbers.nextValue())
}

private object SqliteComputedDefaultRows : Table("computed_default_rows") {
    val id = long("id").default(defaultExpression(10L) + 1L)
}

private object SqliteCurrentInstantRows : Table("current_instant_rows") {
    val createdAt = instant("created_at").default(currentInstant())
}

private object SqliteFunctionDefaultRows : Table("function_default_rows") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

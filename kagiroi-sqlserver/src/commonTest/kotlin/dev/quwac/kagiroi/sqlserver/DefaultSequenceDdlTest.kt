package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.currentInstant
import dev.quwac.kagiroi.schema.default
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
    fun rendersLiteralDefaultsSetDefaultForeignKeysAndSequences() {
        assertEquals(
            "CREATE TABLE [default_child] ([parent_id] BIGINT DEFAULT 0 NOT NULL, " +
                "FOREIGN KEY ([parent_id]) REFERENCES [default_parent] ([id]) " +
                "ON DELETE SET DEFAULT ON UPDATE SET DEFAULT)",
            SqlServerDialect.render(createTable(SqlServerDefaultChild)).sql,
        )
        assertEquals("CREATE SEQUENCE [default_numbers]", SqlServerDialect.render(createSequence(SqlServerNumbers)).sql)
        assertEquals("DROP SEQUENCE [default_numbers]", SqlServerDialect.render(dropSequence(SqlServerNumbers)).sql)
        assertEquals(
            "CREATE TABLE [sequence_default_rows] ([id] BIGINT DEFAULT (NEXT VALUE FOR [default_numbers] + 10) NOT NULL)",
            SqlServerDialect.render(createTable(SqlServerSequenceDefaultRows)).sql,
        )
        assertEquals(
            "SELECT NEXT VALUE FOR [default_numbers] AS kagiroi_sequence_value",
            SqlServerDialect.renderNextValue(SqlServerNumbers).sql,
        )
        assertEquals(
            "CREATE TABLE [current_instant_rows] ([created_at] DATETIMEOFFSET(7) DEFAULT (SYSDATETIMEOFFSET()) NOT NULL)",
            SqlServerDialect.render(createTable(SqlServerCurrentInstantRows)).sql,
        )
        assertEquals(
            "CREATE TABLE [function_default_rows] ([normalized] NVARCHAR(MAX) DEFAULT (LOWER(N'DEFAULT')) NOT NULL)",
            SqlServerDialect.render(createTable(SqlServerFunctionDefaultRows)).sql,
        )
    }

    @Test
    fun rejectsIfExistsSequenceFormsOutsideTheMinimumSqlServerBaseline() {
        assertFailsWith<UnsupportedDdlOperation> {
            SqlServerDialect.render(
                createSequence(SqlServerNumbers, ifNotExists = true),
            )
        }
        assertFailsWith<UnsupportedDdlOperation> {
            SqlServerDialect.render(
                dropSequence(SqlServerNumbers, ifExists = true),
            )
        }
    }
}

private object SqlServerDefaultParent : Table("default_parent") {
    val id = long("id").primaryKey()
}

private object SqlServerDefaultChild : Table("default_child") {
    val parentId = long("parent_id").default(0L)

    init {
        foreignKey(parentId).references(SqlServerDefaultParent.id)
            .onDelete(ForeignKeyAction.SetDefault)
            .onUpdate(ForeignKeyAction.SetDefault)
    }
}

private val SqlServerNumbers = sequence("default_numbers")

private object SqlServerSequenceDefaultRows : Table("sequence_default_rows") {
    val id = long("id").default(SqlServerNumbers.nextValue() + 10L)
}

private object SqlServerCurrentInstantRows : Table("current_instant_rows") {
    val createdAt = instant("created_at").default(currentInstant())
}

private object SqlServerFunctionDefaultRows : Table("function_default_rows") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

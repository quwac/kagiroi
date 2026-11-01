package dev.quwac.kagiroi.oracle

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
    fun rendersLiteralDefaultsAndSequences() {
        assertEquals(
            "CREATE TABLE \"default_child\" (\"parent_id\" NUMBER(19,0) DEFAULT 0 NOT NULL)",
            OracleDialect.render(createTable(OracleDefaultChild)).sql,
        )
        assertEquals("CREATE SEQUENCE \"default_numbers\"", OracleDialect.render(createSequence(OracleNumbers)).sql)
        assertEquals("DROP SEQUENCE \"default_numbers\"", OracleDialect.render(dropSequence(OracleNumbers)).sql)
        assertEquals(
            "CREATE TABLE \"sequence_default_rows\" (\"id\" NUMBER(19,0) DEFAULT (\"default_numbers\".NEXTVAL + 10) NOT NULL)",
            OracleDialect.render(createTable(OracleSequenceDefaultRows)).sql,
        )
        assertEquals(
            "SELECT \"default_numbers\".NEXTVAL AS kagiroi_sequence_value FROM DUAL",
            OracleDialect.renderNextValue(OracleNumbers).sql,
        )
        assertEquals(
            "CREATE TABLE \"current_instant_rows\" (\"created_at\" TIMESTAMP(9) WITH TIME ZONE DEFAULT (SYSTIMESTAMP) NOT NULL)",
            OracleDialect.render(createTable(OracleCurrentInstantRows)).sql,
        )
        assertEquals(
            "CREATE TABLE \"function_default_rows\" (\"normalized\" VARCHAR2(4000 CHAR) DEFAULT LOWER('DEFAULT') NOT NULL)",
            OracleDialect.render(createTable(OracleFunctionDefaultRows)).sql,
        )
    }

    @Test
    fun rejectsSetDefaultAndIfExistsSequenceForms() {
        assertFailsWith<UnsupportedDdlOperation> { OracleDialect.render(createTable(OracleSetDefaultChild)) }
        assertFailsWith<UnsupportedDdlOperation> {
            OracleDialect.render(
                createSequence(OracleNumbers, ifNotExists = true),
            )
        }
        assertFailsWith<UnsupportedDdlOperation> { OracleDialect.render(dropSequence(OracleNumbers, ifExists = true)) }
    }
}

private object OracleDefaultParent : Table("default_parent") {
    val id = long("id").primaryKey()
}

private object OracleDefaultChild : Table("default_child") {
    val parentId = long("parent_id").default(0L)
}

private object OracleSetDefaultChild : Table("set_default_child") {
    val parentId = long("parent_id").default(0L)

    init {
        foreignKey(parentId).references(OracleDefaultParent.id).onDelete(ForeignKeyAction.SetDefault)
    }
}

private val OracleNumbers = sequence("default_numbers")

private object OracleSequenceDefaultRows : Table("sequence_default_rows") {
    val id = long("id").default(OracleNumbers.nextValue() + 10L)
}

private object OracleCurrentInstantRows : Table("current_instant_rows") {
    val createdAt = instant("created_at").default(currentInstant())
}

private object OracleFunctionDefaultRows : Table("function_default_rows") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

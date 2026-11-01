package dev.quwac.kagiroi.mysql

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultSequenceDdlTest {
    @Test
    fun rendersLiteralDefaults() {
        assertEquals(
            "CREATE TABLE `default_child` (`parent_id` BIGINT DEFAULT 0 NOT NULL)",
            MySqlDialect.render(createTable(MySqlDefaultChild)).sql,
        )
        assertEquals(
            "CREATE TABLE `computed_default_rows` (`id` BIGINT DEFAULT (10 + 1) NOT NULL)",
            MySqlDialect.render(createTable(MySqlComputedDefaultRows)).sql,
        )
        assertEquals(
            "CREATE TABLE `function_default_rows` (`normalized` TEXT DEFAULT (LOWER('DEFAULT')) NOT NULL)",
            MySqlDialect.render(createTable(MySqlFunctionDefaultRows)).sql,
        )
    }

    @Test
    fun rejectsSetDefaultAndIndependentSequences() {
        assertFailsWith<UnsupportedDdlOperation> { MySqlDialect.render(createTable(MySqlSetDefaultChild)) }
        assertFailsWith<UnsupportedDdlOperation> { MySqlDialect.render(createSequence(MySqlNumbers)) }
        assertFailsWith<UnsupportedDdlOperation> { MySqlDialect.render(createTable(MySqlSequenceDefaultRows)) }
        assertFailsWith<UnsupportedDdlOperation> { MySqlDialect.render(createTable(MySqlCurrentInstantRows)) }
    }
}

private object MySqlDefaultParent : Table("default_parent") {
    val id = long("id").primaryKey()
}

private object MySqlDefaultChild : Table("default_child") {
    val parentId = long("parent_id").default(0L)
}

private object MySqlSetDefaultChild : Table("set_default_child") {
    val parentId = long("parent_id").default(0L)

    init {
        foreignKey(parentId).references(MySqlDefaultParent.id).onDelete(ForeignKeyAction.SetDefault)
    }
}

private val MySqlNumbers = sequence("default_numbers")

private object MySqlSequenceDefaultRows : Table("sequence_default_rows") {
    val id = long("id").default(MySqlNumbers.nextValue())
}

private object MySqlComputedDefaultRows : Table("computed_default_rows") {
    val id = long("id").default(defaultExpression(10L) + 1L)
}

private object MySqlCurrentInstantRows : Table("current_instant_rows") {
    val createdAt = instant("created_at").default(currentInstant())
}

private object MySqlFunctionDefaultRows : Table("function_default_rows") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

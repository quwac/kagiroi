package dev.quwac.kagiroi.mariadb

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
            "CREATE TABLE `default_child` (`parent_id` BIGINT DEFAULT 0 NOT NULL)",
            MariaDbDialect.render(createTable(MariaDbDefaultChild)).sql,
        )
        assertEquals(
            "CREATE SEQUENCE IF NOT EXISTS `default_numbers`",
            MariaDbDialect.render(
                createSequence(MariaDbNumbers, ifNotExists = true),
            ).sql,
        )
        assertEquals(
            "DROP SEQUENCE IF EXISTS `default_numbers`",
            MariaDbDialect.render(
                dropSequence(MariaDbNumbers, ifExists = true),
            ).sql,
        )
        assertEquals(
            "CREATE TABLE `sequence_default_rows` (`id` BIGINT DEFAULT (nextval(`default_numbers`) + 10) NOT NULL)",
            MariaDbDialect.render(createTable(MariaDbSequenceDefaultRows)).sql,
        )
        assertEquals(
            "SELECT NEXT VALUE FOR `default_numbers` AS kagiroi_sequence_value",
            MariaDbDialect.renderNextValue(MariaDbNumbers).sql,
        )
        assertEquals(
            "CREATE TABLE `current_instant_rows` (`created_at` DATETIME(6) DEFAULT (UTC_TIMESTAMP(6)) NOT NULL)",
            MariaDbDialect.render(createTable(MariaDbCurrentInstantRows)).sql,
        )
        assertEquals(
            "CREATE TABLE `function_default_rows` (`normalized` TEXT DEFAULT (LOWER('DEFAULT')) NOT NULL)",
            MariaDbDialect.render(createTable(MariaDbFunctionDefaultRows)).sql,
        )
    }

    @Test
    fun rejectsSetDefaultForInnoDbForeignKeys() {
        assertFailsWith<UnsupportedDdlOperation> { MariaDbDialect.render(createTable(MariaDbSetDefaultChild)) }
    }
}

private object MariaDbDefaultParent : Table("default_parent") {
    val id = long("id").primaryKey()
}

private object MariaDbDefaultChild : Table("default_child") {
    val parentId = long("parent_id").default(0L)
}

private object MariaDbSetDefaultChild : Table("set_default_child") {
    val parentId = long("parent_id").default(0L)

    init {
        foreignKey(parentId).references(MariaDbDefaultParent.id).onDelete(ForeignKeyAction.SetDefault)
    }
}

private val MariaDbNumbers = sequence("default_numbers")

private object MariaDbSequenceDefaultRows : Table("sequence_default_rows") {
    val id = long("id").default(MariaDbNumbers.nextValue() + 10L)
}

private object MariaDbCurrentInstantRows : Table("current_instant_rows") {
    val createdAt = instant("created_at").default(currentInstant())
}

private object MariaDbFunctionDefaultRows : Table("function_default_rows") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

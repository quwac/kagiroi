package dev.quwac.kagiroi.postgres

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
import dev.quwac.kagiroi.sql.createSequence
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.dropSequence
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultSequenceDdlTest {
    @Test
    fun rendersLiteralDefaultsSetDefaultForeignKeysAndSequences() {
        assertEquals(
            "CREATE TABLE \"default_child\" (\"parent_id\" BIGINT DEFAULT 0 NOT NULL, " +
                "FOREIGN KEY (\"parent_id\") REFERENCES \"default_parent\" (\"id\") " +
                "ON DELETE SET DEFAULT ON UPDATE SET DEFAULT)",
            PostgresDialect.render(createTable(PostgresDefaultChild)).sql,
        )
        assertEquals(
            "CREATE SEQUENCE IF NOT EXISTS \"default_numbers\"",
            PostgresDialect.render(
                createSequence(PostgresNumbers, ifNotExists = true),
            ).sql,
        )
        assertEquals(
            "DROP SEQUENCE IF EXISTS \"default_numbers\"",
            PostgresDialect.render(
                dropSequence(PostgresNumbers, ifExists = true),
            ).sql,
        )
        assertEquals(
            "CREATE TABLE \"sequence_default_rows\" (\"id\" BIGINT DEFAULT (nextval('\"default_numbers\"'::regclass) + 10) NOT NULL)",
            PostgresDialect.render(createTable(PostgresSequenceDefaultRows)).sql,
        )
        assertEquals(
            "SELECT nextval('\"default_numbers\"'::regclass) AS kagiroi_sequence_value",
            PostgresDialect.renderNextValue(PostgresNumbers).sql,
        )
        assertEquals(
            "CREATE TABLE \"current_instant_rows\" (\"created_at\" TIMESTAMP WITH TIME ZONE DEFAULT (CURRENT_TIMESTAMP) NOT NULL)",
            PostgresDialect.render(createTable(PostgresCurrentInstantRows)).sql,
        )
        assertEquals(
            "CREATE TABLE \"function_default_rows\" (\"normalized\" TEXT DEFAULT LOWER('DEFAULT') NOT NULL)",
            PostgresDialect.render(createTable(PostgresFunctionDefaultRows)).sql,
        )
    }
}

private object PostgresDefaultParent : Table("default_parent") {
    val id = long("id").primaryKey()
}

private object PostgresDefaultChild : Table("default_child") {
    val parentId = long("parent_id").default(0L)

    init {
        foreignKey(parentId).references(PostgresDefaultParent.id)
            .onDelete(ForeignKeyAction.SetDefault)
            .onUpdate(ForeignKeyAction.SetDefault)
    }
}

private val PostgresNumbers = sequence("default_numbers")

private object PostgresSequenceDefaultRows : Table("sequence_default_rows") {
    val id = long("id").default(PostgresNumbers.nextValue() + 10L)
}

private object PostgresCurrentInstantRows : Table("current_instant_rows") {
    val createdAt = instant("created_at").default(currentInstant())
}

private object PostgresFunctionDefaultRows : Table("function_default_rows") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

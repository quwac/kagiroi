package dev.quwac.kagiroi.postgres.node

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlin.test.assertEquals

internal suspend fun verifyConfiguredIdentity(database: NodePostgresDatabase) {
    database.execute(createTable(ConfiguredIdentityRows))
    repeat(IDENTITY_INSERT_COUNT) {
        database.insertInto(ConfiguredIdentityRows).values {
            set(ConfiguredIdentityRows.name, "row-$it")
        }.execute()
    }

    val ids = database.select(ConfiguredIdentityRows.id)
        .from(ConfiguredIdentityRows)
        .orderBy(ConfiguredIdentityRows.id.asc())
        .all()
        .map { it[ConfiguredIdentityRows.id] }
    val expectedIds = List(IDENTITY_INSERT_COUNT) { index ->
        IDENTITY_START + index.toLong() * IDENTITY_INCREMENT
    }
    assertEquals(expectedIds, ids)
}

internal object ConfiguredIdentityRows : Table("kagiroi_node_postgres_configured_identity") {
    val id = long("id").primaryKey().identity(startWith = IDENTITY_START, incrementBy = IDENTITY_INCREMENT)
    val name = string("name")
}

private const val IDENTITY_START = 100L
private const val IDENTITY_INCREMENT = 10L
private const val IDENTITY_INSERT_COUNT = 3

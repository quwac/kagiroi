package dev.quwac.kagiroi.mysql.node

import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlin.test.assertEquals
import dev.quwac.kagiroi.mariadb.onDuplicateKeyUpdate as mariaDbDuplicateKeyUpdate
import dev.quwac.kagiroi.mariadb.replaceInto as mariaDbReplaceInto
import dev.quwac.kagiroi.mysql.onDuplicateKeyUpdate as mySqlDuplicateKeyUpdate
import dev.quwac.kagiroi.mysql.replaceInto as mySqlReplaceInto

/** Real mysql2-driver contract; each server keeps its own native proposed-row syntax. */
@Suppress("LongMethod")
internal suspend fun verifyMySqlDuplicateKeyUpdate(database: NodeMySqlDatabase) {
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mysql_duplicate_source")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mysql_duplicate_rows")
        database.execute(createTable(MySqlDuplicateRows))
        database.execute(createTable(MySqlDuplicateSource))
        database.insertInto(MySqlDuplicateRows).values {
            set(MySqlDuplicateRows.id, 1)
            set(MySqlDuplicateRows.email, "ada@example.test")
            set(MySqlDuplicateRows.name, "Ada")
            set(MySqlDuplicateRows.count, 1)
        }.execute()

        assertEquals(
            1L,
            database.insertInto(MySqlDuplicateRows).values {
                set(MySqlDuplicateRows.id, 2)
                set(MySqlDuplicateRows.email, "lin@example.test")
                set(MySqlDuplicateRows.name, "Lin")
                set(MySqlDuplicateRows.count, 1)
            }.mySqlDuplicateKeyUpdate {
                set(MySqlDuplicateRows.name, inserted(MySqlDuplicateRows.name))
            }.execute().affectedRows,
        )
        assertEquals(
            2L,
            database.insertInto(MySqlDuplicateRows).values {
                set(MySqlDuplicateRows.id, 3)
                set(MySqlDuplicateRows.email, "ada@example.test")
                set(MySqlDuplicateRows.name, "Grace")
                set(MySqlDuplicateRows.count, 2)
            }.mySqlDuplicateKeyUpdate {
                set(MySqlDuplicateRows.name, inserted(MySqlDuplicateRows.name))
                set(MySqlDuplicateRows.count, MySqlDuplicateRows.count + inserted(MySqlDuplicateRows.count))
            }.execute().affectedRows,
        )
        assertEquals(
            1L,
            database.insertInto(MySqlDuplicateRows).values {
                set(MySqlDuplicateRows.id, 3)
                set(MySqlDuplicateRows.email, "ada@example.test")
                set(MySqlDuplicateRows.name, "Grace")
                set(MySqlDuplicateRows.count, 0)
            }.mySqlDuplicateKeyUpdate {
                set(MySqlDuplicateRows.name, inserted(MySqlDuplicateRows.name))
                set(MySqlDuplicateRows.count, MySqlDuplicateRows.count + inserted(MySqlDuplicateRows.count))
            }.execute().affectedRows,
        )
        val ada = database.select(MySqlDuplicateRows.name, MySqlDuplicateRows.count)
            .from(MySqlDuplicateRows).all().single { row -> row[MySqlDuplicateRows.name] == "Grace" }
        assertEquals(3, ada[MySqlDuplicateRows.count])

        database.insertInto(MySqlDuplicateSource).values {
            set(MySqlDuplicateSource.id, 4)
            set(MySqlDuplicateSource.email, "source@example.test")
            set(MySqlDuplicateSource.name, "Source")
            set(MySqlDuplicateSource.count, 4)
        }.execute()
        assertEquals(
            1L,
            database.insertInto(
                MySqlDuplicateRows,
                MySqlDuplicateRows.id,
                MySqlDuplicateRows.email,
                MySqlDuplicateRows.name,
                MySqlDuplicateRows.count,
            ).from(
                select(
                    MySqlDuplicateSource.id,
                    MySqlDuplicateSource.email,
                    MySqlDuplicateSource.name,
                    MySqlDuplicateSource.count,
                ).from(MySqlDuplicateSource),
            ).mySqlDuplicateKeyUpdate {
                set(MySqlDuplicateRows.name, inserted(MySqlDuplicateRows.name))
            }.execute().affectedRows,
        )
        database.mySqlReplaceInto(MySqlDuplicateRows).values {
            set(MySqlDuplicateRows.id, 10)
            set(MySqlDuplicateRows.email, "ada@example.test")
            set(MySqlDuplicateRows.name, "Replacement")
            set(MySqlDuplicateRows.count, 8)
        }.execute()
        val replacement = database.select(MySqlDuplicateRows.id, MySqlDuplicateRows.name)
            .from(MySqlDuplicateRows).all().single { it[MySqlDuplicateRows.name] == "Replacement" }
        assertEquals(10, replacement[MySqlDuplicateRows.id])
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mysql_duplicate_source")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mysql_duplicate_rows")
        database.close()
    }
}

/** Real MariaDB contract, including its documented `VALUES(column)` proposed-row semantics. */
@Suppress("LongMethod")
internal suspend fun verifyMariaDbDuplicateKeyUpdate(database: NodeMySqlDatabase) {
    try {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_duplicate_source")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_duplicate_rows")
        database.execute(createTable(MariaDbDuplicateRows))
        database.execute(createTable(MariaDbDuplicateSource))
        database.insertInto(MariaDbDuplicateRows).values {
            set(MariaDbDuplicateRows.id, 1)
            set(MariaDbDuplicateRows.email, "ada@example.test")
            set(MariaDbDuplicateRows.name, "Ada")
            set(MariaDbDuplicateRows.count, 1)
        }.execute()

        assertEquals(
            1L,
            database.insertInto(MariaDbDuplicateRows).values {
                set(MariaDbDuplicateRows.id, 2)
                set(MariaDbDuplicateRows.email, "lin@example.test")
                set(MariaDbDuplicateRows.name, "Lin")
                set(MariaDbDuplicateRows.count, 1)
            }.mariaDbDuplicateKeyUpdate {
                set(MariaDbDuplicateRows.name, inserted(MariaDbDuplicateRows.name))
            }.execute().affectedRows,
        )
        assertEquals(
            2L,
            database.insertInto(MariaDbDuplicateRows).values {
                set(MariaDbDuplicateRows.id, 3)
                set(MariaDbDuplicateRows.email, "ada@example.test")
                set(MariaDbDuplicateRows.name, "Grace")
                set(MariaDbDuplicateRows.count, 2)
            }.mariaDbDuplicateKeyUpdate {
                set(MariaDbDuplicateRows.name, inserted(MariaDbDuplicateRows.name))
                set(MariaDbDuplicateRows.count, MariaDbDuplicateRows.count + inserted(MariaDbDuplicateRows.count))
            }.execute().affectedRows,
        )
        assertEquals(
            1L,
            database.insertInto(MariaDbDuplicateRows).values {
                set(MariaDbDuplicateRows.id, 3)
                set(MariaDbDuplicateRows.email, "ada@example.test")
                set(MariaDbDuplicateRows.name, "Grace")
                set(MariaDbDuplicateRows.count, 0)
            }.mariaDbDuplicateKeyUpdate {
                set(MariaDbDuplicateRows.name, inserted(MariaDbDuplicateRows.name))
                set(MariaDbDuplicateRows.count, MariaDbDuplicateRows.count + inserted(MariaDbDuplicateRows.count))
            }.execute().affectedRows,
        )
        val ada = database.select(MariaDbDuplicateRows.name, MariaDbDuplicateRows.count)
            .from(MariaDbDuplicateRows).all().single { row -> row[MariaDbDuplicateRows.name] == "Grace" }
        assertEquals(3, ada[MariaDbDuplicateRows.count])

        database.insertInto(MariaDbDuplicateSource).values {
            set(MariaDbDuplicateSource.id, 4)
            set(MariaDbDuplicateSource.email, "source@example.test")
            set(MariaDbDuplicateSource.name, "Source")
            set(MariaDbDuplicateSource.count, 4)
        }.execute()
        assertEquals(
            1L,
            database.insertInto(
                MariaDbDuplicateRows,
                MariaDbDuplicateRows.id,
                MariaDbDuplicateRows.email,
                MariaDbDuplicateRows.name,
                MariaDbDuplicateRows.count,
            ).from(
                select(
                    MariaDbDuplicateSource.id,
                    MariaDbDuplicateSource.email,
                    MariaDbDuplicateSource.name,
                    MariaDbDuplicateSource.count,
                ).from(MariaDbDuplicateSource),
            ).mariaDbDuplicateKeyUpdate {
                set(MariaDbDuplicateRows.name, inserted(MariaDbDuplicateRows.name))
            }.execute().affectedRows,
        )
        database.mariaDbReplaceInto(MariaDbDuplicateRows).values {
            set(MariaDbDuplicateRows.id, 10)
            set(MariaDbDuplicateRows.email, "ada@example.test")
            set(MariaDbDuplicateRows.name, "Replacement")
            set(MariaDbDuplicateRows.count, 8)
        }.execute()
        val replacement = database.select(MariaDbDuplicateRows.id, MariaDbDuplicateRows.name)
            .from(MariaDbDuplicateRows).all().single { it[MariaDbDuplicateRows.name] == "Replacement" }
        assertEquals(10, replacement[MariaDbDuplicateRows.id])
    } finally {
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_duplicate_source")
        database.executeRaw("DROP TABLE IF EXISTS kagiroi_mariadb_duplicate_rows")
        database.close()
    }
}

private val duplicateEmailType = MySqlTypes.varchar(255)

private object MySqlDuplicateRows : Table("kagiroi_mysql_duplicate_rows") {
    val id = int("id").primaryKey()
    val email = column("email", duplicateEmailType).unique()
    val name = string("name")
    val count = int("count")
}

private object MySqlDuplicateSource : Table("kagiroi_mysql_duplicate_source") {
    val id = int("id").primaryKey()
    val email = column("email", duplicateEmailType)
    val name = string("name")
    val count = int("count")
}

private object MariaDbDuplicateRows : Table("kagiroi_mariadb_duplicate_rows") {
    val id = int("id").primaryKey()
    val email = column("email", duplicateEmailType).unique()
    val name = string("name")
    val count = int("count")
}

private object MariaDbDuplicateSource : Table("kagiroi_mariadb_duplicate_source") {
    val id = int("id").primaryKey()
    val email = column("email", duplicateEmailType)
    val name = string("name")
    val count = int("count")
}

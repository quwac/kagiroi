package dev.quwac.kagiroi.mysql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.with
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MySqlDuplicateKeyUpdateTest {
    private val renderer = SqlRenderer(MySqlDialect)

    @Test
    fun rendersValuesRowAliasWithoutDeprecatedValuesFunction() {
        val query = insertInto(MySqlUsers).values {
            set(MySqlUsers.id, 1L)
            set(MySqlUsers.email, "ada@example.test")
            set(MySqlUsers.name, "Ada")
            set(MySqlUsers.count, 2L)
        }.onDuplicateKeyUpdate {
            set(MySqlUsers.name, inserted(MySqlUsers.name))
            set(MySqlUsers.count, MySqlUsers.count + inserted(MySqlUsers.count))
        }

        val rendered = renderer.render(query)

        assertEquals(
            "INSERT INTO `app`.`users` (`id`, `email`, `name`, `count`) VALUES (?, ?, ?, ?) AS `kagiroi_inserted` " +
                "ON DUPLICATE KEY UPDATE `name` = `kagiroi_inserted`.`name`, " +
                "`count` = (`app`.`users`.`count` + `kagiroi_inserted`.`count`)",
            rendered.sql,
        )
        assertFalse(rendered.sql.contains("VALUES("))
        assertEquals(listOf(1L, "ada@example.test", "Ada", 2L), rendered.parameters.map { it.value })
    }

    @Test
    fun wrapsInsertSelectInDerivedSourceForInsertedReferences() {
        val query = insertInto(MySqlUsers, MySqlUsers.id, MySqlUsers.email, MySqlUsers.name, MySqlUsers.count)
            .from(select(MySqlArchive.id, MySqlArchive.email, MySqlArchive.name, MySqlArchive.count).from(MySqlArchive))
            .onDuplicateKeyUpdate {
                set(MySqlUsers.name, inserted(MySqlUsers.name))
                set(MySqlUsers.count, inserted(MySqlUsers.count))
            }

        assertEquals(
            "INSERT INTO `app`.`users` (`id`, `email`, `name`, `count`) SELECT * FROM " +
                "(SELECT `app`.`archive`.`id`, `app`.`archive`.`email`, `app`.`archive`.`name`, " +
                "`app`.`archive`.`count` FROM `app`.`archive`) AS `kagiroi_inserted` " +
                "ON DUPLICATE KEY UPDATE `name` = `kagiroi_inserted`.`name`, " +
                "`count` = `kagiroi_inserted`.`count`",
            renderer.render(query).sql,
        )
    }

    @Test
    fun retainsNativeMultiRowInsertInsteadOfSplittingStatements() {
        val query = insertInto(MySqlUsers).values {
            set(MySqlUsers.id, 1L)
            set(MySqlUsers.email, "one@example.test")
        }.values {
            set(MySqlUsers.id, 2L)
            set(MySqlUsers.email, "two@example.test")
        }.onDuplicateKeyUpdate {
            set(MySqlUsers.name, inserted(MySqlUsers.name))
        }

        assertEquals(
            "INSERT INTO `app`.`users` (`id`, `email`) VALUES (?, ?), (?, ?) AS `kagiroi_inserted` " +
                "ON DUPLICATE KEY UPDATE `name` = `kagiroi_inserted`.`name`",
            renderer.render(query).sql,
        )
    }

    @Test
    fun retainsMySqlCtePlacementForInsertSelectDuplicateKeyUpdate() {
        val staged = select(MySqlArchive.id, MySqlArchive.email, MySqlArchive.name, MySqlArchive.count)
            .from(MySqlArchive)
            .asCte("staged_users")
        val query = with(staged) {
            insertInto(MySqlUsers, MySqlUsers.id, MySqlUsers.email, MySqlUsers.name, MySqlUsers.count)
                .from(
                    select(
                        staged[MySqlArchive.id],
                        staged[MySqlArchive.email],
                        staged[MySqlArchive.name],
                        staged[MySqlArchive.count],
                    ).from(staged),
                )
        }.onDuplicateKeyUpdate {
            set(MySqlUsers.name, inserted(MySqlUsers.name))
        }

        val sql = renderer.render(query).sql
        assertFalse(sql.contains("VALUES("))
        assertEquals(
            "INSERT INTO `app`.`users` (`id`, `email`, `name`, `count`) WITH `staged_users` AS " +
                "(SELECT `app`.`archive`.`id`, `app`.`archive`.`email`, `app`.`archive`.`name`, " +
                "`app`.`archive`.`count` FROM `app`.`archive`) SELECT * FROM (SELECT " +
                "`staged_users`.`id`, `staged_users`.`email`, `staged_users`.`name`, " +
                "`staged_users`.`count` FROM `staged_users`) AS `kagiroi_inserted` " +
                "ON DUPLICATE KEY UPDATE `name` = `kagiroi_inserted`.`name`",
            sql,
        )
    }
}

private object MySqlUsers : Table("users", schemaName = "app") {
    val id = long("id")
    val email = string("email")
    val name = string("name")
    val count = long("count")
}

private object MySqlArchive : Table("archive", schemaName = "app") {
    val id = long("id")
    val email = string("email")
    val name = string("name")
    val count = long("count")
}

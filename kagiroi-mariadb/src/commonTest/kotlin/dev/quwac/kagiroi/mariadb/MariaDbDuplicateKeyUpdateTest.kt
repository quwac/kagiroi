package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import kotlin.test.Test
import kotlin.test.assertEquals

class MariaDbDuplicateKeyUpdateTest {
    private val renderer = SqlRenderer(MariaDbDialect)

    @Test
    fun rendersNativeValuesFunctionForInsertedReferences() {
        val query = insertInto(UpsertMariaDbUsers).values {
            set(UpsertMariaDbUsers.id, 1L)
            set(UpsertMariaDbUsers.email, "ada@example.test")
            set(UpsertMariaDbUsers.name, "Ada")
            set(UpsertMariaDbUsers.count, 2L)
        }.onDuplicateKeyUpdate {
            set(UpsertMariaDbUsers.name, inserted(UpsertMariaDbUsers.name))
            set(UpsertMariaDbUsers.count, UpsertMariaDbUsers.count + inserted(UpsertMariaDbUsers.count))
        }

        assertEquals(
            "INSERT INTO `app`.`users` (`id`, `email`, `name`, `count`) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), " +
                "`count` = (`app`.`users`.`count` + VALUES(`count`))",
            renderer.render(query).sql,
        )
    }

    @Test
    fun retainsInsertSelectAndMultiRowNativeForms() {
        val source = insertInto(
            UpsertMariaDbUsers,
            UpsertMariaDbUsers.id,
            UpsertMariaDbUsers.email,
            UpsertMariaDbUsers.name,
        )
            .from(
                select(
                    UpsertMariaDbArchive.id,
                    UpsertMariaDbArchive.email,
                    UpsertMariaDbArchive.name,
                ).from(UpsertMariaDbArchive),
            )
            .onDuplicateKeyUpdate { set(UpsertMariaDbUsers.name, inserted(UpsertMariaDbUsers.name)) }
        val rows = insertInto(UpsertMariaDbUsers).values {
            set(UpsertMariaDbUsers.id, 1L)
            set(UpsertMariaDbUsers.email, "one@example.test")
        }.values {
            set(UpsertMariaDbUsers.id, 2L)
            set(UpsertMariaDbUsers.email, "two@example.test")
        }.onDuplicateKeyUpdate { set(UpsertMariaDbUsers.name, inserted(UpsertMariaDbUsers.name)) }

        assertEquals(
            "INSERT INTO `app`.`users` (`id`, `email`, `name`) SELECT `app`.`archive`.`id`, " +
                "`app`.`archive`.`email`, `app`.`archive`.`name` FROM `app`.`archive` " +
                "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)",
            renderer.render(source).sql,
        )
        assertEquals(
            "INSERT INTO `app`.`users` (`id`, `email`) VALUES (?, ?), (?, ?) " +
                "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)",
            renderer.render(rows).sql,
        )
    }
}

private object UpsertMariaDbUsers : Table("users", schemaName = "app") {
    val id = long("id")
    val email = string("email")
    val name = string("name")
    val count = long("count")
}

private object UpsertMariaDbArchive : Table("archive", schemaName = "app") {
    val id = long("id")
    val email = string("email")
    val name = string("name")
}

package dev.quwac.kagiroi.mariadb

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import kotlin.test.Test
import kotlin.test.assertEquals

class SetOperationRenderingTest {
    @Test
    fun rendersNativeMariaDbSetOperators() {
        val left = select(MariaDbSetLeft.id).from(MariaDbSetLeft)
        val right = select(MariaDbSetRight.id).from(MariaDbSetRight)
        val renderer = SqlRenderer(MariaDbDialect)

        assertEquals(
            "SELECT `mariadb_set_left`.`id` FROM `mariadb_set_left` UNION SELECT `mariadb_set_right`.`id` FROM `mariadb_set_right`",
            renderer.render(left.union(right)).sql,
        )
        assertEquals(
            "SELECT `mariadb_set_left`.`id` FROM `mariadb_set_left` UNION ALL SELECT `mariadb_set_right`.`id` FROM `mariadb_set_right`",
            renderer.render(left.unionAll(right)).sql,
        )
        assertEquals(
            "SELECT `mariadb_set_left`.`id` FROM `mariadb_set_left` INTERSECT SELECT `mariadb_set_right`.`id` FROM `mariadb_set_right`",
            renderer.render(left.intersect(right)).sql,
        )
        assertEquals(
            "SELECT `mariadb_set_left`.`id` FROM `mariadb_set_left` INTERSECT ALL SELECT `mariadb_set_right`.`id` FROM `mariadb_set_right`",
            renderer.render(left.intersectAll(right)).sql,
        )
        assertEquals(
            "SELECT `mariadb_set_left`.`id` FROM `mariadb_set_left` EXCEPT SELECT `mariadb_set_right`.`id` FROM `mariadb_set_right`",
            renderer.render(left.except(right)).sql,
        )
        assertEquals(
            "SELECT `mariadb_set_left`.`id` FROM `mariadb_set_left` EXCEPT ALL SELECT `mariadb_set_right`.`id` FROM `mariadb_set_right`",
            renderer.render(left.exceptAll(right)).sql,
        )
    }
}

private object MariaDbSetLeft : Table("mariadb_set_left") {
    val id = long("id")
}
private object MariaDbSetRight : Table("mariadb_set_right") {
    val id = long("id")
}

package dev.quwac.kagiroi.mysql

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
    fun rendersNativeMySqlSetOperatorsForTheSupportedServerBaseline() {
        val left = select(MySqlSetLeft.id).from(MySqlSetLeft)
        val right = select(MySqlSetRight.id).from(MySqlSetRight)
        val renderer = SqlRenderer(MySqlDialect)

        assertEquals(
            "SELECT `mysql_set_left`.`id` FROM `mysql_set_left` UNION SELECT `mysql_set_right`.`id` FROM `mysql_set_right`",
            renderer.render(left.union(right)).sql,
        )
        assertEquals(
            "SELECT `mysql_set_left`.`id` FROM `mysql_set_left` UNION ALL SELECT `mysql_set_right`.`id` FROM `mysql_set_right`",
            renderer.render(left.unionAll(right)).sql,
        )
        assertEquals(
            "SELECT `mysql_set_left`.`id` FROM `mysql_set_left` INTERSECT SELECT `mysql_set_right`.`id` FROM `mysql_set_right`",
            renderer.render(left.intersect(right)).sql,
        )
        assertEquals(
            "SELECT `mysql_set_left`.`id` FROM `mysql_set_left` INTERSECT ALL SELECT `mysql_set_right`.`id` FROM `mysql_set_right`",
            renderer.render(left.intersectAll(right)).sql,
        )
        assertEquals(
            "SELECT `mysql_set_left`.`id` FROM `mysql_set_left` EXCEPT SELECT `mysql_set_right`.`id` FROM `mysql_set_right`",
            renderer.render(left.except(right)).sql,
        )
        assertEquals(
            "SELECT `mysql_set_left`.`id` FROM `mysql_set_left` EXCEPT ALL SELECT `mysql_set_right`.`id` FROM `mysql_set_right`",
            renderer.render(left.exceptAll(right)).sql,
        )
    }
}

private object MySqlSetLeft : Table("mysql_set_left") {
    val id = long("id")
}
private object MySqlSetRight : Table("mysql_set_right") {
    val id = long("id")
}

package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SetOperationRenderingTest {
    @Test
    fun rendersNativeSqlServerSetOperatorsAndOuterTopPagination() {
        val left = select(SqlServerSetLeft.id).from(SqlServerSetLeft)
        val right = select(SqlServerSetRight.id).from(SqlServerSetRight)
        val renderer = SqlRenderer(SqlServerDialect)

        assertEquals(
            "SELECT CONVERT(NVARCHAR(4000), [sqlserver_set_left].[id]) FROM [sqlserver_set_left] UNION " +
                "SELECT CONVERT(NVARCHAR(4000), [sqlserver_set_right].[id]) FROM [sqlserver_set_right]",
            renderer.render(left.union(right)).sql,
        )
        assertEquals(
            "SELECT TOP (@kb1) * FROM (SELECT CONVERT(NVARCHAR(4000), [sqlserver_set_left].[id]) " +
                "FROM [sqlserver_set_left] UNION ALL SELECT CONVERT(NVARCHAR(4000), " +
                "[sqlserver_set_right].[id]) FROM [sqlserver_set_right]) AS [kagiroi_set_result] " +
                "ORDER BY [kagiroi_set_result].[id] ASC",
            renderer.render(left.unionAll(right).orderBy(SqlServerSetLeft.id.asc()).limit(5)).sql,
        )
        assertEquals(
            "SELECT CONVERT(NVARCHAR(4000), [sqlserver_set_left].[id]) FROM [sqlserver_set_left] INTERSECT " +
                "SELECT CONVERT(NVARCHAR(4000), [sqlserver_set_right].[id]) FROM [sqlserver_set_right]",
            renderer.render(left.intersect(right)).sql,
        )
        assertEquals(
            "SELECT CONVERT(NVARCHAR(4000), [sqlserver_set_left].[id]) FROM [sqlserver_set_left] EXCEPT " +
                "SELECT CONVERT(NVARCHAR(4000), [sqlserver_set_right].[id]) FROM [sqlserver_set_right]",
            renderer.render(left.except(right)).sql,
        )
        assertEquals(
            "intersect-all",
            assertFailsWith<UnsupportedCapability> { renderer.render(left.intersectAll(right)) }.capabilityName,
        )
        assertEquals(
            "except-all",
            assertFailsWith<UnsupportedCapability> { renderer.render(left.exceptAll(right)) }.capabilityName,
        )
    }
}

private object SqlServerSetLeft : Table("sqlserver_set_left") {
    val id = long("id")
}
private object SqlServerSetRight : Table("sqlserver_set_right") {
    val id = long("id")
}

package fixtures

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sql.exceptAll
import dev.quwac.kagiroi.sql.intersectAll
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.union

private object NumberRows : Table("number_rows") {
    val id = long("id")
    val name = string("name")
}

private object StringRows : Table("string_rows") {
    val value = string("value")
}

val wrongSetProjectionType = select(NumberRows.id).from(NumberRows)
    .union(select(StringRows.value).from(StringRows))

val wrongSetProjectionShape = select(NumberRows.id, NumberRows.name).from(NumberRows)
    .union(select(StringRows.value).from(StringRows))

val wrongIntersectAllProjectionType = select(NumberRows.id).from(NumberRows)
    .intersectAll(select(StringRows.value).from(StringRows))

val wrongExceptAllProjectionShape = select(NumberRows.id, NumberRows.name).from(NumberRows)
    .exceptAll(select(StringRows.value).from(StringRows))

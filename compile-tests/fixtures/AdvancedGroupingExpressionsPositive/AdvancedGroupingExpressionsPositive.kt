package fixtures

import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.grouping
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.sum
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.cube
import dev.quwac.kagiroi.sql.grandTotal
import dev.quwac.kagiroi.sql.group
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.groupingSet
import dev.quwac.kagiroi.sql.groupingSets
import dev.quwac.kagiroi.sql.rollup
import dev.quwac.kagiroi.sql.select

private object Sales : Table("sales") {
    val organizationId = int("organization_id")
    val country = string("country")
    val city = string("city")
    val product = string("product")
    val amount = int("amount")
}

fun validAdvancedGrouping(row: SqlRow): String {
    val country = Sales.country.optional
    val city = Sales.city.optional
    val total = sum(Sales.amount).alias("total")
    val countryGrouping = grouping(Sales.country).alias("country_grouping")
    val rollupQuery = select(Sales.organizationId, country, city, total, countryGrouping)
        .from(Sales)
        .groupBy(Sales.organizationId, rollup(Sales.country, Sales.city))
    val cubeQuery = select(country, Sales.product.optional, total)
        .from(Sales)
        .groupBy(cube(Sales.country, Sales.product))
    val setsQuery = select(country, Sales.product.optional, total)
        .from(Sales)
        .groupBy(
            groupingSets(
                groupingSet(Sales.country, group(Sales.city, Sales.product)),
                groupingSet(Sales.country),
                grandTotal(),
            ),
        )
    val summary = select(country, total).from(Sales).groupBy(rollup(Sales.country)).asTable("summary")
    val cte = select(country, total).from(Sales).groupBy(rollup(Sales.country)).asCte("summary_cte")
    val summaryCountry: SqlExpression<String?> = summary[country]
    val cteCountry: SqlExpression<String?> = cte[country]
    val readCountry: (SqlRow) -> String? = { result -> result[country] }
    return rollupQuery.toString() + cubeQuery + setsQuery + summaryCountry + cteCountry + readCountry(row)
}

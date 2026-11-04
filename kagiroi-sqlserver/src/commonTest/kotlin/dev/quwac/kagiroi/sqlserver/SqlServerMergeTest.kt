package dev.quwac.kagiroi.sqlserver

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.asTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlServerMergeTest {
    @Test
    fun rendersNativeClausesInOrder() {
        val source = MergeSource.alias("s")
        val query = mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
            whenMatchedUpdate(MergeTarget.version gt 0) { set(MergeTarget.name, source[MergeSource.name]) }
            whenMatchedDelete()
            whenNotMatchedInsert {
                set(
                    MergeTarget.id,
                    source[MergeSource.id],
                )
                set(MergeTarget.name, source[MergeSource.name])
            }
            whenNotMatchedBySourceDelete()
        }
        assertEquals(
            "MERGE INTO [target] USING [source] AS [s] ON ([target].[id] = [s].[id]) " +
                "WHEN MATCHED AND [target].[version] > @kb1 THEN UPDATE SET [name] = [s].[name] " +
                "WHEN MATCHED THEN DELETE WHEN NOT MATCHED THEN INSERT ([id], [name]) VALUES ([s].[id], [s].[name]) " +
                "WHEN NOT MATCHED BY SOURCE THEN DELETE;",
            SqlRenderer(SqlServerDialect).render(query).sql,
        )
    }

    @Test
    fun rendersDerivedSourceBeforeActionParameters() {
        val source = select(MergeSource.id, MergeSource.name).from(MergeSource)
            .where(MergeSource.id gt 10L).asTable("s")
        val query = mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
            whenMatchedUpdate { set(MergeTarget.name, "changed") }
        }
        val result = SqlRenderer(SqlServerDialect).render(query)
        assertEquals(
            "MERGE INTO [target] USING (SELECT [source].[id], [source].[name] FROM [source] " +
                "WHERE [source].[id] > @kb1) AS [s] ON ([target].[id] = [s].[id]) " +
                "WHEN MATCHED THEN UPDATE SET [name] = @kb2;",
            result.sql,
        )
        assertEquals(2, result.parameters.size)
    }

    @Test
    fun rejectsInvalidTwoMatchedClausesAndOutOfScopeSource() {
        val source = MergeSource.alias("s")
        assertFailsWith<IllegalArgumentException> {
            mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
                whenMatchedDelete()
                whenMatchedUpdate { set(MergeTarget.name, source[MergeSource.name]) }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
                whenNotMatchedBySourceUpdate { set(MergeTarget.name, source[MergeSource.name]) }
            }
        }
    }
}

private object MergeTarget : Table("target") {
    val id = long("id")
    val name = string("name")
    val version = int("version")
}
private object MergeSource : Table("source") {
    val id = long("id")
    val name = string("name")
}

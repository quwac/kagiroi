package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.gt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OracleMergeTest {
    @Test
    fun rendersUpdateAndPostUpdateDeleteAndInsert() {
        val source = MergeSource.alias("s")
        val query = mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
            whenMatchedUpdate(where = MergeTarget.version gt 0, deleteWhere = MergeTarget.version gt 5) {
                set(MergeTarget.name, source[MergeSource.name])
            }
            whenNotMatchedInsert {
                set(
                    MergeTarget.id,
                    source[MergeSource.id],
                )
                set(MergeTarget.name, source[MergeSource.name])
            }
        }
        assertEquals(
            "MERGE INTO \"target\" USING \"source\" \"s\" ON (\"target\".\"id\" = \"s\".\"id\") " +
                "WHEN MATCHED THEN UPDATE SET \"name\" = \"s\".\"name\" WHERE \"target\".\"version\" > :p1 " +
                "DELETE WHERE \"target\".\"version\" > :p2 " +
                "WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (\"s\".\"id\", \"s\".\"name\")",
            SqlRenderer(OracleDialect).render(query).sql,
        )
    }

    @Test
    fun rejectsUpdatingOnColumn() {
        val source = MergeSource.alias("s")
        assertFailsWith<IllegalArgumentException> {
            mergeInto(MergeTarget, source, MergeTarget.id eq source[MergeSource.id]) {
                whenMatchedUpdate { set(MergeTarget.id, source[MergeSource.id]) }
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

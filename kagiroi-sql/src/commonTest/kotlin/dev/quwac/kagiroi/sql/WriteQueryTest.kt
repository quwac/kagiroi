package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.transform
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class WriteQueryTest {
    @Test
    fun buildsAndRendersInsertAst() {
        val query = insertInto(WriteUsers).values {
            set(WriteUsers.id, 7L)
            set(WriteUsers.name, "Alice")
            set(WriteUsers.age, 20)
        }

        assertSame(WriteUsers, query.into)
        assertEquals(
            listOf(WriteUsers.id, WriteUsers.name, WriteUsers.age),
            query.rows.single().assignments.map { it.column },
        )
        assertEquals(listOf(7L, "Alice", 20), query.rows.single().assignments.map { it.value })

        val rendered = renderer.render(query)

        assertEquals("INSERT INTO \"users\" (\"id\", \"name\", \"age\") VALUES (?, ?, ?)", rendered.sql)
        assertEquals(listOf(7L, "Alice", 20), rendered.parameters.map { it.value })
        assertEquals(listOf(WriteUsers.id, WriteUsers.name, WriteUsers.age), rendered.parameters.map { it.column })
    }

    @Test
    fun permitsExplicitIdentityValuesForTheDialectToApplyItsNativePolicy() {
        val insert = insertInto(IdentityWriteUsers).values {
            set(IdentityWriteUsers.id, 7L)
        }
        val update = update(IdentityWriteUsers) {
            set(IdentityWriteUsers.id, 8L)
        }

        assertEquals(
            "INSERT INTO \"identity_write_users\" (\"id\") VALUES (?)",
            renderer.render(insert).sql,
        )
        assertEquals("UPDATE \"identity_write_users\" SET \"id\" = ?", renderer.render(update).sql)
    }

    @Test
    fun distinguishesAnExplicitNullFromAnUnassignedColumn() {
        val query = insertInto(WriteUsers).values {
            set(WriteUsers.id, 8L)
            set(WriteUsers.name, "Bob")
            set(WriteUsers.nickname, null)
        }
        val assignments = query.rows.single().assignments

        assertEquals(listOf(WriteUsers.id, WriteUsers.name, WriteUsers.nickname), assignments.map { it.column })
        assertNull(assignments.single { it.column === WriteUsers.nickname }.value)
        assertEquals(false, assignments.any { it.column === WriteUsers.age })

        val rendered = renderer.render(query)

        assertEquals("INSERT INTO \"users\" (\"id\", \"name\", \"nickname\") VALUES (?, ?, ?)", rendered.sql)
        assertEquals(listOf(8L, "Bob", null), rendered.parameters.map { it.value })
        assertSame(WriteUsers.nickname, rendered.parameters.last().column)
    }

    @Test
    fun rendersMultipleInsertRowsInTheFirstRowsColumnOrder() {
        val query = insertInto(WriteUsers)
            .values {
                set(WriteUsers.id, 1L)
                set(WriteUsers.name, "Alice")
            }
            .values {
                set(WriteUsers.name, "Bob")
                set(WriteUsers.id, 2L)
            }

        val rendered = renderer.render(query)

        assertEquals("INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?), (?, ?)", rendered.sql)
        assertEquals(listOf(1L, "Alice", 2L, "Bob"), rendered.parameters.map { it.value })
    }

    @Test
    fun buildsMultipleInsertRowsFromAnIterable() {
        val users = nonEmptyListOf(
            WriteUser(1L, "Alice", 20),
            WriteUser(2L, "Bob", null),
        )

        val query = insertInto(WriteUsers).values(users) { user ->
            set(WriteUsers.id, user.id)
            set(WriteUsers.name, user.name)
            set(WriteUsers.age, user.age)
        }

        val rendered = renderer.render(query)

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"name\", \"age\") VALUES (?, ?, ?), (?, ?, ?)",
            rendered.sql,
        )
        assertEquals(listOf(1L, "Alice", 20, 2L, "Bob", null), rendered.parameters.map { it.value })
    }

    @Test
    fun buildsAnExecutableInsertFromAnIterable() {
        val query = WriteExecution.insertInto(WriteUsers).values(nonEmptyListOf(WriteUser(1L, "Alice", 20))) { user ->
            set(WriteUsers.id, user.id)
            set(WriteUsers.name, user.name)
            set(WriteUsers.age, user.age)
        }

        assertEquals(listOf(1L, "Alice", 20), query.query.rows.single().assignments.map { it.value })
    }

    @Test
    fun retainsTheNonEmptyListGuaranteeAtTheValuesBoundary() {
        val users = nonEmptyListOf(WriteUser(1L, "Alice", 20), WriteUser(2L, "Bob", null))

        assertEquals(2, users.size)
        assertEquals(WriteUser(1L, "Alice", 20), users.first)
        assertEquals(listOf(WriteUser(1L, "Alice", 20), WriteUser(2L, "Bob", null)), users)
        assertEquals(null, NonEmptyList.from(emptyList<WriteUser>()))
        assertEquals(users, users.toNonEmptyListOrNull())
    }

    @Test
    fun rejectsInsertRowsWithDifferentColumns() {
        val query = insertInto(WriteUsers).values {
            set(WriteUsers.id, 1L)
            set(WriteUsers.name, "Alice")
        }

        assertFailsWith<IllegalArgumentException> {
            query.values {
                set(WriteUsers.id, 2L)
                set(WriteUsers.age, 20)
            }
        }
    }

    @Test
    fun rendersUpdateAssignmentsBeforeWhereParameters() {
        val predicate = (WriteUsers.id eq 7L) and (WriteUsers.age.isNotNull() or (WriteUsers.nickname eq "ally"))
        val query = update(WriteUsers) {
            set(WriteUsers.name, "Alice")
            set(WriteUsers.age, 21)
        }.where(predicate)

        assertEquals(listOf(WriteUsers.name, WriteUsers.age), query.assignments.map { it.column })
        assertSame(predicate, query.predicate)

        val rendered = renderer.render(query)

        assertEquals(
            "UPDATE \"users\" SET \"name\" = ?, \"age\" = ? " +
                "WHERE (\"users\".\"id\" = ? AND (\"users\".\"age\" IS NOT NULL OR \"users\".\"nickname\" = ?))",
            rendered.sql,
        )
        assertEquals(listOf("Alice", 21, 7L, "ally"), rendered.parameters.map { it.value })
        assertEquals(
            listOf(WriteUsers.name, WriteUsers.age, WriteUsers.id, WriteUsers.nickname),
            rendered.parameters.map { it.column },
        )
    }

    @Test
    fun rendersUpdateWithoutWhereAndBindsNull() {
        val query = update(WriteUsers) {
            set(WriteUsers.nickname, null)
        }

        val rendered = renderer.render(query)

        assertEquals("UPDATE \"users\" SET \"nickname\" = ?", rendered.sql)
        assertEquals(listOf(null), rendered.parameters.map { it.value })
    }

    @Test
    fun usesDialectParameterPositionsAcrossAssignmentsAndPredicate() {
        val query = update(WriteUsers) {
            set(WriteUsers.name, "Alice")
            set(WriteUsers.age, 21)
        }.where(WriteUsers.id eq 7L)

        val rendered = SqlRenderer(WriteNumberedDialect).render(query)

        assertEquals("UPDATE `users` SET `name` = $1, `age` = $2 WHERE `users`.`id` = $3", rendered.sql)
        assertEquals(listOf<Any?>("Alice", 21, 7L), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersDeleteWithTheSharedPredicateModel() {
        val predicate = (WriteUsers.id eq 7L) or ((WriteUsers.age lt 18) and WriteUsers.nickname.isNull())
        val query = deleteFrom(WriteUsers).where(predicate)

        assertSame(predicate, query.predicate)

        val rendered = renderer.render(query)

        assertEquals(
            "DELETE FROM \"users\" WHERE (\"users\".\"id\" = ? OR " +
                "(\"users\".\"age\" < ? AND \"users\".\"nickname\" IS NULL))",
            rendered.sql,
        )
        assertEquals(listOf<Any?>(7L, 18), rendered.parameters.map { it.value })
    }

    @Test
    fun rendersExplicitDeleteAllWithoutWhere() {
        val query = deleteAllFrom(WriteUsers)

        assertSame(WriteUsers, query.from)

        val rendered = renderer.render(query)

        assertEquals("DELETE FROM \"users\"", rendered.sql)
        assertEquals(emptyList(), rendered.parameters)
    }

    @Test
    fun rendersCteScopedUpdateAndDeleteInSqlParameterOrder() {
        val targets = select(WriteUsers.id)
            .from(WriteUsers)
            .where(WriteUsers.name eq "source")
            .asCte("targets")
        val targetId = targets[WriteUsers.id]

        val update = with(targets) {
            update(WriteUsers) { set(WriteUsers.name, "processed") }
                .where(
                    WriteUsers.id.inSubquery(
                        select(targetId).from(targets).where(targetId gt 10L),
                    ),
                )
        }
        val delete = with(targets) {
            deleteFrom(WriteUsers).where(WriteUsers.id.inSubquery(select(targetId).from(targets)))
        }

        assertEquals(
            "WITH \"targets\" AS (SELECT \"users\".\"id\" FROM \"users\" WHERE \"users\".\"name\" = ?) " +
                "UPDATE \"users\" SET \"name\" = ? WHERE \"users\".\"id\" IN " +
                "(SELECT \"targets\".\"id\" FROM \"targets\" WHERE \"targets\".\"id\" > ?)",
            renderer.render(update).sql,
        )
        assertEquals(listOf<Any>("source", "processed", 10L), renderer.render(update).parameters.map { it.value })
        assertEquals(
            "WITH \"targets\" AS (SELECT \"users\".\"id\" FROM \"users\" WHERE \"users\".\"name\" = ?) " +
                "DELETE FROM \"users\" WHERE \"users\".\"id\" IN (SELECT \"targets\".\"id\" FROM \"targets\")",
            renderer.render(delete).sql,
        )
        assertEquals(listOf<Any>("source"), renderer.render(delete).parameters.map { it.value })
    }

    @Test
    fun rendersTypedInsertSelectInsideTheSameCteScope() {
        val archived = select(WriteUsers.id, WriteUsers.name)
            .from(WriteUsers)
            .where(WriteUsers.name eq "source")
            .asCte("archived")
        val archivedId = archived[WriteUsers.id]
        val archivedName = archived[WriteUsers.name]

        val query = with(archived) {
            insertInto(WriteArchive, WriteArchive.id, WriteArchive.name)
                .from(select(archivedId, archivedName).from(archived).where(archivedName eq "selected"))
        }
        val rendered = renderer.render(query)

        assertEquals(
            "WITH \"archived\" AS (SELECT \"users\".\"id\", \"users\".\"name\" FROM \"users\" " +
                "WHERE \"users\".\"name\" = ?) INSERT INTO \"user_archive\" (\"id\", \"name\") " +
                "SELECT \"archived\".\"id\", \"archived\".\"name\" FROM \"archived\" " +
                "WHERE \"archived\".\"name\" = ?",
            rendered.sql,
        )
        assertEquals(listOf<Any>("source", "selected"), rendered.parameters.map { it.value })
    }

    @Test
    fun rejectsInvalidInsertSelectShapesAndUnboundCteDmlSources() {
        assertFailsWith<IllegalArgumentException> {
            insertInto(WriteArchive, WriteArchive.id, WriteArchive.name)
                .from(select(WriteUsers.id).from(WriteUsers))
        }
        assertFailsWith<IllegalArgumentException> {
            insertInto(WriteArchive, WriteArchive.id, WriteArchive.name)
                .from(select(WriteUsers.name, WriteUsers.name).from(WriteUsers))
        }
        assertFailsWith<IllegalArgumentException> {
            insertInto(WriteArchive, WriteArchive.name)
                .from(select(WriteUsers.nickname).from(WriteUsers))
        }

        val targets = select(WriteUsers.id).from(WriteUsers).asCte("targets")
        assertFailsWith<IllegalArgumentException> {
            renderer.render(
                deleteFrom(WriteUsers).where(
                    WriteUsers.id.inSubquery(select(targets[WriteUsers.id]).from(targets)),
                ),
            )
        }
    }

    @Test
    fun keepsDmlSubqueriesIndependentFromTheChangedRow() {
        assertFailsWith<IllegalArgumentException> {
            update(WriteUsers) { set(WriteUsers.name, "processed") }
                .where(
                    WriteUsers.id.inSubquery(
                        select(OtherWriteUsers.id)
                            .from(OtherWriteUsers)
                            .where(OtherWriteUsers.id eq WriteUsers.id),
                    ),
                )
        }
    }

    @Test
    fun keepsExecutionBoundDmlResultTypesWhenAttachingCtes() {
        val targets = select(WriteUsers.id).from(WriteUsers).asCte("targets")
        val targetId = targets[WriteUsers.id]

        val update = with(targets) {
            WriteExecution.update(WriteUsers) { set(WriteUsers.name, "processed") }
                .where(WriteUsers.id.inSubquery(select(targetId).from(targets)))
        }
        val delete = with(targets) {
            WriteExecution.deleteFrom(WriteUsers)
                .where(WriteUsers.id.inSubquery(select(targetId).from(targets)))
        }
        val insert = with(targets) {
            WriteExecution.insertInto(WriteArchive, WriteArchive.id)
                .from(select(targetId).from(targets))
        }

        assertEquals(listOf(targets), update.query.withClause)
        assertEquals(listOf(targets), delete.query.withClause)
        assertEquals(listOf(targets), insert.query.withClause)
    }

    @Test
    fun rendersReturningWritesWithTheSelectedColumns() {
        val insert = SqlRenderer(WriteReturningDialect).render(
            insertInto(WriteUsers).values {
                set(WriteUsers.id, 7L)
                set(WriteUsers.name, "Alice")
            }.returning(WriteUsers.id, WriteUsers.name),
        )
        val update = SqlRenderer(WriteReturningDialect).render(
            update(WriteUsers) { set(WriteUsers.name, "Bob") }
                .where(WriteUsers.id eq 7L)
                .returning(WriteUsers.id, WriteUsers.name),
        )
        val delete = SqlRenderer(WriteReturningDialect).render(
            deleteFrom(WriteUsers).where(WriteUsers.id eq 7L).returning(WriteUsers.id),
        )

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?) RETURNING \"id\", \"name\"",
            insert.sql,
        )
        assertEquals(
            "UPDATE \"users\" SET \"name\" = ? WHERE \"users\".\"id\" = ? RETURNING \"id\", \"name\"",
            update.sql,
        )
        assertEquals("DELETE FROM \"users\" WHERE \"users\".\"id\" = ? RETURNING \"id\"", delete.sql)
    }

    @Test
    fun preservesCteScopesForReturningOutputAndReturningInto() {
        val targets = select(WriteUsers.id).from(WriteUsers).where(WriteUsers.name eq "source").asCte("targets")
        val targetId = targets[WriteUsers.id]
        val update = with(targets) {
            update(WriteUsers) { set(WriteUsers.name, "processed") }
                .where(WriteUsers.id.inSubquery(select(targetId).from(targets)))
        }
        val returning = SqlRenderer(WriteReturningDialect).render(update.returning(WriteUsers.id, WriteUsers.name))
        val output = DmlOutputRenderer(WriteOutputDialect).render(
            update,
            SqlDmlOutput(nonEmptyListOf(SqlDmlOutputColumn(SqlDmlOutputImage.AFTER, WriteUsers.id))),
        )
        val insert = with(targets) {
            insertInto(WriteArchive, WriteArchive.id).from(select(targetId).from(targets))
        }
        val returningInto = DmlReturningIntoRenderer(WriteReturningIntoDialect).render(
            insert,
            SqlDmlReturningInto(nonEmptyListOf(WriteArchive.id)),
        )

        assertEquals(
            "WITH \"targets\" AS (SELECT \"users\".\"id\" FROM \"users\" WHERE \"users\".\"name\" = ?) " +
                "UPDATE \"users\" SET \"name\" = ? WHERE \"users\".\"id\" IN " +
                "(SELECT \"targets\".\"id\" FROM \"targets\") RETURNING \"id\", \"name\"",
            returning.sql,
        )
        assertEquals(listOf<Any>("source", "processed"), returning.parameters.map { it.value })
        assertEquals(
            "WITH \"targets\" AS (SELECT \"users\".\"id\" FROM \"users\" WHERE \"users\".\"name\" = ?) " +
                "UPDATE \"users\" SET \"name\" = ? OUTPUT AFTER:id WHERE \"users\".\"id\" IN " +
                "(SELECT \"targets\".\"id\" FROM \"targets\")",
            output.sql,
        )
        assertEquals(
            "WITH \"targets\" AS (SELECT \"users\".\"id\" FROM \"users\" WHERE \"users\".\"name\" = ?) " +
                "INSERT INTO \"user_archive\" (\"id\") SELECT \"targets\".\"id\" FROM \"targets\" " +
                "RETURNING id INTO :kagiroi_ret_1",
            returningInto.sql,
        )
    }

    @Test
    fun rendersDmlOutputAtTheDialectDefinedDmlPosition() {
        val insertedId = SqlDmlOutputColumn(SqlDmlOutputImage.AFTER, WriteUsers.id)
        val insertedName = SqlDmlOutputColumn(SqlDmlOutputImage.AFTER, WriteUsers.name)
        val deletedName = SqlDmlOutputColumn(SqlDmlOutputImage.BEFORE, WriteUsers.name)
        val outputRenderer = DmlOutputRenderer(WriteOutputDialect)

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"name\") OUTPUT AFTER:id, AFTER:name VALUES (?, ?)",
            outputRenderer.render(
                insertInto(WriteUsers).values {
                    set(WriteUsers.id, 7L)
                    set(WriteUsers.name, "Alice")
                },
                SqlDmlOutput(nonEmptyListOf(insertedId, insertedName)),
            ).sql,
        )
        assertEquals(
            "UPDATE \"users\" SET \"name\" = ? OUTPUT AFTER:name, BEFORE:name WHERE \"users\".\"id\" = ?",
            outputRenderer.render(
                update(WriteUsers) { set(WriteUsers.name, "Bob") }.where(WriteUsers.id eq 7L),
                SqlDmlOutput(nonEmptyListOf(insertedName, deletedName)),
            ).sql,
        )
        assertEquals(
            "DELETE FROM \"users\" OUTPUT BEFORE:name WHERE \"users\".\"id\" = ?",
            outputRenderer.render(
                deleteFrom(WriteUsers).where(WriteUsers.id eq 7L),
                SqlDmlOutput(nonEmptyListOf(deletedName)),
            ).sql,
        )
    }

    @Test
    fun rendersDmlReturningIntoWithGeneratedOutputBindNames() {
        val returning = SqlDmlReturningInto(nonEmptyListOf(WriteUsers.id, WriteUsers.name))
        val renderer = DmlReturningIntoRenderer(WriteReturningIntoDialect)

        assertEquals(
            "INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?) RETURNING id, name INTO :kagiroi_ret_1, :kagiroi_ret_2",
            renderer.render(
                insertInto(WriteUsers).values {
                    set(WriteUsers.id, 7L)
                    set(WriteUsers.name, "Alice")
                },
                returning,
            ).sql,
        )
        assertEquals(
            listOf("kagiroi_ret_1", "kagiroi_ret_2"),
            returning.parameterNames,
        )
    }

    @Test
    fun rejectsReturningColumnsOutsideTheWriteTableOrAnUnsupportedDialect() {
        assertFailsWith<IllegalArgumentException> {
            insertInto(WriteUsers).values {
                set(WriteUsers.id, 1L)
            }.returning(OtherWriteUsers.id)
        }
        assertFailsWith<UnsupportedCapability> {
            renderer.render(insertInto(WriteUsers).values { set(WriteUsers.id, 1L) }.returning(WriteUsers.id))
        }
    }

    @Test
    fun keepsWriteValuesOutOfSqlAndUsesColumnMappings() {
        val unsafeValue = "Robert'); DROP TABLE users; --"
        val query = update(MappedWriteUsers) {
            set(MappedWriteUsers.id, WriteUserId(unsafeValue))
        }.where(MappedWriteUsers.id eq WriteUserId("user-1"))

        val rendered = renderer.render(query)

        assertEquals("UPDATE \"mapped_users\" SET \"id\" = ? WHERE \"mapped_users\".\"id\" = ?", rendered.sql)
        assertEquals(listOf(unsafeValue, "user-1"), rendered.parameters.map { it.value })
        assertEquals(false, unsafeValue in rendered.sql)
    }

    @Test
    fun rejectsColumnsOutsideTheWriteTableAndDuplicateAssignments() {
        assertFailsWith<IllegalArgumentException> {
            insertInto(WriteUsers).values { set(OtherWriteUsers.id, 1L) }
        }
        assertFailsWith<IllegalArgumentException> {
            update(WriteUsers) { set(OtherWriteUsers.id, 1L) }
        }
        assertFailsWith<IllegalArgumentException> {
            update(WriteUsers) { set(WriteUsers.id, 1L) }.where(OtherWriteUsers.id eq 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            deleteFrom(WriteUsers).where(OtherWriteUsers.id eq 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            insertInto(WriteUsers).values {
                set(WriteUsers.id, 1L)
                set(WriteUsers.id, 2L)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            update(WriteUsers) {
                set(WriteUsers.id, 1L)
                set(WriteUsers.id, 2L)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            update(WriteUsers) {}
        }
    }
}

private val renderer = SqlRenderer(DoubleQuotedQuestionMarkDialect)

private object WriteNumberedDialect : SqlDialect {
    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override fun parameterMarker(position: Int): String = "$$position"
}

private object WriteReturningDialect : SqlReturningDialect {
    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override fun parameterMarker(position: Int): String = "?"
}

private object WriteOutputDialect : SqlDmlOutputDialect {
    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override fun parameterMarker(position: Int): String = "?"

    override fun renderDmlOutput(output: SqlDmlOutput): String = "OUTPUT " + output.columns.joinToString(", ") {
        "${it.image.name}:${it.column.name}"
    }
}

private object WriteReturningIntoDialect : SqlDmlReturningIntoDialect {
    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override fun parameterMarker(position: Int): String = "?"

    override fun renderDmlReturningInto(returning: SqlDmlReturningInto): String =
        "RETURNING ${returning.columns.joinToString(", ") { it.name }} INTO " +
            returning.parameterNames.joinToString(", ") { name -> ":$name" }
}

private object WriteUsers : Table("users") {
    val id = long("id").primaryKey()
    val name = string("name")
    val age = int("age").nullable()
    val nickname = string("nickname").nullable()
}

private object OtherWriteUsers : Table("other_users") {
    val id = long("id")
}

private object WriteArchive : Table("user_archive") {
    val id = long("id")
    val name = string("name")
}

private object IdentityWriteUsers : Table("identity_write_users") {
    val id = long("id").primaryKey().identity()
}

private data class WriteUserId(val value: String)

private data class WriteUser(val id: Long, val name: String, val age: Int?)

private object WriteExecution : SqlExecution {
    override val dialect: SqlDialect = DoubleQuotedQuestionMarkDialect

    override suspend fun execute(statement: RenderedSql): ExecuteResult = ExecuteResult(0)

    override fun query(statement: RenderedSql, columns: List<dev.quwac.kagiroi.schema.SqlExpression<*>>) =
        emptyFlow<SqlRow>()

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = ExecuteResult(0)

    override fun queryRaw(sql: String, parameters: List<Any?>) = emptyFlow<RawSqlRow>()
}

private object MappedWriteUsers : Table("mapped_users") {
    val id = string("id").transform(
        fromStorage = ::WriteUserId,
        toStorage = { it.value },
    )
}

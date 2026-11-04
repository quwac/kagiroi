@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.currentInstant
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.asCte
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.except
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.from
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.inSubquery
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.intersect
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.lt
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.recursiveCte
import dev.quwac.kagiroi.sql.returning
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.testing.cteExpressionContract
import dev.quwac.kagiroi.sql.testing.functionExpressionContract
import dev.quwac.kagiroi.sql.testing.joinExpressionContract
import dev.quwac.kagiroi.sql.testing.recursiveCteExpressionContract
import dev.quwac.kagiroi.sql.testing.subqueryExpressionContract
import dev.quwac.kagiroi.sql.testing.windowExpressionContract
import dev.quwac.kagiroi.sql.union
import dev.quwac.kagiroi.sql.unionAll
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import dev.quwac.kagiroi.sql.with
import dev.quwac.kagiroi.sqlite.onConflict
import dev.quwac.kagiroi.sqlite.replaceInto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@JsModule("miniflare")
@JsNonModule
private external object MiniflareModule {
    val Miniflare: dynamic
    val convertV4MiniflareOptions: dynamic
}

private external interface Miniflare {
    fun getD1Database(name: String): Promise<CloudflareD1Binding>
    fun dispose(): Promise<Unit>
}

class D1MiniflareIntegrationTest {
    @Test
    fun executesSqliteConflictUpdateReturningAndInsertSelectParserWorkaround() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.execute(createTable(D1UpsertRows))
            database.execute(createTable(D1UpsertSource))
            database.insertInto(D1UpsertRows).values {
                set(D1UpsertRows.id, 1)
                set(D1UpsertRows.email, "ada@example.test")
                set(D1UpsertRows.name, "Ada")
                set(D1UpsertRows.revision, 1)
            }.execute()

            val updated = database.insertInto(D1UpsertRows).values {
                set(D1UpsertRows.id, 2)
                set(D1UpsertRows.email, "ada@example.test")
                set(D1UpsertRows.name, "Grace")
                set(D1UpsertRows.revision, 2)
            }.onConflict(D1UpsertRows.email) {
                doUpdate {
                    set(D1UpsertRows.name, excluded(D1UpsertRows.name))
                    where(excluded(D1UpsertRows.revision) gt D1UpsertRows.revision)
                }
            }.returning(D1UpsertRows.id, D1UpsertRows.name).one()
            assertEquals(1, updated[D1UpsertRows.id])
            assertEquals("Grace", updated[D1UpsertRows.name])

            database.insertInto(D1UpsertSource).values {
                set(D1UpsertSource.id, 3)
                set(D1UpsertSource.email, "lin@example.test")
                set(D1UpsertSource.name, "Lin")
                set(D1UpsertSource.revision, 1)
            }.execute()
            val inserted = database.insertInto(
                D1UpsertRows,
                D1UpsertRows.id,
                D1UpsertRows.email,
                D1UpsertRows.name,
                D1UpsertRows.revision,
            ).from(
                select(
                    D1UpsertSource.id,
                    D1UpsertSource.email,
                    D1UpsertSource.name,
                    D1UpsertSource.revision,
                ).from(D1UpsertSource),
            ).onConflict {
                doNothing()
            }.returning(D1UpsertRows.id).one()
            assertEquals(3, inserted[D1UpsertRows.id])
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesMultipleConflictClausesAndReplace() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.execute(createTable(D1UpsertRows))
            database.insertInto(D1UpsertRows).values {
                set(D1UpsertRows.id, 1)
                set(D1UpsertRows.email, "one@example.test")
                set(D1UpsertRows.name, "Original")
                set(D1UpsertRows.revision, 1)
            }.execute()
            val updated = database.insertInto(D1UpsertRows).values {
                set(D1UpsertRows.id, 2)
                set(D1UpsertRows.email, "one@example.test")
                set(D1UpsertRows.name, "Updated")
                set(D1UpsertRows.revision, 2)
            }.onConflict(D1UpsertRows.id) { doNothing() }
                .onConflict(D1UpsertRows.email) {
                    doUpdate { set(D1UpsertRows.name, excluded(D1UpsertRows.name)) }
                }.onConflict { doNothing() }
                .returning(D1UpsertRows.name).one()
            assertEquals("Updated", updated[D1UpsertRows.name])
            val replaced = database.replaceInto(D1UpsertRows).values {
                set(D1UpsertRows.id, 2)
                set(D1UpsertRows.email, "one@example.test")
                set(D1UpsertRows.name, "Replaced")
                set(D1UpsertRows.revision, 3)
            }.returning(D1UpsertRows.id).one()
            assertEquals(2, replaced[D1UpsertRows.id])
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    @Suppress("LongMethod") // Keeps the one Miniflare connection and CTE/DML assertions in one integration scenario.
    fun executesCteScopedWritesInsertSelectReturningAndRecursiveDelete() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.execute(createTable(D1CteDmlUsers))
            database.execute(createTable(D1CteDmlArchive))
            database.insertInto(D1CteDmlUsers).values {
                set(D1CteDmlUsers.id, 1)
                set(D1CteDmlUsers.name, "copy")
                set(D1CteDmlUsers.status, "queued")
            }.execute()
            database.insertInto(D1CteDmlUsers).values {
                set(D1CteDmlUsers.id, 2)
                set(D1CteDmlUsers.name, "remove")
                set(D1CteDmlUsers.status, "queued")
            }.execute()

            val active = select(D1CteDmlUsers.id)
                .from(D1CteDmlUsers)
                .where(D1CteDmlUsers.name eq "copy")
                .asCte("active_users")
            val activeId = active[D1CteDmlUsers.id]
            val updated = with(active) {
                database.update(D1CteDmlUsers) { set(D1CteDmlUsers.status, "processed") }
                    .where(D1CteDmlUsers.id.inSubquery(select(activeId).from(active)))
            }.returning(D1CteDmlUsers.id, D1CteDmlUsers.status).one()
            assertEquals(1, updated[D1CteDmlUsers.id])
            assertEquals("processed", updated[D1CteDmlUsers.status])

            val archived = select(D1CteDmlUsers.id, D1CteDmlUsers.status)
                .from(D1CteDmlUsers)
                .where(D1CteDmlUsers.status eq "processed")
                .asCte("archived_users")
            val archivedId = archived[D1CteDmlUsers.id]
            val archivedStatus = archived[D1CteDmlUsers.status]
            val inserted = with(archived) {
                database.insertInto(D1CteDmlArchive, D1CteDmlArchive.id, D1CteDmlArchive.status)
                    .from(select(archivedId, archivedStatus).from(archived))
            }.returning(D1CteDmlArchive.id, D1CteDmlArchive.status).one()
            assertEquals(1, inserted[D1CteDmlArchive.id])
            assertEquals("processed", inserted[D1CteDmlArchive.status])

            val removable = select(D1CteDmlUsers.id)
                .from(D1CteDmlUsers)
                .where(D1CteDmlUsers.name eq "remove")
                .asCte("removable_users")
            val removableId = removable[D1CteDmlUsers.id]
            assertEquals(
                1L,
                with(removable) {
                    database.deleteFrom(D1CteDmlUsers)
                        .where(D1CteDmlUsers.id.inSubquery(select(removableId).from(removable)))
                }.execute().affectedRows,
            )

            val n = literal(1).alias("n")
            val numbers = recursiveCte("numbers", select(n)) { self ->
                select(self[n] + 1).from(self).where(self[n] lt 1)
            }
            assertEquals(
                1,
                with(numbers) {
                    database.deleteFrom(D1CteDmlArchive)
                        .where(D1CteDmlArchive.id.inSubquery(select(numbers[n]).from(numbers)))
                }.returning(D1CteDmlArchive.id).one()[D1CteDmlArchive.id],
            )
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesExpressionPartialAndOrderedIndexAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.execute(createTable(D1ExpressionIndexRows))
            database.execute(
                createIndex("d1_active_name_lower_idx", D1ExpressionIndexRows) {
                    on(lower(D1ExpressionIndexRows.name).desc(), D1ExpressionIndexRows.id.asc())
                    where(D1ExpressionIndexRows.active eq true)
                },
            )
            database.insertInto(D1ExpressionIndexRows).values {
                set(D1ExpressionIndexRows.id, 1)
                set(D1ExpressionIndexRows.name, "Ada")
                set(D1ExpressionIndexRows.active, true)
            }.execute()

            assertEquals(
                "Ada",
                database.select(D1ExpressionIndexRows.name)
                    .from(D1ExpressionIndexRows)
                    .where(lower(D1ExpressionIndexRows.name) eq "ada")
                    .one()[D1ExpressionIndexRows.name],
            )
            assertEquals(
                "d1_active_name_lower_idx",
                database.queryRaw(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'd1_active_name_lower_idx'",
                ).toList().single().string("name"),
            )
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesNativeSqliteSetOperationsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.execute(createTable(D1SetLeft))
            database.execute(createTable(D1SetRight))
            listOf("A", "B", "C").forEach { value ->
                database.insertInto(D1SetLeft).values { set(D1SetLeft.value, value) }.execute()
            }
            listOf("B", "C", "D").forEach { value ->
                database.insertInto(D1SetRight).values { set(D1SetRight.value, value) }.execute()
            }

            val left = database.select(D1SetLeft.value).from(D1SetLeft)
            val right = database.select(D1SetRight.value).from(D1SetRight)
            assertEquals(
                listOf("A", "B", "C", "D"),
                left.union(right).orderBy(D1SetLeft.value.asc()).all().map { it[D1SetLeft.value] },
            )
            assertEquals(
                listOf("A", "B", "B", "C", "C", "D"),
                left.unionAll(right).orderBy(D1SetLeft.value.asc()).all().map { it[D1SetLeft.value] },
            )
            assertEquals(
                listOf("B", "C"),
                left.intersect(right).orderBy(D1SetLeft.value.asc()).all().map { it[D1SetLeft.value] },
            )
            assertEquals(listOf("A"), left.except(right).all().map { it[D1SetLeft.value] })
            assertEquals(
                listOf("B", "B", "C", "C"),
                left.unionAll(right).orderBy(D1SetLeft.value.asc()).limit(4).offset(1).all()
                    .map { it[D1SetLeft.value] },
            )
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesCommonTableExpressionsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            cteExpressionContract(D1Database(miniflare.getD1Database("DB").await()))
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesRecursiveCommonTableExpressionsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            recursiveCteExpressionContract(D1Database(miniflare.getD1Database("DB").await()))
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesSubqueryExpressionsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            subqueryExpressionContract(D1Database(miniflare.getD1Database("DB").await()))
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesScalarAggregateAndAliasedFunctionsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            functionExpressionContract(
                D1Database(miniflare.getD1Database("DB").await()),
                includePower = false,
            )
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesWindowFunctionsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            windowExpressionContract(
                D1Database(miniflare.getD1Database("DB").await()),
                supportsNavigationDefault = true,
                supportsAdvancedWindowFeatures = true,
            )
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun executesCrossAndOuterJoinsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            joinExpressionContract(D1Database(miniflare.getD1Database("DB").await()))
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    @Suppress("LongMethod") // One lifecycle keeps all assertions on the same local D1 database.
    fun executesCrudBatchBindingsAndSessionsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.execute(createTable(Todos))
            val firstId = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
            val payload = byteArrayOf(0, 1, 127, -1)
            val dangerousTitle = "'); DROP TABLE todos; --"

            val insert = database.insertInto(Todos).values {
                set(Todos.id, firstId)
                set(Todos.title, dangerousTitle)
                set(Todos.completed, false)
                set(Todos.payload, payload)
            }.execute()
            assertEquals(1, insert.affectedRows)

            val todo = database.select(Todos.id, Todos.title, Todos.completed, Todos.payload)
                .from(Todos)
                .where(Todos.id eq firstId)
                .one()
            assertEquals(firstId, todo[Todos.id])
            assertEquals(dangerousTitle, todo[Todos.title])
            assertEquals(false, todo[Todos.completed])
            assertContentEquals(payload, todo[Todos.payload])
            assertNull(database.select(Todos.id).from(Todos).where(Todos.id eq missingId).oneOrNull())
            val raw = database.queryRaw("SELECT title FROM todos WHERE id = ?", listOf(firstId)).toList().single()
            assertEquals(dangerousTitle, raw.string("title"))

            val update = database.update(Todos) { set(Todos.completed, true) }
                .where(Todos.id eq firstId)
                .execute()
            assertEquals(1, update.affectedRows)

            val returningInsert = database.insertInto(Todos).values {
                set(Todos.id, Uuid.parse("123e4567-e89b-12d3-a456-426614174005"))
                set(Todos.title, "returning")
                set(Todos.completed, false)
                set(Todos.payload, byteArrayOf(5))
            }.returning(Todos.id, Todos.title).one()
            assertEquals("returning", returningInsert[Todos.title])

            val returningUpdate = database.update(Todos) { set(Todos.completed, true) }
                .where(Todos.id eq returningInsert[Todos.id])
                .returning(Todos.id, Todos.completed)
                .one()
            assertTrue(returningUpdate[Todos.completed])

            val returningDelete = database.deleteFrom(Todos)
                .where(Todos.id eq returningInsert[Todos.id])
                .returning(Todos.id, Todos.title)
                .one()
            assertEquals("returning", returningDelete[Todos.title])

            val batch = database.batch {
                add(insertTodo("123e4567-e89b-12d3-a456-426614174001", "second"))
                add(insertTodo("123e4567-e89b-12d3-a456-426614174002", "third"))
            }
            assertEquals(listOf(1L, 1L), batch.map { it.result.affectedRows })
            assertEquals(3, database.select(Todos.id).from(Todos).all().size)

            val batchQuery = database.batch {
                raw("SELECT title FROM todos WHERE id = ?", listOf(firstId))
            }.single()
            assertEquals(dangerousTitle, batchQuery.rows.single().string("title"))

            assertFailsWith<ConstraintViolation> {
                database.batch {
                    add(insertTodo("123e4567-e89b-12d3-a456-426614174003", "rolled-back"))
                    add(insertTodo("123e4567-e89b-12d3-a456-426614174004", "second"))
                }
            }
            assertNull(
                database.select(Todos.id)
                    .from(Todos)
                    .where(Todos.id eq Uuid.parse("123e4567-e89b-12d3-a456-426614174003"))
                    .oneOrNull(),
            )

            assertFailsWith<ConstraintViolation> {
                database.insertInto(Todos).values {
                    set(Todos.id, Uuid.parse("123e4567-e89b-12d3-a456-426614174003"))
                    set(Todos.title, "second")
                    set(Todos.completed, false)
                    set(Todos.payload, byteArrayOf())
                }.execute()
            }

            val session = database.session(D1SessionStart.Primary)
            assertEquals(3, session.select(Todos.id).from(Todos).all().size)
            val bookmark = assertNotNull(session.bookmark())
            val resumed = database.session(after = bookmark)
            assertTrue(resumed.select(Todos.id).from(Todos).all().isNotEmpty())

            val delete = database.deleteFrom(Todos).where(Todos.id eq firstId).execute()
            assertEquals(1, delete.affectedRows)
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun createsAndReadsIdentityAndStoredGeneratedValuesAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.execute(createTable(GeneratedOrders))
            database.insertInto(GeneratedOrders).values {
                set(GeneratedOrders.price, 6)
                set(GeneratedOrders.quantity, 7)
                set(GeneratedOrders.email, "Ada@Example.com")
            }.execute()

            val row = database.select(
                GeneratedOrders.id,
                GeneratedOrders.total,
                GeneratedOrders.normalizedEmail,
            ).from(GeneratedOrders).one()
            assertEquals(1L, row[GeneratedOrders.id])
            assertEquals(42, row[GeneratedOrders.total])
            assertEquals("ada@example.com", row[GeneratedOrders.normalizedEmail])
            assertFailsWith<ConstraintViolation> {
                database.insertInto(GeneratedOrders).values {
                    set(GeneratedOrders.price, 1)
                    set(GeneratedOrders.quantity, 1)
                    set(GeneratedOrders.email, "")
                }.execute()
            }
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun addsAndReadsNotNullVirtualGeneratedValueAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            database.executeRaw(
                "CREATE TABLE alterable_generated_orders (price INTEGER NOT NULL, quantity INTEGER NOT NULL)",
            )
            database.executeRaw("INSERT INTO alterable_generated_orders (price, quantity) VALUES (?, ?)", listOf(6, 7))
            database.execute(alterTable(AlterableGeneratedOrders) { addColumn(AlterableGeneratedOrders.total) })

            val row = database.select(AlterableGeneratedOrders.total).from(AlterableGeneratedOrders).one()
            assertEquals(42, row[AlterableGeneratedOrders.total])
        } finally {
            miniflare.dispose().await()
        }
    }

    @Test
    fun enforcesTypedForeignCheckAndCompositePrimaryKeyConstraintsAgainstLocalD1() = runTest {
        val miniflare = createMiniflare(
            MiniflareModule.Miniflare,
            MiniflareModule.convertV4MiniflareOptions(options()),
        )
        try {
            val database = D1Database(miniflare.getD1Database("DB").await())
            verifyTypedConstraintEnforcement(database)
        } finally {
            miniflare.dispose().await()
        }
    }
}

private suspend fun verifyTypedConstraintEnforcement(database: D1Database) {
    database.execute(createTable(D1ConstraintParent))
    database.execute(createTable(D1ConstraintChild))
    database.execute(createTable(D1DefaultForeignKeyRows))
    database.execute(createTable(D1CompositeKeyRows))
    database.execute(createTable(D1CompositeUniqueRows))
    database.insertInto(D1ConstraintParent).values { set(D1ConstraintParent.id, 0) }.execute()
    database.insertInto(D1ConstraintParent).values { set(D1ConstraintParent.id, 1) }.execute()
    database.insertInto(D1ConstraintParent).values { set(D1ConstraintParent.id, 2) }.execute()
    database.insertInto(D1ConstraintChild).values {
        set(D1ConstraintChild.id, 1)
        set(D1ConstraintChild.parentId, 1)
        set(D1ConstraintChild.optionalParentId, 2)
        set(D1ConstraintChild.quantity, 1)
    }.execute()
    database.insertInto(D1DefaultForeignKeyRows).values { set(D1DefaultForeignKeyRows.id, 1) }.execute()
    verifyCurrentInstantDefault(database)
    assertEquals(
        0,
        database.select(D1DefaultForeignKeyRows.parentId).from(D1DefaultForeignKeyRows).one()[
            D1DefaultForeignKeyRows.parentId,
        ],
    )
    database.insertInto(D1DefaultForeignKeyRows).values {
        set(D1DefaultForeignKeyRows.id, 2)
        set(D1DefaultForeignKeyRows.parentId, 2)
    }.execute()
    assertFailsWith<ConstraintViolation> {
        database.insertInto(D1ConstraintChild).values {
            set(D1ConstraintChild.id, 2)
            set(D1ConstraintChild.parentId, 999)
            set(D1ConstraintChild.optionalParentId, null)
            set(D1ConstraintChild.quantity, 1)
        }.execute()
    }
    assertFailsWith<ConstraintViolation> {
        database.insertInto(D1ConstraintChild).values {
            set(D1ConstraintChild.id, 3)
            set(D1ConstraintChild.parentId, 1)
            set(D1ConstraintChild.optionalParentId, null)
            set(D1ConstraintChild.quantity, 0)
        }.execute()
    }
    database.deleteFrom(D1ConstraintParent).where(D1ConstraintParent.id eq 2).execute()
    assertNull(
        database.select(D1ConstraintChild.optionalParentId).from(D1ConstraintChild).one()[
            D1ConstraintChild.optionalParentId,
        ],
    )
    assertEquals(
        0,
        database.select(D1DefaultForeignKeyRows.parentId).from(D1DefaultForeignKeyRows).where(
            D1DefaultForeignKeyRows.id eq 2,
        ).one()[D1DefaultForeignKeyRows.parentId],
    )
    verifyCompositePrimaryKeyConstraint(database)
    verifyCompositeUniqueConstraint(database)
}

private suspend fun verifyCurrentInstantDefault(database: D1Database) {
    database.execute(createTable(D1CurrentInstantRows))
    database.execute(createTable(D1FunctionDefaultRows))
    database.executeRaw("INSERT INTO d1_current_instant_rows DEFAULT VALUES")
    database.executeRaw("INSERT INTO d1_function_default_rows DEFAULT VALUES")
    assertTrue(
        database.select(D1CurrentInstantRows.createdAt).from(D1CurrentInstantRows).one()[
            D1CurrentInstantRows.createdAt,
        ].epochSeconds > 0,
    )
    assertEquals(
        "default",
        database.select(D1FunctionDefaultRows.normalized).from(D1FunctionDefaultRows).one()[
            D1FunctionDefaultRows.normalized,
        ],
    )
}

private suspend fun verifyCompositePrimaryKeyConstraint(database: D1Database) {
    database.insertInto(D1CompositeKeyRows).values {
        set(D1CompositeKeyRows.organizationId, 1)
        set(D1CompositeKeyRows.userId, 1)
    }.execute()
    assertFailsWith<ConstraintViolation> {
        database.insertInto(D1CompositeKeyRows).values {
            set(D1CompositeKeyRows.organizationId, 1)
            set(D1CompositeKeyRows.userId, 1)
        }.execute()
    }
    assertEquals(
        1,
        database.insertInto(D1CompositeKeyRows).values {
            set(D1CompositeKeyRows.organizationId, 1)
            set(D1CompositeKeyRows.userId, 2)
        }.execute().affectedRows,
    )
}

private suspend fun verifyCompositeUniqueConstraint(database: D1Database) {
    database.insertInto(D1CompositeUniqueRows).values {
        set(D1CompositeUniqueRows.organizationId, 1)
        set(D1CompositeUniqueRows.email, "ada@example.com")
    }.execute()
    assertFailsWith<ConstraintViolation> {
        database.insertInto(D1CompositeUniqueRows).values {
            set(D1CompositeUniqueRows.organizationId, 1)
            set(D1CompositeUniqueRows.email, "ada@example.com")
        }.execute()
    }
    assertEquals(
        1,
        database.insertInto(D1CompositeUniqueRows).values {
            set(D1CompositeUniqueRows.organizationId, 2)
            set(D1CompositeUniqueRows.email, "ada@example.com")
        }.execute().affectedRows,
    )
}

private fun insertTodo(id: String, title: String) = insertInto(Todos).values {
    set(Todos.id, Uuid.parse(id))
    set(Todos.title, title)
    set(Todos.completed, false)
    set(Todos.payload, byteArrayOf())
}

private object Todos : Table("todos") {
    val id = uuid("id").primaryKey()
    val title = string("title").unique()
    val completed = boolean("completed")
    val payload = bytes("payload")
}

private object D1UpsertRows : Table("d1_upsert_rows") {
    val id = int("id").primaryKey()
    val email = string("email").unique()
    val name = string("name")
    val revision = int("revision")
}

private object D1UpsertSource : Table("d1_upsert_source") {
    val id = int("id").primaryKey()
    val email = string("email")
    val name = string("name")
    val revision = int("revision")
}

private object GeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity()
    val price = int("price")
    val quantity = int("quantity")
    val email = string("email")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Stored)
    val normalizedEmail = string("normalized_email").generated(lower(email), GeneratedColumnStorage.Stored)

    init {
        checkConstraint("email_not_empty") { length(email) gt 0 }
    }
}

private object AlterableGeneratedOrders : Table("alterable_generated_orders") {
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total").generated(price * quantity, GeneratedColumnStorage.Virtual)
}

private object D1ConstraintParent : Table("d1_constraint_parent") {
    val id = int("id").primaryKey()
}

private object D1ConstraintChild : Table("d1_constraint_child") {
    val id = int("id").primaryKey()
    val parentId = foreignKey("parent_id", D1ConstraintParent.id)
    val optionalParentId = foreignKey("optional_parent_id", D1ConstraintParent.id)
        .nullable()
        .onDelete(ForeignKeyAction.SetNull)
    val quantity = int("quantity")

    init {
        checkConstraint("quantity_positive") { quantity gt 0 }
    }
}

private object D1DefaultForeignKeyRows : Table("d1_default_foreign_key_rows") {
    val id = int("id").primaryKey()
    val parentId = foreignKey("parent_id", D1ConstraintParent.id)
        .default(0)
        .onDelete(ForeignKeyAction.SetDefault)
}

private object D1CurrentInstantRows : Table("d1_current_instant_rows") {
    val createdAt = instant("created_at").default(currentInstant())
}

private object D1FunctionDefaultRows : Table("d1_function_default_rows") {
    val normalized = string("normalized").default(lower(literal("DEFAULT")))
}

private object D1CompositeKeyRows : Table("d1_composite_key_rows") {
    val organizationId = int("organization_id")
    val userId = int("user_id")

    init {
        primaryKey(organizationId, userId)
    }
}

private object D1CompositeUniqueRows : Table("d1_composite_unique_rows") {
    val organizationId = int("organization_id")
    val email = string("email")

    init {
        unique(organizationId, email, name = "uq_d1_organization_email")
    }
}

private object D1SetLeft : Table("d1_set_left") {
    val value = string("value")
}

private object D1SetRight : Table("d1_set_right") {
    val value = string("value")
}

private object D1ExpressionIndexRows : Table("d1_expression_index_rows") {
    val id = int("id").primaryKey()
    val name = string("name")
    val active = boolean("active")
}

private object D1CteDmlUsers : Table("d1_cte_dml_users") {
    val id = int("id").primaryKey()
    val name = string("name")
    val status = string("status")
}

private object D1CteDmlArchive : Table("d1_cte_dml_archive") {
    val id = int("id").primaryKey()
    val status = string("status")
}

private val missingId = Uuid.parse("123e4567-e89b-12d3-a456-426614174099")

private fun options(): dynamic = js(
    "({ modules: true, script: 'export default { fetch() { return new Response(\"ok\") } }', d1Databases: ['DB'] })",
)

@Suppress("UnusedParameter")
private fun createMiniflare(constructor: dynamic, options: dynamic): Miniflare = js("new constructor(options)")

private suspend fun <T> Promise<T>.await(): T = suspendCoroutine { continuation ->
    then { value ->
        continuation.resume(value)
        value
    }.catch { error ->
        continuation.resumeWithException(error)
        null
    }
}

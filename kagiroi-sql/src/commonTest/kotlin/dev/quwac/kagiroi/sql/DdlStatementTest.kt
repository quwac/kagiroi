package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.ColumnIndexKey
import dev.quwac.kagiroi.schema.ExpressionIndexKey
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.IndexDirection
import dev.quwac.kagiroi.schema.SchemaIndex
import dev.quwac.kagiroi.schema.Sequence
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.sequence
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.unique
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class DdlStatementTest {
    @Test
    fun buildsCreateAndDropTableFromMaterialSchemaColumns() {
        val create = createTable(DdlUsers, ifNotExists = true)
        val drop = dropTable(DdlUsers, ifExists = true)

        assertSame(DdlUsers, create.table)
        assertEquals(listOf(DdlUsers.id, DdlUsers.name, DdlUsers.email), create.columns.toList())
        assertEquals(true, create.ifNotExists)
        assertEquals("Users available to the application", create.tableComment)
        assertEquals("Primary user identifier", create.columns.first().comment)
        assertSame(DdlUsers, drop.table)
        assertEquals(true, drop.ifExists)
    }

    @Test
    fun buildsOrderedNonEmptyAlterOperations() {
        assertIs<AlterTableOperation.AddColumn>(alterTable(DdlUsers) { addColumn(DdlUsers.email) }.operation)
        assertIs<AlterTableOperation.DropColumn>(alterTable(DdlUsers) { dropColumn(DdlUsers.email) }.operation)
        assertIs<AlterTableOperation.RenameColumn>(
            alterTable(DdlUsers) { renameColumn(DdlUsers.email, "contact_email") }.operation,
        )
        assertIs<AlterTableOperation.RenameTable>(alterTable(DdlUsers) { renameTable("people") }.operation)

        val multiple = alterTable(DdlUsers) {
            addColumn(DdlUsers.email)
            dropColumn(DdlUsers.name)
        }
        assertIs<AlterTableOperation.AddColumn>(multiple.operations[0])
        assertIs<AlterTableOperation.DropColumn>(multiple.operations[1])
        assertFailsWith<IllegalArgumentException> { alterTable(DdlUsers) {} }
        assertFailsWith<IllegalArgumentException> { alterTable(DdlUsers) { addColumn(DdlUsers.rank) } }
    }

    @Test
    fun rejectsOperationsAfterRenameAndAfterDroppedColumn() {
        assertFailsWith<IllegalArgumentException> {
            alterTable(DdlUsers) {
                renameColumn(DdlUsers.email, "contact_email")
                dropColumn(DdlUsers.name)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            alterTable(DdlUsers) {
                dropColumn(DdlUsers.email)
                setNotNull(DdlUsers.email)
            }
        }
    }

    @Test
    fun buildsTypedConstraintAndColumnAlterOperationsWithoutMutatingTheSchema() {
        val statement = alterTable(DdlUsers) {
            addPrimaryKey(DdlUsers.id, name = "pk_users")
            addUnique(DdlUsers.name, DdlUsers.email, name = "uq_users_name_email")
            addForeignKey(DdlUsers.id, name = "fk_users_organization").references(DdlOrganizations.id)
            addCheck("ck_users_id") { DdlUsers.id gt 0L }
            alterColumnType(DdlUsers.name, DdlUsers.name.type)
            setDefault(DdlUsers.name, "anonymous")
            dropDefault(DdlUsers.name)
            setNotNull(DdlUsers.email)
            dropNotNull(DdlUsers.email)
            dropPrimaryKey("pk_users")
            dropUnique("uq_users_name_email")
            dropForeignKey("fk_users_organization")
            dropCheck("ck_users_id")
        }

        assertEquals(
            listOf(
                AlterTableOperation.AddPrimaryKey::class,
                AlterTableOperation.AddUnique::class,
                AlterTableOperation.AddForeignKey::class,
                AlterTableOperation.AddCheck::class,
                AlterTableOperation.AlterColumnType::class,
                AlterTableOperation.SetDefault::class,
                AlterTableOperation.DropDefault::class,
                AlterTableOperation.SetNotNull::class,
                AlterTableOperation.DropNotNull::class,
                AlterTableOperation.DropPrimaryKey::class,
                AlterTableOperation.DropUnique::class,
                AlterTableOperation.DropForeignKey::class,
                AlterTableOperation.DropCheck::class,
            ),
            statement.operations.map { it::class },
        )
        assertEquals(null, DdlUsers.name.defaultExpression)

        assertFailsWith<IllegalArgumentException> {
            alterTable(DdlUsers) { addUnique(DdlUsers.name, DdlUsers.name, name = "duplicate") }
        }
        assertFailsWith<IllegalArgumentException> {
            alterTable(DdlUsers) { addCheck(" ") { DdlUsers.id gt 0L } }
        }
        assertFailsWith<IllegalArgumentException> {
            alterTable(DdlUsers) { addForeignKey(DdlUsers.id, name = "fk_incomplete") }
        }
    }

    @Test
    fun buildsSingleCompositeAndUniqueIndexesAndRejectsUnrelatedColumns() {
        val single = createIndex("users_email_idx", DdlUsers, comment = "Lookup by email") { on(DdlUsers.email) }
        val composite = createIndex("users_name_email_idx", unique = true) {
            on(DdlUsers.name, DdlUsers.email)
        }

        assertEquals(listOf(DdlUsers.email), single.columns.toList())
        assertEquals("Lookup by email", single.comment)
        assertSame(DdlUsers, composite.table)
        assertEquals(listOf(DdlUsers.name, DdlUsers.email), composite.columns.toList())
        assertEquals(true, composite.unique)
        assertSame(DdlUsers, dropIndex("users_email_idx", DdlUsers).table)

        assertFailsWith<IllegalArgumentException> {
            createIndex("invalid_idx", DdlUsers) { on(DdlUsers.email, DdlOrganizations.id) }
        }
    }

    @Test
    fun buildsTypedAdvancedIndexMetadataAndRejectsInvalidIndexContexts() {
        val advanced = AdvancedIndexUsers.activeEmailIndex
        val sameStructure = AdvancedIndexUsersCopy.activeEmailIndex

        assertEquals(advanced, sameStructure)
        assertIs<ColumnIndexKey>(advanced.keys.first())
        assertIs<ExpressionIndexKey>(advanced.keys.last())
        assertEquals(IndexDirection.Ascending, advanced.keys.first().direction)
        assertEquals(IndexDirection.Descending, advanced.keys.last().direction)
        assertEquals(listOf(AdvancedIndexUsers.id, AdvancedIndexUsers.displayName), advanced.includedColumns)
        assertEquals(createIndex(advanced).index, advanced)
        assertEquals(listOf(advanced), createIndexes(AdvancedIndexUsers).map(CreateIndex::index))

        assertFailsWith<IllegalArgumentException> { createIndexes(DdlUsers) }

        assertFailsWith<IllegalArgumentException> {
            createIndex("duplicate_key", AdvancedIndexUsers) {
                on(AdvancedIndexUsers.email, AdvancedIndexUsers.email)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            createIndex("invalid_include", AdvancedIndexUsers) {
                on(AdvancedIndexUsers.email)
                include(AdvancedIndexUsers.email)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            createIndex("foreign_expression", AdvancedIndexUsers) {
                on(lower(DdlUsers.email))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            createIndex("aggregate_expression", AdvancedIndexUsers) {
                on(count(AdvancedIndexUsers.id))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            createIndex("null_placement", AdvancedIndexUsers) {
                on(AdvancedIndexUsers.email.asc().nullsLast())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            createIndex("foreign_predicate", AdvancedIndexUsers) {
                on(AdvancedIndexUsers.email)
                where(DdlUsers.id gt 0)
            }
        }
    }

    @Test
    fun executesDdlThroughTheExistingRenderedStatementBoundary() = runTest {
        val execution = RecordingExecution()

        execution.execute(dropTable(DdlUsers))

        assertEquals(listOf(RenderedSql("DROP users", emptyList())), execution.statements)
    }

    @Test
    fun validatesTheWholeAlterPlanBeforeExecutingItsFirstStatement() = runTest {
        val execution = RecordingExecution(
            object : SqlDdlDialect {
                override fun quoteIdentifier(identifier: String): String = identifier

                override fun parameterMarker(position: Int): String = "?"

                override fun render(statement: DdlStatement): RenderedSql = when (statement) {
                    is AlterTable -> when (statement.operation) {
                        is AlterTableOperation.SetNotNull -> RenderedSql("ALTER users SET NOT NULL", emptyList())

                        is AlterTableOperation.SetDefault -> throw UnsupportedDdlOperation(
                            "test default",
                            "unsupported",
                        )

                        else -> error("Unexpected ALTER operation")
                    }

                    else -> error("Unexpected statement")
                }
            },
        )

        val failure = try {
            execution.execute(
                alterTable(DdlUsers) {
                    setNotNull(DdlUsers.email)
                    setDefault(DdlUsers.name, "anonymous")
                },
            )
            null
        } catch (exception: UnsupportedDdlOperation) {
            exception
        }
        assertIs<UnsupportedDdlOperation>(failure)
        assertEquals(emptyList(), execution.statements)
    }

    @Test
    fun executesAConfiguredDdlBlockInDeclarationOrder() = runTest {
        val execution = RecordingExecution(batchDdlDialect)

        execution.execute {
            createTable(DdlUsers)
            dropTable(DdlUsers)
            createIndex("users_email_idx", DdlUsers, comment = "Lookup by email") { on(DdlUsers.email) }
            createIndex("users_name_idx") { on(DdlUsers.name) }
            createIndex("users_id_idx", DdlUsers, listOf(DdlUsers.id))
            dropIndex("users_email_idx", DdlUsers)
        }

        assertEquals(
            listOf(
                "CREATE users",
                "DROP users",
                "CREATE INDEX users_email_idx",
                "CREATE INDEX users_name_idx",
                "CREATE INDEX users_id_idx",
                "DROP INDEX users_email_idx",
            ),
            execution.statements.map(RenderedSql::sql),
        )
    }

    @Test
    fun executesTableDeclaredIndexesWithoutRedefiningThem() = runTest {
        val execution = RecordingExecution(batchDdlDialect)

        execution.execute {
            createTable(AdvancedIndexUsers)
            createIndexes(AdvancedIndexUsers)
        }

        assertEquals(
            listOf("CREATE advanced_index_users", "CREATE INDEX active_users_email_idx"),
            execution.statements.map(RenderedSql::sql),
        )
    }

    @Test
    fun rejectsAnEmptyConfiguredDdlBlock() = runTest {
        val error = try {
            RecordingExecution(batchDdlDialect).execute { }
            null
        } catch (exception: IllegalArgumentException) {
            exception
        }

        assertNotNull(error)
    }

    @Test
    fun executesDdlAndSequenceReadsInDeclarationOrder() = runTest {
        val execution = RecordingExecution(batchDdlDialect, nextSequenceValue = 41L)

        val reservedNumber: Long = execution.execute {
            createSequence(SnapshotDefaultRows.numbers)
            createTable(SnapshotDefaultRows)
            val number = nextSequenceValue(SnapshotDefaultRows.numbers)
            dropSequence(SnapshotDefaultRows.numbers, ifExists = true)
            number
        }

        assertEquals(41L, reservedNumber)
        assertEquals(
            listOf(
                "CREATE SEQUENCE snapshot_default_rows_numbers",
                "CREATE snapshot_default_rows",
                "DROP SEQUENCE snapshot_default_rows_numbers",
            ),
            execution.statements.map(RenderedSql::sql),
        )
        assertEquals(
            "SELECT NEXT snapshot_default_rows_numbers AS kagiroi_sequence_value",
            execution.rawQueries.single(),
        )
    }

    @Test
    fun snapshotsCommentMetadataAndExecutesDdlSequencesInOrder() = runTest {
        val users = SnapshotUsers()
        val create = createTable(users)
        val updated = users.email.comment("Updated email documentation")
        val execution = RecordingExecution(
            object : SqlDdlDialect {
                override fun quoteIdentifier(identifier: String): String = identifier

                override fun parameterMarker(position: Int): String = "?"

                override fun render(statement: DdlStatement): RenderedSql = RenderedSql(
                    "CREATE snapshot_users",
                    emptyList(),
                )

                override fun renderDdl(statement: DdlStatement): RenderedDdl = RenderedDdl.of(
                    render(statement),
                    listOf(
                        RenderedSql("COMMENT snapshot_users", emptyList()),
                    ),
                )
            },
        )

        assertEquals("Snapshot users", create.tableComment)
        assertEquals("Primary email address", create.columns.single().comment)
        assertEquals("Computed display name", users.displayName.comment)
        assertEquals("Updated email documentation", updated.comment)
        execution.execute(create)
        assertEquals(
            listOf("CREATE snapshot_users", "COMMENT snapshot_users"),
            execution.statements.map(RenderedSql::sql),
        )
    }

    @Test
    fun snapshotsIdentityAndGeneratedMetadataWhileKeepingComputedColumnsLogicalOnly() {
        val orders = SnapshotGeneratedOrders()
        val create = createTable(orders)
        val updated = orders.total.comment("Changed documentation")

        assertEquals(listOf(orders.id, orders.price, orders.quantity, orders.total), create.columns.toList())
        assertEquals(true, create.columns.first().isIdentity)
        assertEquals(GeneratedColumnStorage.Stored, create.columns.last().generatedColumnStorage)
        assertEquals(
            listOf(orders.price, orders.quantity),
            create.columns.last().generatedExpression?.referencedColumns,
        )
        assertEquals("Calculated total", create.columns.last().comment)
        assertEquals("Changed documentation", updated.comment)
        assertEquals("Calculated total", create.columns.last().comment)

        assertIs<AlterTableOperation.AddColumn>(alterTable(orders) { addColumn(updated) }.operation)
        assertFailsWith<IllegalArgumentException> { alterTable(orders) { addColumn(orders.displayName) } }
    }

    @Test
    fun snapshotsTableConstraintMetadataBeforeLaterSchemaDeclarations() {
        val table = SnapshotConstraintRows()
        val create = createTable(table)

        table.addCheckAndForeignKey()

        assertEquals(listOf(table.organizationId, table.userId), create.primaryKey?.columns)
        assertEquals("pk_snapshot_constraint_rows", create.primaryKey?.name)
        assertEquals(emptyList(), create.uniqueConstraints)
        assertEquals(emptyList(), create.foreignKeys)
        assertEquals(emptyList(), create.checks)
        table.addUnique()
        assertEquals(1, table.foreignKeys.size)
        assertEquals(ForeignKeyAction.Cascade, table.foreignKeys.single().onDelete)
        assertEquals(1, table.checks.size)
        assertEquals(1, table.uniqueConstraints.size)
    }

    @Test
    fun snapshotsDefaultMetadataAndBuildsIndependentSequenceStatements() {
        val create = createTable(SnapshotDefaultRows)
        val createSequence = createSequence(SnapshotDefaultRows.numbers, ifNotExists = true)
        val dropSequence = dropSequence(SnapshotDefaultRows.numbers, ifExists = true)

        assertEquals(
            0,
            create.columns.single { it.name == "status" }.defaultExpression?.let { expression ->
                (expression.expression as dev.quwac.kagiroi.schema.SqlLiteral<*>).value
            },
        )
        assertSame(SnapshotDefaultRows.numbers, createSequence.sequence)
        assertEquals(true, createSequence.ifNotExists)
        assertSame(SnapshotDefaultRows.numbers, dropSequence.sequence)
        assertEquals(true, dropSequence.ifExists)
    }
}

private object DdlUsers : Table("users", comment = "Users available to the application") {
    val id = long("id").primaryKey().comment("Primary user identifier")
    val name = string("name")
    val email = string("email").nullable().unique()
    val rank = int("rank").computed()
}

private object DdlOrganizations : Table("organizations") {
    val id = long("id")
}

private object AdvancedIndexUsers : Table("advanced_index_users") {
    val id = long("id")
    val organizationId = long("organization_id")
    val email = string("email")
    val displayName = string("display_name")
    val active = boolean("active")
    val activeEmailIndex: SchemaIndex

    init {
        activeEmailIndex = index("active_users_email_idx", unique = true) {
            on(organizationId.asc(), lower(email).desc())
            include(id, displayName)
            where(active eq true)
        }
    }
}

private object AdvancedIndexUsersCopy : Table("advanced_index_users") {
    val id = long("id")
    val organizationId = long("organization_id")
    val email = string("email")
    val displayName = string("display_name")
    val active = boolean("active")
    val activeEmailIndex: SchemaIndex

    init {
        activeEmailIndex = index("active_users_email_idx", unique = true) {
            on(organizationId.asc(), lower(email).desc())
            include(id, displayName)
            where(active eq true)
        }
    }
}

private class SnapshotUsers : Table("snapshot_users", comment = "Snapshot users") {
    val email = string("email").comment("Primary email address")
    val displayName = string("display_name").computed().comment("Computed display name")
}

private class SnapshotGeneratedOrders : Table("snapshot_generated_orders") {
    val id = long("id").primaryKey().identity().comment("Order identifier")
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total")
        .generated(price * quantity, GeneratedColumnStorage.Stored)
        .comment("Calculated total")
    val displayName = string("display_name").computed()
}

private object SnapshotConstraintParent : Table("snapshot_constraint_parent") {
    val id = long("id").primaryKey()
}

private class SnapshotConstraintRows : Table("snapshot_constraint_rows") {
    val organizationId = long("organization_id")
    val userId = long("user_id")
    val quantity = int("quantity")

    init {
        primaryKey(organizationId, userId, name = "pk_snapshot_constraint_rows")
    }

    fun addCheckAndForeignKey() {
        foreignKey(organizationId, name = "fk_snapshot_constraint_parent")
            .references(SnapshotConstraintParent.id)
            .onDelete(ForeignKeyAction.Cascade)
        checkConstraint("quantity_positive") { quantity gt 0 }
    }

    fun addUnique() {
        unique(organizationId, userId, name = "uq_snapshot_constraint_rows")
    }
}

private object SnapshotDefaultRows : Table("snapshot_default_rows") {
    val numbers = sequence("snapshot_default_rows_numbers")
    val status = int("status").default(0)
}

private class RecordingExecution(
    override val dialect: SqlDialect = defaultDdlDialect,
    private val nextSequenceValue: Long? = null,
) : SqlExecution {
    val statements = mutableListOf<RenderedSql>()
    val rawQueries = mutableListOf<String>()

    override suspend fun execute(statement: RenderedSql): ExecuteResult {
        statements += statement
        return ExecuteResult(0)
    }

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = emptyFlow()

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = ExecuteResult(0)

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> {
        rawQueries += sql
        return nextSequenceValue?.let { value ->
            flowOf(rawSqlRow(listOf("kagiroi_sequence_value"), listOf(value)))
        } ?: emptyFlow()
    }
}

private val defaultDdlDialect = object : SqlDdlDialect {
    override fun quoteIdentifier(identifier: String): String = identifier

    override fun parameterMarker(position: Int): String = "?"

    override fun render(statement: DdlStatement): RenderedSql = when (statement) {
        is DropTable -> RenderedSql("DROP ${statement.table.tableName}", emptyList())
        else -> error("Unexpected statement")
    }
}

private val batchDdlDialect = object : SqlDdlDialect, SqlSequenceDialect {
    override fun quoteIdentifier(identifier: String): String = identifier

    override fun parameterMarker(position: Int): String = "?"

    override fun render(statement: DdlStatement): RenderedSql = RenderedSql(
        when (statement) {
            is CreateTable -> "CREATE ${statement.table.tableName}"
            is DropTable -> "DROP ${statement.table.tableName}"
            is CreateSequence -> "CREATE SEQUENCE ${statement.sequence.name}"
            is DropSequence -> "DROP SEQUENCE ${statement.sequence.name}"
            is CreateIndex -> "CREATE INDEX ${statement.name}"
            is DropIndex -> "DROP INDEX ${statement.name}"
            else -> error("Unexpected statement")
        },
        emptyList(),
    )

    override fun renderNextValue(sequence: Sequence): RenderedSql = RenderedSql(
        "SELECT NEXT ${sequence.name} AS kagiroi_sequence_value",
        emptyList(),
    )
}

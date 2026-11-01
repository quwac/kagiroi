package dev.quwac.kagiroi.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConstraintTest {
    @Test
    fun retainsTableLevelPrimaryUniqueForeignKeyAndCheckMetadataInDeclarationOrder() {
        assertEquals("pk_child", ConstraintChild.primaryKey?.name)
        assertEquals(
            listOf(ConstraintChild.organizationId, ConstraintChild.userId),
            ConstraintChild.primaryKey?.columns,
        )
        assertEquals(
            listOf(ConstraintChild.organizationId, ConstraintChild.userId),
            ConstraintChild.uniqueConstraints.single().columns,
        )
        assertEquals("uq_child_organization_user", ConstraintChild.uniqueConstraints.single().name)
        assertEquals("Organization membership is unique", ConstraintChild.uniqueConstraints.single().comment)
        assertEquals(1, ConstraintChild.foreignKeys.size)
        assertEquals("fk_child_parent", ConstraintChild.foreignKeys.single().name)
        assertEquals(
            listOf(ConstraintChild.organizationId, ConstraintChild.userId),
            ConstraintChild.foreignKeys.single().sourceColumns,
        )
        assertEquals(
            listOf(ConstraintParent.organizationId, ConstraintParent.userId),
            ConstraintChild.foreignKeys.single().targetColumns,
        )
        assertEquals(ForeignKeyAction.Cascade, ConstraintChild.foreignKeys.single().onDelete)
        assertEquals(ForeignKeyAction.NoAction, ConstraintChild.foreignKeys.single().onUpdate)
        assertEquals(listOf("organization_present", null), ConstraintChild.checks.map(CheckConstraint::name))
    }

    @Test
    fun acceptsNullableForeignKeySourcesForSetNullAndRejectsNonNullableSources() {
        assertEquals(ForeignKeyAction.SetNull, NullableForeignKeyChild.foreignKeys.single().onDelete)
        assertFailsWith<IllegalArgumentException> { NonNullableSetNullChild }
    }

    @Test
    fun declaresForeignKeyColumnsFromTheirTarget() {
        assertEquals("owner_id", InlineForeignKeyChild.ownerId.name)
        assertEquals(ConstraintParent.organizationId.type, InlineForeignKeyChild.ownerId.type)
        assertEquals(ConstraintParent.organizationId, InlineForeignKeyChild.foreignKeys[0].targetColumns.single())
        assertEquals(ForeignKeyAction.Cascade, InlineForeignKeyChild.foreignKeys[0].onDelete)
        assertEquals(ForeignKeyAction.SetNull, InlineForeignKeyChild.foreignKeys[1].onDelete)
        assertEquals(true, InlineForeignKeyChild.optionalOwnerId.isNullable)
    }

    @Test
    fun rejectsInvalidPrimaryKeyDefinitions() {
        assertFailsWith<IllegalArgumentException> { EmptyPrimaryKey }
        assertFailsWith<IllegalArgumentException> { DuplicatePrimaryKey }
        assertFailsWith<IllegalArgumentException> { UnrelatedPrimaryKey }
        assertFailsWith<IllegalArgumentException> { ColumnAndTablePrimaryKey }
    }

    @Test
    fun rejectsInvalidUniqueDefinitions() {
        assertFailsWith<IllegalArgumentException> { EmptyUnique }
        assertFailsWith<IllegalArgumentException> { DuplicateUniqueColumn }
        assertFailsWith<IllegalArgumentException> { UnrelatedUniqueColumn }
        assertFailsWith<IllegalArgumentException> { UnnamedUniqueComment }
        assertFailsWith<IllegalArgumentException> { BlankUniqueComment }
    }

    @Test
    fun rejectsMixedTargetTablesAndUnfinishedForeignKeys() {
        assertFailsWith<IllegalArgumentException> { MixedForeignKeyTarget }
        assertFailsWith<IllegalArgumentException> { DuplicateForeignKeySource }
        assertFailsWith<IllegalArgumentException> { DuplicateForeignKeyTarget }
        assertFailsWith<IllegalArgumentException> { UnfinishedForeignKey.foreignKeys }
    }

    @Test
    fun rejectsForeignKeyCountMismatchesAndUnrelatedSources() {
        assertFailsWith<IllegalArgumentException> {
            ConstraintChild.buildForeignKey(
                null,
                listOf(ConstraintChild.organizationId, ConstraintChild.userId),
                listOf(ConstraintParent.organizationId),
                null,
                null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConstraintChild.buildForeignKey(
                null,
                listOf(ConstraintParent.organizationId),
                listOf(ConstraintParent.organizationId),
                null,
                null,
            )
        }
    }

    @Test
    fun rejectsCrossTableCheckExpressionsAndRetainsOptionalNames() {
        assertNull(ConstraintChild.checks[1].name)
        assertFailsWith<IllegalArgumentException> { CrossTableCheck }
    }

    @Test
    fun retainsTypedLiteralAndSequenceDefaultsAndValidatesSetDefaultSources() {
        assertEquals(0, (DefaultedRows.status.defaultExpression?.expression as SqlLiteral<*>).value)
        assertEquals(null, (DefaultedRows.optionalLabel.defaultExpression?.expression as SqlLiteral<*>).value)
        assertEquals(
            DefaultedRows.numbers,
            (DefaultedRows.number.defaultExpression?.expression as SequenceNextValue).sequence,
        )
        val expression = DefaultedRows.numberAfterGap.defaultExpression?.expression as NumericBinaryExpression<Long>
        assertEquals(NumericBinaryOperator.Add, expression.operator)
        assertEquals(DefaultedRows.numbers, (expression.left as SequenceNextValue).sequence)
        assertEquals(10L, (expression.right as SqlLiteral<*>).value)
        assertEquals(
            SqlFunction.CurrentTimestamp,
            (DefaultedRows.createdAt.defaultExpression?.expression as SqlFunctionExpression<*>).function,
        )
        DefaultedForeignKeyChild.validateConstraints()
        assertFailsWith<IllegalArgumentException> { MissingDefaultForeignKeyChild.validateConstraints() }
    }
}

private object ConstraintParent : Table("constraint_parent") {
    val organizationId = long("organization_id")
    val userId = long("user_id")

    init {
        primaryKey(organizationId, userId, name = "pk_parent")
    }
}

private object ConstraintChild : Table("constraint_child") {
    val organizationId = long("organization_id")
    val userId = long("user_id")

    init {
        primaryKey(organizationId, userId, name = "pk_child")
        unique(
            organizationId,
            userId,
            name = "uq_child_organization_user",
            comment = "Organization membership is unique",
        )
        foreignKey(organizationId, userId, name = "fk_child_parent")
            .references(ConstraintParent.organizationId, ConstraintParent.userId)
            .onDelete(ForeignKeyAction.Cascade)
            .onUpdate(ForeignKeyAction.NoAction)
        checkConstraint("organization_present") { organizationId gt 0L }
        checkConstraint { userId gt 0L }
    }
}

private object NullableForeignKeyChild : Table("nullable_foreign_key_child") {
    val parentId = long("parent_id").nullable()

    init {
        foreignKey(parentId).references(ConstraintParent.organizationId).onDelete(ForeignKeyAction.SetNull)
    }
}

private object InlineForeignKeyChild : Table("inline_foreign_key_child") {
    val ownerId = foreignKey("owner_id", ConstraintParent.organizationId, constraintName = "fk_inline_owner")
        .onDelete(ForeignKeyAction.Cascade)
    val optionalOwnerId = foreignKey("optional_owner_id", ConstraintParent.organizationId)
        .nullable()
        .onDelete(ForeignKeyAction.SetNull)
}

private object NonNullableSetNullChild : Table("non_nullable_set_null_child") {
    val parentId = long("parent_id")

    init {
        foreignKey(parentId).references(ConstraintParent.organizationId).onDelete(ForeignKeyAction.SetNull)
    }
}

private object EmptyPrimaryKey : Table("empty_primary_key") {
    init {
        primaryKey()
    }
}

private object DuplicatePrimaryKey : Table("duplicate_primary_key") {
    val id = long("id")

    init {
        primaryKey(id, id)
    }
}

private object EmptyUnique : Table("empty_unique") {
    init {
        unique()
    }
}

private object DuplicateUniqueColumn : Table("duplicate_unique_column") {
    val id = long("id")

    init {
        unique(id, id)
    }
}

private object UnrelatedUniqueColumn : Table("unrelated_unique_column") {
    val id = long("id")

    init {
        unique(ConstraintParent.organizationId)
    }
}

private object UnnamedUniqueComment : Table("unnamed_unique_comment") {
    val id = long("id")

    init {
        unique(id, comment = "Needs a name")
    }
}

private object BlankUniqueComment : Table("blank_unique_comment") {
    val id = long("id")

    init {
        unique(id, name = "uq_blank_unique_comment", comment = " ")
    }
}

private object UnrelatedPrimaryKey : Table("unrelated_primary_key") {
    val id = long("id")

    init {
        primaryKey(ConstraintParent.organizationId)
    }
}

private object ColumnAndTablePrimaryKey : Table("column_and_table_primary_key") {
    val id = long("id").primaryKey()

    init {
        primaryKey(id)
    }
}

private object MixedForeignKeyTarget : Table("mixed_foreign_key_target") {
    val organizationId = long("organization_id")
    val userId = long("user_id")

    init {
        foreignKey(organizationId, userId).references(ConstraintParent.organizationId, ConstraintChild.userId)
    }
}

private object UnfinishedForeignKey : Table("unfinished_foreign_key") {
    val parentId = long("parent_id")

    init {
        foreignKey(parentId)
    }
}

private object DuplicateForeignKeySource : Table("duplicate_foreign_key_source") {
    val parentId = long("parent_id")

    init {
        foreignKey(parentId, parentId).references(ConstraintParent.organizationId, ConstraintParent.userId)
    }
}

private object DuplicateForeignKeyTarget : Table("duplicate_foreign_key_target") {
    val organizationId = long("organization_id")
    val userId = long("user_id")

    init {
        foreignKey(organizationId, userId).references(ConstraintParent.organizationId, ConstraintParent.organizationId)
    }
}

private object CrossTableCheck : Table("cross_table_check") {
    val id = long("id")

    init {
        checkConstraint { ConstraintParent.organizationId gt 0L }
    }
}

private object DefaultedRows : Table("defaulted_rows") {
    val numbers = sequence("defaulted_rows_numbers")
    val status = int("status").default(0)
    val optionalLabel = string("optional_label").nullable().default(null)
    val number = long("number").default(numbers.nextValue())
    val numberAfterGap = long("number_after_gap").default(numbers.nextValue() + 10L)
    val createdAt = instant("created_at").default(currentInstant())
}

private object DefaultedForeignKeyChild : Table("defaulted_foreign_key_child") {
    val parentId = long("parent_id").default(0L)

    init {
        foreignKey(parentId).references(ConstraintParent.organizationId).onDelete(ForeignKeyAction.SetDefault)
    }
}

private object MissingDefaultForeignKeyChild : Table("missing_default_foreign_key_child") {
    val parentId = long("parent_id")

    init {
        foreignKey(parentId).references(ConstraintParent.organizationId).onDelete(ForeignKeyAction.SetDefault)
    }
}

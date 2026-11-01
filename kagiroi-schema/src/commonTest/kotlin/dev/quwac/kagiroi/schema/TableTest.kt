package dev.quwac.kagiroi.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TableTest {
    @Test
    fun definesTypedColumnsAndConstraints() {
        val id: Column<Uuid> = Users.id
        val name: Column<String> = Users.name
        val age: Column<Int?> = Users.age

        assertSame(Users, id.table)
        val displayName: Column<String> = Users.displayName

        assertEquals(listOf("id", "name", "age", "handle", "display_name"), Users.columns.map(Column<*>::name))
        assertSame(UuidColumnType, id.type)
        assertSame(StringColumnType, name.type)
        assertSame(IntColumnType, age.type)
        assertTrue(id.isPrimaryKey)
        assertTrue(id.isUnique)
        assertFalse(id.isNullable)
        assertFalse(name.isNullable)
        assertTrue(age.isNullable)
        assertTrue(Users.handle.isUnique)
        assertIs<WritableColumn<String>>(Users.handle)
        assertIs<ComputedColumn<String>>(displayName)
        assertTrue(displayName.isComputed)
        assertFalse(name.isComputed)
    }

    @Test
    fun definesEveryHttpInputScalarType() {
        val stringValue: Column<String> = ScalarValues.stringValue
        val byteValue: Column<Byte> = ScalarValues.byteValue
        val shortValue: Column<Short> = ScalarValues.shortValue
        val intValue: Column<Int> = ScalarValues.intValue
        val longValue: Column<Long> = ScalarValues.longValue
        val floatValue: Column<Float> = ScalarValues.floatValue
        val doubleValue: Column<Double> = ScalarValues.doubleValue
        val ubyteValue: Column<UByte> = ScalarValues.ubyteValue
        val ushortValue: Column<UShort> = ScalarValues.ushortValue
        val uintValue: Column<UInt> = ScalarValues.uintValue
        val ulongValue: Column<ULong> = ScalarValues.ulongValue
        val booleanValue: Column<Boolean> = ScalarValues.booleanValue
        val instantValue: Column<Instant> = ScalarValues.instantValue
        val durationValue: Column<Duration> = ScalarValues.durationValue
        val uuidValue: Column<Uuid> = ScalarValues.uuidValue

        assertEquals(
            listOf(
                StringColumnType,
                ByteColumnType,
                ShortColumnType,
                IntColumnType,
                LongColumnType,
                FloatColumnType,
                DoubleColumnType,
                UByteColumnType,
                UShortColumnType,
                UIntColumnType,
                ULongColumnType,
                BooleanColumnType,
                InstantColumnType,
                DurationColumnType,
                UuidColumnType,
            ),
            listOf(
                stringValue,
                byteValue,
                shortValue,
                intValue,
                longValue,
                floatValue,
                doubleValue,
                ubyteValue,
                ushortValue,
                uintValue,
                ulongValue,
                booleanValue,
                instantValue,
                durationValue,
                uuidValue,
            ).map { it.type },
        )
    }

    @Test
    fun columnModifiersDoNotMutateOlderReferences() {
        assertFalse(ColumnAliases.rawAge.isNullable)
        assertTrue(ColumnAliases.age.isNullable)
        assertFailsWith<IllegalArgumentException> { ColumnAliases.rawAge.fromStorageValue(null) }

        assertSame(StringColumnType, ColumnAliases.rawReference.type)
        assertEquals("raw", ColumnAliases.rawReference.toStorageValue("raw"))
        assertIs<MappedColumnType<*, *>>(ColumnAliases.reference.type)
        assertEquals(Reference("mapped"), ColumnAliases.reference.fromStorageValue("mapped"))

        assertFalse(ColumnAliases.rawCode.isUnique)
        assertTrue(ColumnAliases.code.isUnique)
        assertSame(ColumnAliases.age, ColumnAliases.columns[0])
        assertSame(ColumnAliases.reference, ColumnAliases.columns[1])
        assertSame(ColumnAliases.code, ColumnAliases.columns[2])
    }

    @Test
    fun rejectsModifiersAppliedToReplacedColumnReferences() {
        assertFailsWith<IllegalArgumentException> { ColumnAliases.rawAge.unique() }
    }

    @Test
    fun retainsTableAndColumnCommentsAcrossModifierOrdering() {
        assertEquals("Application users", CommentedUsers.comment)
        assertEquals("User identifier", CommentedUsers.id.comment)
        assertEquals("Primary email address", CommentedUsers.email.comment)
        assertEquals("表示名", CommentedUsers.displayName.comment)
        assertEquals(null, Users.id.comment)
        assertTrue(CommentedUsers.email.isUnique)
        assertFalse(CommentedUsers.email.isNullable)
        assertTrue(CommentedUsers.displayName.isComputed)
    }

    @Test
    fun retainsSeparateIdentityAndGeneratedColumnMetadataAcrossModifierOrdering() {
        assertTrue(GeneratedOrders.id.isIdentity)
        assertTrue(GeneratedOrders.id.isPrimaryKey)
        assertEquals("Order identifier", GeneratedOrders.id.comment)
        assertIs<WritableColumn<Long>>(GeneratedOrders.id)

        assertTrue(GeneratedOrders.total.isGenerated)
        assertFalse(GeneratedOrders.total.isComputed)
        assertEquals(GeneratedColumnStorage.Stored, GeneratedOrders.total.generatedColumnStorage)
        assertEquals(
            listOf(GeneratedOrders.price, GeneratedOrders.quantity),
            GeneratedOrders.total.generatedExpression?.referencedColumns,
        )
        assertEquals("Calculated total amount", GeneratedOrders.total.comment)
        assertIs<GeneratedColumn<Int>>(GeneratedOrders.total)
    }

    @Test
    fun retainsIdentityOptionsAsStableSchemaMetadata() {
        assertEquals(Identity(), GeneratedOrders.id.identity)
        assertEquals(Identity(startWith = 1000L), ConfiguredIdentityOrders.startOnly.identity)
        assertEquals(Identity(incrementBy = 10L), ConfiguredIdentityOrders.incrementOnly.identity)
        assertEquals(
            Identity(startWith = 1000L, incrementBy = 10L),
            ConfiguredIdentityOrders.startAndIncrement.identity,
        )
        assertEquals(Identity(incrementBy = -1L), ConfiguredIdentityOrders.descending.identity)
        assertNotEquals(GeneratedOrders.id.identity, ConfiguredIdentityOrders.explicitDefaults.identity)
    }

    @Test
    fun rejectsZeroIdentityIncrementWithoutRestrictingDescendingIdentityMetadata() {
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid_identity_increment") {
                val id = long("id").identity(incrementBy = 0L)
            }
        }
    }

    @Test
    fun rejectsIdentityCombinedWithGeneratedOrComputedColumns() {
        assertFailsWith<IllegalArgumentException> {
            object : Table("identity_generated") {
                val source = long("source")
                val id = long("id").identity().generated(source + source, GeneratedColumnStorage.Stored)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("identity_computed") {
                val id = long("id").identity().computed()
            }
        }
    }

    @Test
    fun rejectsBlankCommentsWhileKeepingNullAsTheOnlyAbsentValue() {
        assertFailsWith<IllegalArgumentException> { object : Table("blank_table", comment = " ") {} }
        assertFailsWith<IllegalArgumentException> { CommentedUsers.email.comment("") }
    }
}

private object ScalarValues : Table("scalar_values") {
    val stringValue = string("string_value")
    val byteValue = byte("byte_value")
    val shortValue = short("short_value")
    val intValue = int("int_value")
    val longValue = long("long_value")
    val floatValue = float("float_value")
    val doubleValue = double("double_value")
    val ubyteValue = ubyte("ubyte_value")
    val ushortValue = ushort("ushort_value")
    val uintValue = uint("uint_value")
    val ulongValue = ulong("ulong_value")
    val booleanValue = boolean("boolean_value")
    val instantValue = instant("instant_value")
    val durationValue = duration("duration_value")
    val uuidValue = uuid("uuid_value")
}

private object Users : Table("users") {
    val id = uuid("id").primaryKey()
    val name = string("name")
    val age = int("age").nullable()
    val handle = string("handle").unique()
    val displayName = string("display_name").computed()
}

private data class Reference(val value: String)

private object ColumnAliases : Table("column_aliases") {
    val rawAge = int("age")
    val age = rawAge.nullable()
    val rawReference = string("reference")
    val reference = rawReference.transform(
        fromStorage = ::Reference,
        toStorage = { it.value },
    )
    val rawCode = string("code")
    val code = rawCode.unique()
}

private object CommentedUsers : Table("commented_users", comment = "Application users") {
    val id = uuid("id").comment("User identifier").primaryKey()
    val email = string("email").unique().comment("Primary email address")
    val displayName = string("display_name").computed().comment("表示名")
}

private object GeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity().comment("Order identifier")
    val price = int("price")
    val quantity = int("quantity")
    val total = int("total")
        .generated(price * quantity, GeneratedColumnStorage.Stored)
        .comment("Calculated total amount")
}

private object ConfiguredIdentityOrders : Table("configured_identity_orders") {
    val startOnly = long("start_only").identity(startWith = 1000L)
    val incrementOnly = long("increment_only").identity(incrementBy = 10L)
    val startAndIncrement = long("start_and_increment").identity(startWith = 1000L, incrementBy = 10L)
    val descending = long("descending").identity(incrementBy = -1L)
    val explicitDefaults = long("explicit_defaults").identity(startWith = 1L, incrementBy = 1L)
}

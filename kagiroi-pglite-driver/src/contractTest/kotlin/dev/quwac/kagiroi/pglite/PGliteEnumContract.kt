package dev.quwac.kagiroi.pglite

import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private enum class EnumKind { In, Out }
private val kindType = PostgresTypes.enum("pglite_punch_kind", listOf("ClockIn", "ClockOut"))
private object EnumPunches : Table("pglite_enum_punches") {
    val kind = column("kind", kindType).asEnum(EnumKind.entries) {
        EnumKind.In mapsTo "ClockIn"
        EnumKind.Out mapsTo "ClockOut"
    }
    val optionalKind = column("optional_kind", kindType).nullable()
}

internal suspend fun verifyNativeEnums(database: PGliteDatabase) {
    database.execute {
        createPostgresEnum(kindType.definition)
        createTable(EnumPunches)
    }
    try {
        database.insertInto(EnumPunches).values {
            set(EnumPunches.kind, EnumKind.In)
            set(EnumPunches.optionalKind, null)
        }.execute()
        assertEquals(
            EnumKind.In,
            database.select(
                EnumPunches.kind,
            ).from(EnumPunches).where(EnumPunches.kind eq EnumKind.In).one()[EnumPunches.kind],
        )
        assertFailsWith<DatabaseError> {
            database.executeRaw(
                "INSERT INTO pglite_enum_punches(kind) VALUES ('invalid')",
            )
        }
        database.execute { addPostgresEnumValue(kindType.definition, "New") }
        database.executeRaw("INSERT INTO pglite_enum_punches(kind, optional_kind) VALUES ('ClockOut', 'New')")
        assertEquals(
            "New",
            database.select(
                EnumPunches.optionalKind,
            ).from(EnumPunches).where(EnumPunches.kind eq EnumKind.Out).one()[EnumPunches.optionalKind],
        )
    } finally {
        database.execute {
            dropTable(EnumPunches)
            dropPostgresEnum(kindType.definition)
        }
    }
}

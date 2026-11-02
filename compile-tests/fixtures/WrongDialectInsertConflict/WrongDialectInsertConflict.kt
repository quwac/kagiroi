package fixtures

import dev.quwac.kagiroi.postgres.onConflict
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.values

private object InvalidConflictRows : Table("invalid_conflict_rows") {
    val id = long("id").primaryKey()
    val email = string("email")
    val generatedName = string("generated_name").generated(lower(email), GeneratedColumnStorage.Stored)
}

val wrongExcludedType = insertInto(InvalidConflictRows).values {
    set(InvalidConflictRows.id, 1L)
    set(InvalidConflictRows.email, "ada@example.test")
}.onConflict(InvalidConflictRows.email) {
    doUpdate {
        set(InvalidConflictRows.email, excluded(InvalidConflictRows.id))
    }
}

val generatedConflictAssignment = insertInto(InvalidConflictRows).values {
    set(InvalidConflictRows.id, 1L)
    set(InvalidConflictRows.email, "ada@example.test")
}.onConflict(InvalidConflictRows.email) {
    doUpdate {
        set(InvalidConflictRows.generatedName, excluded(InvalidConflictRows.email))
    }
}

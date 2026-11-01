package fixtures

import dev.quwac.kagiroi.mariadb.onDuplicateKeyUpdate as mariaDbDuplicateKeyUpdate
import dev.quwac.kagiroi.mysql.onDuplicateKeyUpdate as mySqlDuplicateKeyUpdate
import dev.quwac.kagiroi.postgres.onConflict as postgresOnConflict
import dev.quwac.kagiroi.postgres.onConflictConstraint
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.transform
import dev.quwac.kagiroi.sqlite.onConflict as sqliteOnConflict
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.isNull
import dev.quwac.kagiroi.sql.values
import kotlin.uuid.Uuid

private enum class ConflictStatus { Active, Suspended }

private data class ExternalReference(val value: String)

private object ConflictRows : Table("conflict_rows") {
    val id = uuid("id").primaryKey()
    val email = string("email")
    val name = string("name")
    val revision = long("revision")
    val status = int("status").asEnum(ConflictStatus.entries) {
        ConflictStatus.Active mapsTo 1
        ConflictStatus.Suspended mapsTo 2
    }
    val reference = string("reference").transform(::ExternalReference) { value -> value.value }
    val optionalNote = string("optional_note").nullable()
}

private fun dialectSpecificConflictQueries(id: Uuid, reference: ExternalReference) {
    insertInto(ConflictRows).values {
        set(ConflictRows.id, id)
        set(ConflictRows.email, "ada@example.test")
        set(ConflictRows.name, "Ada")
        set(ConflictRows.revision, 2L)
        set(ConflictRows.status, ConflictStatus.Active)
        set(ConflictRows.reference, reference)
        set(ConflictRows.optionalNote, null)
    }.postgresOnConflict(ConflictRows.email, where = ConflictRows.optionalNote.isNull()) {
        doUpdate {
            set(ConflictRows.name, excluded(ConflictRows.name))
            set(ConflictRows.status, excluded(ConflictRows.status))
            set(ConflictRows.reference, excluded(ConflictRows.reference))
            set(ConflictRows.optionalNote, excluded(ConflictRows.optionalNote))
            set(ConflictRows.revision, ConflictRows.revision + excluded(ConflictRows.revision))
            where(excluded(ConflictRows.revision) gt ConflictRows.revision)
        }
    }

    insertInto(ConflictRows).values {
        set(ConflictRows.id, id)
        set(ConflictRows.email, "ada@example.test")
    }.onConflictConstraint("uq_conflict_rows_email") {
        doNothing()
    }

    insertInto(ConflictRows).values {
        set(ConflictRows.id, id)
        set(ConflictRows.email, "ada@example.test")
    }.postgresOnConflict {
        doNothing()
    }

    insertInto(ConflictRows).values {
        set(ConflictRows.id, id)
        set(ConflictRows.email, "ada@example.test")
    }.sqliteOnConflict(ConflictRows.email) {
        doUpdate { set(ConflictRows.name, excluded(ConflictRows.name)) }
    }

    insertInto(ConflictRows).values {
        set(ConflictRows.id, id)
        set(ConflictRows.email, "ada@example.test")
    }.mySqlDuplicateKeyUpdate {
        set(ConflictRows.name, inserted(ConflictRows.name))
        set(ConflictRows.status, inserted(ConflictRows.status))
        set(ConflictRows.reference, inserted(ConflictRows.reference))
        set(ConflictRows.optionalNote, inserted(ConflictRows.optionalNote))
    }

    insertInto(ConflictRows).values {
        set(ConflictRows.id, id)
        set(ConflictRows.email, "ada@example.test")
    }.mariaDbDuplicateKeyUpdate {
        set(ConflictRows.name, inserted(ConflictRows.name))
    }
}

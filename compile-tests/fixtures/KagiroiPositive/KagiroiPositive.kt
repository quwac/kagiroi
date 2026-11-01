package fixtures

import dev.quwac.bignumber.BigDecimal
import dev.quwac.bignumber.BigInteger
import dev.quwac.kagiroi.quwac.bignumber.bigDecimal
import dev.quwac.kagiroi.quwac.bignumber.bigInteger
import dev.quwac.kagiroi.kotlinx.datetime.localDate
import dev.quwac.kagiroi.schema.Column
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.schema.currentInstant
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.defaultExpression
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.minus
import dev.quwac.kagiroi.schema.nextValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.plus
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.sequence
import dev.quwac.kagiroi.schema.transform
import dev.quwac.kagiroi.sql.DoubleQuotedQuestionMarkDialect
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.SqlExecution
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.deleteAllFrom
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.execute
import dev.quwac.kagiroi.sql.gt
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.nextSequenceValue
import dev.quwac.kagiroi.sql.nonEmptyListOf
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

private enum class AccountStatus {
    ACTIVE,
    SUSPENDED,
}

private data class AccountReference(val value: String)

private val accountNumbers = sequence("account_numbers")

private object Accounts : Table("accounts") {
    val id = uuid("id").primaryKey()
    val score = int("score").nullable()
    val normalizedScore = int("normalized_score").computed()
    val lastSeenAt = instant("last_seen_at").nullable()
    val flags = uint("flags")
    val foundedOn = localDate("founded_on")
    val balance = bigDecimal("balance")
    val externalSequence = bigInteger("external_sequence")
    val status = int("status").asEnum(AccountStatus.entries) {
        AccountStatus.ACTIVE mapsTo 100
        AccountStatus.SUSPENDED mapsTo 200
    }
    val reference = string("reference").transform(
        fromStorage = ::AccountReference,
        toStorage = { it.value },
    )
}

private object AccountOwners : Table("account_owners") {
    val organizationId = uuid("organization_id")
    val accountId = uuid("account_id")

    init {
        primaryKey(organizationId, accountId, name = "pk_account_owners")
    }
}

private object AccountAudits : Table("account_audits") {
    private val auditNumbers = sequence("account_audit_numbers")
    val organizationId = uuid("organization_id")
    val accountId = uuid("account_id")
    val actorId = foreignKey("actor_id", Accounts.id).onDelete(ForeignKeyAction.NoAction)
    val auditKey = string("audit_key")
    val revision = int("revision").default(defaultExpression(10) - 1)
    val auditNumber = long("audit_number").default(auditNumbers.nextValue() + 1L)
    val createdAt = instant("created_at").default(currentInstant())
    val observedAt = instant("observed_at").default(currentTimestamp())
    val normalized = string("normalized").default(lower(literal("DEFAULT")))

    init {
        unique(organizationId, auditKey, name = "uq_account_audits_organization_key")
        foreignKey(organizationId, accountId, name = "fk_account_audits_owner")
            .references(AccountOwners.organizationId, AccountOwners.accountId)
            .onDelete(ForeignKeyAction.Cascade)
        checkConstraint("revision_positive") { revision gt 0 }
    }

    suspend fun allocateNumber(database: SqlExecution): Long = database.nextSequenceValue(auditNumbers)
}

fun validKagiroiQuery(id: Uuid): String {
    val typedId: Column<Uuid> = Accounts.id
    val nullableScore: Column<Int?> = Accounts.score
    val computedScore: Column<Int> = Accounts.normalizedScore
    val lastSeenAt: Column<Instant?> = Accounts.lastSeenAt
    val flags: Column<UInt> = Accounts.flags
    val foundedOn: Column<LocalDate> = Accounts.foundedOn
    val balance: Column<BigDecimal> = Accounts.balance
    val externalSequence: Column<BigInteger> = Accounts.externalSequence
    val status: Column<AccountStatus> = Accounts.status
    val reference: Column<AccountReference> = Accounts.reference
    val query = select(
        typedId,
        nullableScore,
        computedScore,
        lastSeenAt,
        flags,
        foundedOn,
        balance,
        externalSequence,
        status,
        reference,
    )
        .from(Accounts)
        .where((typedId eq id) and (status eq AccountStatus.ACTIVE))
    return SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql
}

fun validTypedRowQuery(): String {
    val read: (SqlRow) -> Pair<String, String> = { row ->
        val status: AccountStatus = row[Accounts.status]
        val reference: AccountReference = row[Accounts.reference]
        status.name to reference.value
    }

    val query = select(Accounts.status, Accounts.reference).from(Accounts)
    return SqlRenderer(DoubleQuotedQuestionMarkDialect).render(query).sql + read.toString()
}

fun validKagiroiWriteQueries(id: Uuid): List<String> {
    val insert = insertInto(Accounts).values {
        set(Accounts.id, id)
        set(Accounts.score, null)
        set(Accounts.status, AccountStatus.ACTIVE)
    }
    val update = update(Accounts) {
        set(Accounts.score, 10)
        set(Accounts.reference, AccountReference("account-123"))
    }.where(Accounts.id eq id)
    val delete = deleteFrom(Accounts).where(Accounts.id eq id)
    val deleteAll = deleteAllFrom(Accounts)
    val batchInsert = insertInto(Accounts).values(
        nonEmptyListOf(id, id),
    ) { accountId ->
        set(Accounts.id, accountId)
        set(Accounts.status, AccountStatus.ACTIVE)
    }
    val renderer = SqlRenderer(DoubleQuotedQuestionMarkDialect)
    return listOf(
        renderer.render(insert).sql,
        renderer.render(batchInsert).sql,
        renderer.render(update).sql,
        renderer.render(delete).sql,
        renderer.render(deleteAll).sql,
    )
}

fun validTypedIndexDefinition(): String {
    val statement = createIndex("account_audits_lookup_idx", AccountAudits, unique = true) {
        on(AccountAudits.organizationId.asc(), AccountAudits.revision.desc())
        include(AccountAudits.auditNumber)
        where(AccountAudits.revision gt 0)
    }
    return statement.index.name
}

suspend fun validDdlSequenceExecution(database: SqlExecution): Long = database.execute {
    createSequence(accountNumbers)
    createTable(Accounts)
    val number = nextSequenceValue(accountNumbers)
    dropSequence(accountNumbers, ifExists = true)
    number
}

@file:Suppress("Indentation", "MaximumLineLength", "TrailingCommaOnCallSite")

package dev.quwac.kagiroi.oracle

import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.SqlFunction
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.alias
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.concat
import dev.quwac.kagiroi.schema.count
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.get
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.length
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.optional
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.substring
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.unique
import dev.quwac.kagiroi.sql.SqlDmlReturningInto
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedDdlOperation
import dev.quwac.kagiroi.sql.alterTable
import dev.quwac.kagiroi.sql.and
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.createIndex
import dev.quwac.kagiroi.sql.createTable
import dev.quwac.kagiroi.sql.crossJoin
import dev.quwac.kagiroi.sql.deleteFrom
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.dropIndex
import dev.quwac.kagiroi.sql.dropTable
import dev.quwac.kagiroi.sql.eq
import dev.quwac.kagiroi.sql.fullOuterJoin
import dev.quwac.kagiroi.sql.fullOuterJoinUsing
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.isNotNull
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.leftJoinUsing
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.nonEmptyListOf
import dev.quwac.kagiroi.sql.nullsFirst
import dev.quwac.kagiroi.sql.nullsLast
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.rightJoinUsing
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OracleDialectTest {
    private val renderer = SqlRenderer(OracleDialect)

    @Test
    fun rendersNativeUsingJoinsWithoutQualifiedIdentifiers() {
        val employee = OracleUsers.alias("employee")
        val manager = OracleUsers.alias("manager")
        val inner = select(employee[OracleUsers.id], manager[OracleUsers.name])
            .from(employee)
            .innerJoinUsing(manager) { using(employee[OracleUsers.id], manager[OracleUsers.id]) }
        val left = select(employee[OracleUsers.id], manager[OracleUsers.name].optional)
            .from(employee)
            .leftJoinUsing(manager) {
                using(employee[OracleUsers.id], manager[OracleUsers.id])
                using(employee[OracleUsers.name], manager[OracleUsers.name])
            }
        val right = select(employee[OracleUsers.id].optional, manager[OracleUsers.name])
            .from(employee)
            .rightJoinUsing(manager) { using(employee[OracleUsers.id], manager[OracleUsers.id]) }
        val full = select(employee[OracleUsers.id].optional, manager[OracleUsers.name].optional)
            .from(employee)
            .fullOuterJoinUsing(manager) { using(employee[OracleUsers.id], manager[OracleUsers.id]) }

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"app\".\"user\"\"name\" \"employee\" " +
                "INNER JOIN \"app\".\"user\"\"name\" \"manager\" USING (\"id\")",
            renderer.render(inner).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"app\".\"user\"\"name\" \"employee\" " +
                "LEFT JOIN \"app\".\"user\"\"name\" \"manager\" USING (\"id\", \"name\")",
            renderer.render(left).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"app\".\"user\"\"name\" \"employee\" " +
                "RIGHT JOIN \"app\".\"user\"\"name\" \"manager\" USING (\"id\")",
            renderer.render(right).sql,
        )
        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"name\" FROM \"app\".\"user\"\"name\" \"employee\" " +
                "FULL OUTER JOIN \"app\".\"user\"\"name\" \"manager\" USING (\"id\")",
            renderer.render(full).sql,
        )
    }

    @Test
    fun rendersCrossJoinWithoutOn() {
        val employee = OracleUsers.alias("employee")
        val coworker = OracleUsers.alias("coworker")
        val query = select(employee[OracleUsers.id], coworker[OracleUsers.name])
            .from(employee)
            .crossJoin(coworker)

        assertEquals(
            "SELECT \"employee\".\"id\", \"coworker\".\"name\" FROM \"app\".\"user\"\"name\" \"employee\" " +
                "CROSS JOIN \"app\".\"user\"\"name\" \"coworker\"",
            renderer.render(query).sql,
        )
    }

    @Test
    fun rendersAliasedInnerLeftAndFullOuterJoins() {
        val employee = OracleUsers.alias("employee")
        val manager = OracleUsers.alias("manager")
        val reviewer = OracleUsers.alias("reviewer")
        val query = select(
            employee[OracleUsers.name],
            manager[OracleUsers.name],
            reviewer[OracleUsers.name].optional,
        ).from(employee)
            .innerJoin(manager, on = employee[OracleUsers.id] eq manager[OracleUsers.id])
            .leftJoin(reviewer, on = employee[OracleUsers.id] eq reviewer[OracleUsers.id])

        assertEquals(
            "SELECT \"employee\".\"name\", \"manager\".\"name\", \"reviewer\".\"name\" " +
                "FROM \"app\".\"user\"\"name\" \"employee\" INNER JOIN \"app\".\"user\"\"name\" \"manager\" " +
                "ON \"employee\".\"id\" = \"manager\".\"id\" LEFT JOIN \"app\".\"user\"\"name\" \"reviewer\" " +
                "ON \"employee\".\"id\" = \"reviewer\".\"id\"",
            renderer.render(query).sql,
        )

        val full = select(employee[OracleUsers.id].optional, manager[OracleUsers.id].optional)
            .from(employee)
            .fullOuterJoin(manager, on = employee[OracleUsers.id] eq manager[OracleUsers.id])

        assertEquals(
            "SELECT \"employee\".\"id\", \"manager\".\"id\" FROM \"app\".\"user\"\"name\" \"employee\" " +
                "FULL OUTER JOIN \"app\".\"user\"\"name\" \"manager\" ON \"employee\".\"id\" = \"manager\".\"id\"",
            renderer.render(full).sql,
        )
    }

    @Test
    fun rendersPortableFunctionNames() {
        val normalized = lower(OracleUsers.name).alias("normalized_name")
        val characterLength = length(OracleUsers.name).alias("name_length")
        val rowCount = count().alias("row_count")

        assertEquals(
            "SELECT LOWER(\"app\".\"user\"\"name\".\"name\") AS \"normalized_name\", " +
                "LENGTH(\"app\".\"user\"\"name\".\"name\") AS \"name_length\", COUNT(*) AS \"row_count\" " +
                "FROM \"app\".\"user\"\"name\" GROUP BY LOWER(\"app\".\"user\"\"name\".\"name\"), " +
                "LENGTH(\"app\".\"user\"\"name\".\"name\")",
            renderer.render(
                select(normalized, characterLength, rowCount).from(OracleUsers).groupBy(normalized, characterLength),
            ).sql,
        )
    }

    @Test
    fun rendersOracleSpecificSubstringConcatAndCurrentTimestamp() {
        val rendered = renderer.render(
            select(
                substring(OracleUsers.name, 1, 2).alias("prefix"),
                concat(OracleUsers.name, OracleUsers.name).alias("doubled"),
                currentTimestamp().alias("observed_at"),
            ).from(OracleUsers),
        )

        assertEquals(
            "SELECT SUBSTR(\"app\".\"user\"\"name\".\"name\", :p1, :p2) AS \"prefix\", " +
                "(\"app\".\"user\"\"name\".\"name\" || \"app\".\"user\"\"name\".\"name\") AS \"doubled\", " +
                "TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9') || 'Z' " +
                "AS \"observed_at\" FROM \"app\".\"user\"\"name\"",
            rendered.sql,
        )
        assertEquals(listOf("1", "2"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersOracleCurrentDateAndTime() {
        assertEquals(
            "TO_CHAR(CURRENT_DATE, 'YYYY-MM-DD')",
            OracleDialect.renderFunction(SqlFunction.CurrentDate, emptyList()),
        )
        assertEquals(
            "TO_CHAR(CURRENT_TIMESTAMP, 'HH24:MI:SS.FF9')",
            OracleDialect.renderFunction(SqlFunction.CurrentTime, emptyList()),
        )
        assertEquals(
            "TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD\"T\"HH24:MI:SS.FF9') || 'Z'",
            OracleDialect.renderFunction(SqlFunction.CurrentTimestamp, emptyList()),
        )
    }

    @Test
    fun rendersQuotedIdentifiersDeterministicNamedBindingsAndFetchPagination() {
        val rendered = renderer.render(
            select(OracleUsers.name).from(OracleUsers)
                .where((OracleUsers.id eq 7L) and OracleUsers.nickname.isNotNull())
                .orderBy(OracleUsers.id.asc())
                .limit(5)
                .offset(10),
        )

        assertEquals(
            "SELECT \"app\".\"user\"\"name\".\"name\" FROM \"app\".\"user\"\"name\" " +
                "WHERE (\"app\".\"user\"\"name\".\"id\" = :p1 AND \"app\".\"user\"\"name\".\"nickname\" IS NOT NULL) " +
                "ORDER BY \"app\".\"user\"\"name\".\"id\" ASC OFFSET :p2 ROWS FETCH NEXT :p3 ROWS ONLY",
            rendered.sql,
        )
        assertEquals(listOf("7", "10", "5"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersExplicitNullOrdering() {
        val rendered = renderer.render(
            select(OracleUsers.id).from(OracleUsers).orderBy(
                OracleUsers.nickname.asc().nullsLast(),
                OracleUsers.name.desc().nullsFirst(),
            ),
        )

        assertEquals(
            "SELECT \"app\".\"user\"\"name\".\"id\" FROM \"app\".\"user\"\"name\" ORDER BY " +
                "\"app\".\"user\"\"name\".\"nickname\" ASC NULLS LAST, " +
                "\"app\".\"user\"\"name\".\"name\" DESC NULLS FIRST",
            rendered.sql,
        )
    }

    @Test
    @Suppress("LongMethod") // Keeps the complete Oracle DDL contract together for comparison.
    fun rendersTypedCrudAndDdlWithoutOracleSyntaxInTheGenericAst() {
        assertEquals(
            "INSERT INTO \"app\".\"user\"\"name\" (\"id\", \"name\") VALUES (:p1, :p2)",
            renderer.render(
                insertInto(OracleUsers).values {
                set(OracleUsers.id, 9L)
                set(OracleUsers.name, "Ada")
            }
            ).sql,
        )
        assertEquals(
            "UPDATE \"app\".\"user\"\"name\" SET \"name\" = :p1 WHERE \"app\".\"user\"\"name\".\"id\" = :p2",
            renderer.render(update(OracleUsers) { set(OracleUsers.name, "Grace") }.where(OracleUsers.id eq 9L)).sql,
        )
        assertEquals(
            "DELETE FROM \"app\".\"user\"\"name\" WHERE \"app\".\"user\"\"name\".\"id\" = :p1",
            renderer.render(deleteFrom(OracleUsers).where(OracleUsers.id eq 9L)).sql,
        )
        assertEquals(
            "CREATE TABLE IF NOT EXISTS \"app\".\"user\"\"name\" (\"id\" NUMBER(19,0) NOT NULL PRIMARY KEY, " +
                "\"name\" VARCHAR2(4000 CHAR) NOT NULL, \"email\" VARCHAR2(128 CHAR) NOT NULL UNIQUE, " +
                "\"active\" BOOLEAN NOT NULL, \"identifier\" RAW(16) NOT NULL, \"nickname\" VARCHAR2(4000 CHAR), " +
                "\"payload\" RAW(2000) NOT NULL)",
            OracleDialect.render(createTable(OracleUsers, ifNotExists = true)).sql,
        )
        assertEquals(
            "CREATE UNIQUE INDEX \"app\".\"users_name_idx\" ON \"app\".\"user\"\"name\" (\"email\")",
            OracleDialect.render(
                createIndex("users_name_idx", OracleUsers, unique = true) { on(OracleUsers.email) },
            ).sql,
        )
        assertEquals(
            "DROP INDEX \"app\".\"users_name_idx\"",
            OracleDialect.render(dropIndex("users_name_idx", OracleUsers)).sql,
        )
        assertEquals(
            "ALTER TABLE \"app\".\"user\"\"name\" ADD \"nickname\" VARCHAR2(4000 CHAR)",
            OracleDialect.render(alterTable(OracleUsers) { addColumn(OracleUsers.nickname) }).sql,
        )
        assertEquals(
            "ALTER TABLE \"app\".\"user\"\"name\" RENAME COLUMN \"nickname\" TO \"alias\"",
            OracleDialect.render(alterTable(OracleUsers) { renameColumn(OracleUsers.nickname, "alias") }).sql,
        )
        assertEquals(
            listOf(
                "ALTER TABLE \"app\".\"user\"\"name\" ADD CONSTRAINT \"ck_user_active\" CHECK (\"active\" = TRUE)",
                "ALTER TABLE \"app\".\"user\"\"name\" MODIFY (\"active\" DEFAULT TRUE)",
                "ALTER TABLE \"app\".\"user\"\"name\" MODIFY (\"active\" DEFAULT NULL)",
            ),
            OracleDialect.renderDdl(
                alterTable(OracleUsers) {
                    addCheck("ck_user_active") { OracleUsers.active eq true }
                    setDefault(OracleUsers.active, true)
                    dropDefault(OracleUsers.active)
                },
            ).statements.map { it.sql },
        )
        assertEquals(
            "DROP TABLE IF EXISTS \"app\".\"user\"\"name\"",
            OracleDialect.render(dropTable(OracleUsers, true)).sql,
        )
    }

    @Test
    fun rendersReturningIntoWithPrivateOutputBindNames() {
        assertEquals(
            "RETURNING \"id\", \"name\" INTO :kagiroi_ret_1, :kagiroi_ret_2",
            OracleDialect.renderDmlReturningInto(
                SqlDmlReturningInto(nonEmptyListOf(OracleUsers.id, OracleUsers.name)),
            ),
        )
    }

    @Test
    fun rejectsGenericIndexesForTypesThatNeedOracleSpecificIndexDesign() {
        val error = assertFailsWith<UnsupportedDdlOperation> {
            OracleDialect.render(createIndex("documents_body", OracleDocuments) { on(OracleDocuments.body) })
        }
        assertEquals(
            "DDL operation is not supported: Oracle index (Oracle CLOB needs an Oracle-specific domain or function-based index)",
            error.message,
        )
    }

    @Test
    fun rendersFunctionBasedOrderedIndexesAndRejectsPartialAndIncludeClauses() {
        assertEquals(
            "CREATE INDEX \"app\".\"users_email_lower_idx\" ON \"app\".\"user\"\"name\" " +
                "(LOWER(\"email\") DESC)",
            OracleDialect.render(
                createIndex("users_email_lower_idx", OracleUsers) {
                    on(lower(OracleUsers.email).desc())
                },
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            OracleDialect.render(
                createIndex("active_users_email_idx", OracleUsers) {
                    on(OracleUsers.email)
                    where(OracleUsers.active eq true)
                },
            )
        }
        assertFailsWith<UnsupportedDdlOperation> {
            OracleDialect.render(
                createIndex("covering_users_email_idx", OracleUsers) {
                    on(OracleUsers.email)
                    include(OracleUsers.id)
                },
            )
        }
    }

    @Test
    fun rendersCreateCommentsAsOrderedOracleMetadataStatements() {
        assertEquals(
            listOf(
                "CREATE TABLE \"app\".\"commented_users\" (\"id\" NUMBER(19,0) NOT NULL PRIMARY KEY, " +
                    "\"email\" VARCHAR2(4000 CHAR) NOT NULL, \"display_name\" VARCHAR2(4000 CHAR) NOT NULL)",
                "COMMENT ON TABLE \"app\".\"commented_users\" IS 'Application users'",
                "COMMENT ON COLUMN \"app\".\"commented_users\".\"id\" IS 'User identifier'",
                "COMMENT ON COLUMN \"app\".\"commented_users\".\"email\" IS " +
                    "'Primary user''s email\nユーザーの主要メールアドレス'",
                "COMMENT ON COLUMN \"app\".\"commented_users\".\"display_name\" IS " +
                    "'''; DROP TABLE users; --'",
            ),
            OracleDialect.renderDdl(createTable(OracleCommentedUsers)).statements.map { it.sql },
        )
    }

    @Test
    fun retainsIndexAndUniqueConstraintCommentsWithoutOraclePersistence() {
        assertEquals("Email must be unique", OracleCommentedConstraints.uniqueConstraints.single().comment)
        assertEquals(
            listOf(
                "CREATE TABLE \"commented_constraints\" (\"email\" VARCHAR2(128 CHAR) NOT NULL, " +
                    "CONSTRAINT \"uq_commented_constraints_email\" UNIQUE (\"email\"))",
            ),
            OracleDialect.renderDdl(createTable(OracleCommentedConstraints)).statements.map { it.sql },
        )
        assertEquals(
            listOf("CREATE INDEX \"commented_email_idx\" ON \"commented_constraints\" (\"email\")"),
            OracleDialect.renderDdl(
                createIndex("commented_email_idx", OracleCommentedConstraints, comment = "Email lookup") {
                    on(OracleCommentedConstraints.email)
                },
            ).statements.map { it.sql },
        )
    }

    @Test
    fun rendersIdentityAndVirtualOrMaterializedExpressionColumns() {
        assertEquals(
            "CREATE TABLE \"generated_orders\" (" +
                "\"id\" NUMBER(19,0) GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY, " +
                "\"price\" NUMBER(10,0) NOT NULL, \"quantity\" NUMBER(10,0) NOT NULL, " +
                "\"virtual_total\" NUMBER(10,0) GENERATED ALWAYS AS (\"price\" * \"quantity\") VIRTUAL NOT NULL, " +
                "\"stored_total\" NUMBER(10,0) GENERATED ALWAYS AS (\"price\" * \"quantity\") MATERIALIZED NOT NULL)",
            OracleDialect.render(createTable(OracleGeneratedOrders)).sql,
        )
        assertEquals(
            "ALTER TABLE \"generated_orders\" ADD \"virtual_total\" NUMBER(10,0) " +
                "GENERATED ALWAYS AS (\"price\" * \"quantity\") VIRTUAL NOT NULL",
            OracleDialect.render(
                alterTable(OracleGeneratedOrders) { addColumn(OracleGeneratedOrders.virtualTotal) }
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            OracleDialect.render(alterTable(OracleGeneratedOrders) { addColumn(OracleGeneratedOrders.id) })
        }
    }

    @Test
    fun rendersIdentityStartAndIncrementOptionsWithoutChangingDefaultIdentitySql() {
        assertEquals(
            "CREATE TABLE \"identity_default\" (\"id\" NUMBER(19,0) GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY)",
            OracleDialect.render(createTable(oracleIdentityTable("identity_default"))).sql,
        )
        assertEquals(
            "CREATE TABLE \"identity_start\" (\"id\" NUMBER(19,0) GENERATED BY DEFAULT AS IDENTITY " +
                "(START WITH 1000) NOT NULL PRIMARY KEY)",
            OracleDialect.render(createTable(oracleIdentityTable("identity_start", startWith = 1000L))).sql,
        )
        assertEquals(
            "CREATE TABLE \"identity_increment\" (\"id\" NUMBER(19,0) GENERATED BY DEFAULT AS IDENTITY " +
                "(INCREMENT BY -10) NOT NULL PRIMARY KEY)",
            OracleDialect.render(createTable(oracleIdentityTable("identity_increment", incrementBy = -10L))).sql,
        )
        assertEquals(
            "CREATE TABLE \"identity_start_increment\" (\"id\" NUMBER(19,0) GENERATED BY DEFAULT AS IDENTITY " +
                "(START WITH 1000 INCREMENT BY 10) NOT NULL PRIMARY KEY)",
            OracleDialect.render(
                createTable(oracleIdentityTable("identity_start_increment", startWith = 1000L, incrementBy = 10L)),
            ).sql,
        )
    }
}

private fun oracleIdentityTable(name: String, startWith: Long? = null, incrementBy: Long? = null): Table =
    object : Table(name) {
        val id = long("id").identity(startWith = startWith, incrementBy = incrementBy).primaryKey()
    }

private object OracleUsers : Table("user\"name", schemaName = "app") {
    val id = long("id").primaryKey()
    val name = string("name")
    val email = column("email", OracleTypes.varchar2(128)).unique()
    val active = boolean("active")
    val identifier = uuid("identifier")
    val nickname = string("nickname").nullable()
    val payload = bytes("payload")
}

private object OracleDocuments : Table("documents") {
    val body = column("body", OracleTypes.clob)
}

private object OracleCommentedUsers : Table("commented_users", schemaName = "app", comment = "Application users") {
    val id = long("id").primaryKey().comment("User identifier")
    val email = string("email").comment("Primary user's email\nユーザーの主要メールアドレス")
    val displayName = string("display_name").comment("'; DROP TABLE users; --")
}

private object OracleCommentedConstraints : Table("commented_constraints") {
    val email = column("email", OracleTypes.varchar2(128))

    init {
        unique(
            email,
            name = "uq_commented_constraints_email",
            comment = "Email must be unique",
        )
    }
}

private object OracleGeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity()
    val price = int("price")
    val quantity = int("quantity")
    val virtualTotal = int("virtual_total").generated(price * quantity, GeneratedColumnStorage.Virtual)
    val storedTotal = int("stored_total").generated(price * quantity, GeneratedColumnStorage.Stored)
}

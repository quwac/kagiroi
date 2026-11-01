@file:Suppress("MaximumLineLength")

package dev.quwac.kagiroi.sqlserver

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
import dev.quwac.kagiroi.sql.SqlDmlOutput
import dev.quwac.kagiroi.sql.SqlDmlOutputColumn
import dev.quwac.kagiroi.sql.SqlDmlOutputImage
import dev.quwac.kagiroi.sql.SqlRenderer
import dev.quwac.kagiroi.sql.UnsupportedCapability
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
import dev.quwac.kagiroi.sql.groupBy
import dev.quwac.kagiroi.sql.innerJoin
import dev.quwac.kagiroi.sql.innerJoinUsing
import dev.quwac.kagiroi.sql.insertInto
import dev.quwac.kagiroi.sql.isNotNull
import dev.quwac.kagiroi.sql.leftJoin
import dev.quwac.kagiroi.sql.limit
import dev.quwac.kagiroi.sql.nonEmptyListOf
import dev.quwac.kagiroi.sql.offset
import dev.quwac.kagiroi.sql.orderBy
import dev.quwac.kagiroi.sql.select
import dev.quwac.kagiroi.sql.update
import dev.quwac.kagiroi.sql.values
import dev.quwac.kagiroi.sql.where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlServerDialectTest {
    private val renderer = SqlRenderer(SqlServerDialect)

    @Test
    fun rejectsUsingJoinInsteadOfRewritingItToOn() {
        val employee = SqlServerUsers.alias("employee")
        val manager = SqlServerUsers.alias("manager")
        val query = select(employee[SqlServerUsers.id], manager[SqlServerUsers.name])
            .from(employee)
            .innerJoinUsing(manager) { using(employee[SqlServerUsers.id], manager[SqlServerUsers.id]) }

        val failure = assertFailsWith<UnsupportedCapability> { renderer.render(query) }

        assertEquals("join-using", failure.capabilityName)
    }

    @Test
    fun rendersCrossJoinWithoutOn() {
        val employee = SqlServerUsers.alias("employee")
        val coworker = SqlServerUsers.alias("coworker")
        val query = select(employee[SqlServerUsers.id], coworker[SqlServerUsers.name])
            .from(employee)
            .crossJoin(coworker)

        assertEquals(
            "SELECT CONVERT(NVARCHAR(4000), [employee].[id]), [coworker].[name] " +
                "FROM [app].[user]]name] AS [employee] CROSS JOIN [app].[user]]name] AS [coworker]",
            renderer.render(query).sql,
        )
    }

    @Test
    fun rendersAliasedInnerLeftAndFullOuterJoins() {
        val employee = SqlServerUsers.alias("employee")
        val manager = SqlServerUsers.alias("manager")
        val reviewer = SqlServerUsers.alias("reviewer")
        val query = select(
            employee[SqlServerUsers.name],
            manager[SqlServerUsers.name],
            reviewer[SqlServerUsers.name].optional,
        ).from(employee)
            .innerJoin(manager, on = employee[SqlServerUsers.id] eq manager[SqlServerUsers.id])
            .leftJoin(reviewer, on = employee[SqlServerUsers.id] eq reviewer[SqlServerUsers.id])

        assertEquals(
            "SELECT [employee].[name], [manager].[name], [reviewer].[name] FROM [app].[user]]name] AS [employee] " +
                "INNER JOIN [app].[user]]name] AS [manager] ON [employee].[id] = [manager].[id] " +
                "LEFT JOIN [app].[user]]name] AS [reviewer] ON [employee].[id] = [reviewer].[id]",
            renderer.render(query).sql,
        )

        val full = select(employee[SqlServerUsers.id].optional, manager[SqlServerUsers.id].optional)
            .from(employee)
            .fullOuterJoin(manager, on = employee[SqlServerUsers.id] eq manager[SqlServerUsers.id])

        assertEquals(
            "SELECT CONVERT(NVARCHAR(4000), [employee].[id]), CONVERT(NVARCHAR(4000), [manager].[id]) " +
                "FROM [app].[user]]name] AS [employee] FULL OUTER JOIN [app].[user]]name] AS [manager] " +
                "ON [employee].[id] = [manager].[id]",
            renderer.render(full).sql,
        )
    }

    @Test
    fun rendersTypedFunctionsWithTsqlCharacterLengthAndBigCount() {
        val normalized = lower(SqlServerUsers.name).alias("normalized_name")
        val characterLength = length(SqlServerUsers.name).alias("name_length")
        val rowCount = count().alias("row_count")

        val rendered = renderer.render(
            select(normalized, characterLength, rowCount).from(SqlServerUsers).groupBy(normalized, characterLength),
        )

        assertEquals(
            "SELECT LOWER([app].[user]]name].[name]) AS [normalized_name], " +
                "LEN([app].[user]]name].[name] + N'.') - 1 AS [name_length], COUNT_BIG(*) AS [row_count] " +
                "FROM [app].[user]]name] GROUP BY LOWER([app].[user]]name].[name]), " +
                "LEN([app].[user]]name].[name] + N'.') - 1",
            rendered.sql,
        )
    }

    @Test
    fun rendersTsqlSpecificSubstringConcatAndCurrentTimestamp() {
        val rendered = renderer.render(
            select(
                substring(SqlServerUsers.name, 1, 2).alias("prefix"),
                concat(SqlServerUsers.name, SqlServerUsers.name).alias("doubled"),
                currentTimestamp().alias("observed_at"),
            ).from(SqlServerUsers),
        )

        assertEquals(
            "SELECT SUBSTRING([app].[user]]name].[name], @kb1, @kb2) AS [prefix], " +
                "CONCAT([app].[user]]name].[name], [app].[user]]name].[name]) AS [doubled], " +
                "CONVERT(NVARCHAR(33), SYSUTCDATETIME(), 127) + N'Z' AS [observed_at] " +
                "FROM [app].[user]]name]",
            rendered.sql,
        )
        assertEquals(listOf("1", "2"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersTsqlCurrentDateAndTime() {
        assertEquals(
            "CONVERT(NVARCHAR(10), SYSDATETIME(), 23)",
            SqlServerDialect.renderFunction(SqlFunction.CurrentDate, emptyList()),
        )
        assertEquals(
            "CONVERT(NVARCHAR(8), SYSDATETIME(), 108) + N'.' + " +
                "RIGHT(CONVERT(NVARCHAR(23), SYSDATETIME(), 121), 3)",
            SqlServerDialect.renderFunction(SqlFunction.CurrentTime, emptyList()),
        )
        assertEquals(
            "CONVERT(NVARCHAR(33), SYSUTCDATETIME(), 127) + N'Z'",
            SqlServerDialect.renderFunction(SqlFunction.CurrentTimestamp, emptyList()),
        )
    }

    @Test
    fun rendersBracketEscapedIdentifiersAndNamedParameters() {
        val query = select(SqlServerUsers.name).from(SqlServerUsers)
            .where((SqlServerUsers.id eq 7L) and SqlServerUsers.nickname.isNotNull())
            .orderBy(SqlServerUsers.id.asc())
            .limit(5)
            .offset(10)

        val rendered = renderer.render(query)

        assertEquals(
            "SELECT [app].[user]]name].[name] FROM [app].[user]]name] " +
                "WHERE ([app].[user]]name].[id] = @kb1 AND [app].[user]]name].[nickname] IS NOT NULL) " +
                "ORDER BY [app].[user]]name].[id] ASC OFFSET @kb2 ROWS FETCH NEXT @kb3 ROWS ONLY",
            rendered.sql,
        )
        assertEquals(listOf("7", "10", "5"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rendersTopBeforePredicatesForLimitOnly() {
        val rendered = renderer.render(
            select(SqlServerUsers.id).from(SqlServerUsers).where(SqlServerUsers.id eq 7L).limit(2),
        )

        assertEquals(
            "SELECT TOP (@kb1) CONVERT(NVARCHAR(4000), [app].[user]]name].[id]) FROM [app].[user]]name] " +
                "WHERE [app].[user]]name].[id] = @kb2",
            rendered.sql,
        )
        assertEquals(listOf("2", "7"), rendered.parameters.map { it.value.toString() })
    }

    @Test
    fun rejectsOffsetWithoutAnExplicitOrder() {
        val error = assertFailsWith<IllegalArgumentException> {
            renderer.render(select(SqlServerUsers.id).from(SqlServerUsers).offset(1))
        }

        assertEquals(
            "SQL Server requires ORDER BY when SelectQuery uses offset(); add orderBy(...) before offset()",
            error.message,
        )
    }

    @Test
    fun rendersTypedReadAndWriteOperationsWithoutLimitSyntax() {
        assertEquals(
            "INSERT INTO [app].[user]]name] ([id], [name]) VALUES (@kb1, @kb2)",
            renderer.render(
                insertInto(SqlServerUsers).values {
                    set(SqlServerUsers.id, 9L)
                    set(SqlServerUsers.name, "Ada")
                },
            ).sql,
        )
        assertEquals(
            "UPDATE [app].[user]]name] SET [name] = @kb1 WHERE [app].[user]]name].[id] = @kb2",
            renderer.render(
                update(SqlServerUsers) { set(SqlServerUsers.name, "Grace") }.where(SqlServerUsers.id eq 9L),
            ).sql,
        )
        assertEquals(
            "DELETE FROM [app].[user]]name] WHERE [app].[user]]name].[id] = @kb1",
            renderer.render(deleteFrom(SqlServerUsers).where(SqlServerUsers.id eq 9L)).sql,
        )
    }

    @Test
    fun rendersOutputImages() {
        val insertedId = SqlDmlOutputColumn(SqlDmlOutputImage.AFTER, SqlServerUsers.id)
        val insertedName = SqlDmlOutputColumn(SqlDmlOutputImage.AFTER, SqlServerUsers.name)
        val deletedName = SqlDmlOutputColumn(SqlDmlOutputImage.BEFORE, SqlServerUsers.name)

        assertEquals(
            "OUTPUT INSERTED.[id], INSERTED.[name]",
            SqlServerDialect.renderDmlOutput(SqlDmlOutput(nonEmptyListOf(insertedId, insertedName))),
        )
        assertEquals(
            "OUTPUT INSERTED.[name], DELETED.[name]",
            SqlServerDialect.renderDmlOutput(SqlDmlOutput(nonEmptyListOf(insertedName, deletedName))),
        )
        assertEquals(
            "OUTPUT DELETED.[name]",
            SqlServerDialect.renderDmlOutput(SqlDmlOutput(nonEmptyListOf(deletedName))),
        )
    }

    @Test
    @Suppress("LongMethod") // Keeps the complete SQL Server DDL rendering contract together.
    fun rendersSqlServerDdlAndRejectsGenericConditionalCreate() {
        assertEquals(
            "CREATE TABLE [app].[user]]name] ([id] BIGINT NOT NULL PRIMARY KEY, [name] NVARCHAR(MAX) NOT NULL, " +
                "[email] NVARCHAR(256) NOT NULL UNIQUE, [active] BIT NOT NULL, [identifier] UNIQUEIDENTIFIER NOT NULL, " +
                "[nickname] NVARCHAR(MAX), " +
                "[payload] VARBINARY(MAX) NOT NULL)",
            SqlServerDialect.render(createTable(SqlServerUsers)).sql,
        )
        assertEquals(
            "CREATE UNIQUE INDEX [users_name_idx] ON [app].[user]]name] ([email])",
            SqlServerDialect.render(
                createIndex("users_name_idx", SqlServerUsers, unique = true) {
                    on(SqlServerUsers.email)
                },
            ).sql,
        )
        assertEquals(
            "DROP INDEX [users_name_idx] ON [app].[user]]name]",
            SqlServerDialect.render(dropIndex("users_name_idx", SqlServerUsers)).sql,
        )
        assertEquals(
            "ALTER TABLE [app].[user]]name] ADD [nickname] NVARCHAR(MAX)",
            SqlServerDialect.render(
                alterTable(SqlServerUsers) { addColumn(SqlServerUsers.nickname) },
            ).sql,
        )
        assertEquals(
            "ALTER TABLE [app].[user]]name] DROP COLUMN [nickname]",
            SqlServerDialect.render(
                alterTable(SqlServerUsers) { dropColumn(SqlServerUsers.nickname) },
            ).sql,
        )
        assertEquals(
            "EXEC sp_rename N'[app].[user]]name].[nickname]', N'alias', N'COLUMN'",
            SqlServerDialect.render(
                alterTable(SqlServerUsers) { renameColumn(SqlServerUsers.nickname, "alias") },
            ).sql,
        )
        assertEquals(
            "EXEC sp_rename N'[app].[user]]name]', N'accounts'",
            SqlServerDialect.render(
                alterTable(SqlServerUsers) { renameTable("accounts") },
            ).sql,
        )
        assertEquals(
            listOf(
                "ALTER TABLE [app].[user]]name] ADD CONSTRAINT [ck_user_active] CHECK ([active] = 1)",
                "ALTER TABLE [app].[user]]name] ALTER COLUMN [nickname] NVARCHAR(MAX) NOT NULL",
                "ALTER TABLE [app].[user]]name] ALTER COLUMN [nickname] NVARCHAR(MAX) NOT NULL",
                "ALTER TABLE [app].[user]]name] ALTER COLUMN [nickname] NVARCHAR(MAX) NULL",
            ),
            SqlServerDialect.renderDdl(
                alterTable(SqlServerUsers) {
                    addCheck("ck_user_active") { SqlServerUsers.active eq true }
                    setNotNull(SqlServerUsers.nickname)
                    alterColumnType(SqlServerUsers.nickname, SqlServerUsers.nickname.type)
                    dropNotNull(SqlServerUsers.nickname)
                },
            ).statements.map { it.sql },
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqlServerDialect.renderDdl(alterTable(SqlServerUsers) { dropDefault(SqlServerUsers.active) })
        }
        assertEquals(
            "DROP TABLE [app].[user]]name]",
            SqlServerDialect.render(dropTable(SqlServerUsers)).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqlServerDialect.render(createTable(SqlServerUsers, ifNotExists = true))
        }
    }

    @Test
    fun rendersFilteredCoveringOrderedIndexesAndRejectsExpressionIndexes() {
        assertEquals(
            "CREATE UNIQUE INDEX [active_users_email_idx] ON [app].[user]]name] ([email] DESC) " +
                "INCLUDE ([id], [identifier]) WHERE [active] = 1",
            SqlServerDialect.render(
                createIndex("active_users_email_idx", SqlServerUsers, unique = true) {
                    on(SqlServerUsers.email.desc())
                    include(SqlServerUsers.id, SqlServerUsers.identifier)
                    where(SqlServerUsers.active eq true)
                },
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqlServerDialect.render(
                createIndex("users_email_lower_idx", SqlServerUsers) { on(lower(SqlServerUsers.email)) },
            )
        }
    }

    @Test
    fun rendersCreateAndExtendedPropertiesForTableAndColumns() {
        assertEquals(
            listOf(
                "CREATE TABLE [app].[commented_users] ([id] BIGINT NOT NULL PRIMARY KEY, " +
                    "[email] NVARCHAR(MAX) NOT NULL, [display_name] NVARCHAR(MAX) NOT NULL)",
                "EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Application users', " +
                    "@level0type = N'SCHEMA', @level0name = N'app', @level1type = N'TABLE', " +
                    "@level1name = N'commented_users'",
                "EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'User identifier', " +
                    "@level0type = N'SCHEMA', @level0name = N'app', @level1type = N'TABLE', " +
                    "@level1name = N'commented_users', @level2type = N'COLUMN', @level2name = N'id'",
                "EXEC sys.sp_addextendedproperty @name = N'MS_Description', " +
                    "@value = N'Primary user''s email\nユーザーの主要メールアドレス', @level0type = N'SCHEMA', " +
                    "@level0name = N'app', @level1type = N'TABLE', @level1name = N'commented_users', " +
                    "@level2type = N'COLUMN', @level2name = N'email'",
                "EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'''; DROP TABLE users; --', " +
                    "@level0type = N'SCHEMA', @level0name = N'app', @level1type = N'TABLE', " +
                    "@level1name = N'commented_users', @level2type = N'COLUMN', @level2name = N'display_name'",
            ),
            SqlServerDialect.renderDdl(createTable(SqlServerCommentedUsers)).statements.map { it.sql },
        )
    }

    @Test
    fun rendersExtendedPropertiesForIndexAndNamedUniqueConstraintComments() {
        assertEquals(
            listOf(
                "CREATE TABLE [commented_constraints] ([email] NVARCHAR(256) NOT NULL, " +
                    "CONSTRAINT [uq_commented_constraints_email] UNIQUE ([email]))",
                "DECLARE @kagiroi_schema sysname = SCHEMA_NAME(); " +
                    "EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Email must be unique', " +
                    "@level0type = N'SCHEMA', @level0name = @kagiroi_schema, @level1type = N'TABLE', " +
                    "@level1name = N'commented_constraints', @level2type = N'CONSTRAINT', " +
                    "@level2name = N'uq_commented_constraints_email'",
            ),
            SqlServerDialect.renderDdl(createTable(SqlServerCommentedConstraints)).statements.map { it.sql },
        )
        assertEquals(
            listOf(
                "CREATE INDEX [commented_email_idx] ON [commented_constraints] ([email])",
                "DECLARE @kagiroi_schema sysname = SCHEMA_NAME(); " +
                    "EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Email lookup', " +
                    "@level0type = N'SCHEMA', @level0name = @kagiroi_schema, @level1type = N'TABLE', " +
                    "@level1name = N'commented_constraints', @level2type = N'INDEX', " +
                    "@level2name = N'commented_email_idx'",
            ),
            SqlServerDialect.renderDdl(
                createIndex("commented_email_idx", SqlServerCommentedConstraints, comment = "Email lookup") {
                    on(SqlServerCommentedConstraints.email)
                },
            ).statements.map { it.sql },
        )
    }

    @Test
    fun resolvesTheCurrentSchemaBeforeAddingPropertiesForAnUnqualifiedTable() {
        assertEquals(
            listOf(
                "CREATE TABLE [default_schema_comments] ([id] BIGINT NOT NULL)",
                "DECLARE @kagiroi_schema sysname = SCHEMA_NAME(); " +
                    "EXEC sys.sp_addextendedproperty @name = N'MS_Description', @value = N'Table comment', " +
                    "@level0type = N'SCHEMA', @level0name = @kagiroi_schema, @level1type = N'TABLE', " +
                    "@level1name = N'default_schema_comments'",
            ),
            SqlServerDialect.renderDdl(createTable(SqlServerDefaultSchemaComments)).statements.map { it.sql },
        )
    }

    @Test
    fun rendersIdentityAndComputedColumnsWithNativeStorageTerms() {
        assertEquals(
            "CREATE TABLE [generated_orders] ([id] BIGINT IDENTITY NOT NULL PRIMARY KEY, [price] INT NOT NULL, " +
                "[quantity] INT NOT NULL, [virtual_total] AS ([price] * [quantity]), " +
                "[stored_total] AS ([price] * [quantity]) PERSISTED NOT NULL)",
            SqlServerDialect.render(createTable(SqlServerGeneratedOrders)).sql,
        )
        assertEquals(
            "ALTER TABLE [generated_orders] ADD [stored_total] AS ([price] * [quantity]) PERSISTED NOT NULL",
            SqlServerDialect.render(
                alterTable(SqlServerGeneratedOrders) { addColumn(SqlServerGeneratedOrders.storedTotal) },
            ).sql,
        )
        assertFailsWith<UnsupportedDdlOperation> {
            SqlServerDialect.render(alterTable(SqlServerGeneratedOrders) { addColumn(SqlServerGeneratedOrders.id) })
        }
    }

    @Test
    fun rendersIdentityOptionsWithSqlServerNativeDefaultsOnlyAtRenderingTime() {
        assertEquals(
            "CREATE TABLE [identity_default] ([id] BIGINT IDENTITY NOT NULL PRIMARY KEY)",
            SqlServerDialect.render(createTable(sqlServerIdentityTable("identity_default"))).sql,
        )
        assertEquals(
            "CREATE TABLE [identity_start] ([id] BIGINT IDENTITY(1000, 1) NOT NULL PRIMARY KEY)",
            SqlServerDialect.render(createTable(sqlServerIdentityTable("identity_start", startWith = 1000L))).sql,
        )
        assertEquals(
            "CREATE TABLE [identity_increment] ([id] BIGINT IDENTITY(1, -10) NOT NULL PRIMARY KEY)",
            SqlServerDialect.render(createTable(sqlServerIdentityTable("identity_increment", incrementBy = -10L))).sql,
        )
        assertEquals(
            "CREATE TABLE [identity_start_increment] ([id] BIGINT IDENTITY(1000, 10) NOT NULL PRIMARY KEY)",
            SqlServerDialect.render(
                createTable(sqlServerIdentityTable("identity_start_increment", startWith = 1000L, incrementBy = 10L)),
            ).sql,
        )
    }
}

private fun sqlServerIdentityTable(name: String, startWith: Long? = null, incrementBy: Long? = null): Table =
    object : Table(name) {
        val id = long("id").identity(startWith = startWith, incrementBy = incrementBy).primaryKey()
    }

private object SqlServerUsers : Table("user]name", schemaName = "app") {
    val id = long("id").primaryKey()
    val name = string("name")
    val email = column("email", SqlServerTypes.nVarchar(256)).unique()
    val active = boolean("active")
    val identifier = uuid("identifier")
    val nickname = string("nickname").nullable()
    val payload = bytes("payload")
}

private object SqlServerCommentedUsers : Table("commented_users", schemaName = "app", comment = "Application users") {
    val id = long("id").primaryKey().comment("User identifier")
    val email = string("email").comment("Primary user's email\nユーザーの主要メールアドレス")
    val displayName = string("display_name").comment("'; DROP TABLE users; --")
}

private object SqlServerCommentedConstraints : Table("commented_constraints") {
    val email = column("email", SqlServerTypes.nVarchar(256))

    init {
        unique(
            email,
            name = "uq_commented_constraints_email",
            comment = "Email must be unique",
        )
    }
}

private object SqlServerDefaultSchemaComments : Table("default_schema_comments", comment = "Table comment") {
    val id = long("id")
}

private object SqlServerGeneratedOrders : Table("generated_orders") {
    val id = long("id").primaryKey().identity()
    val price = int("price")
    val quantity = int("quantity")
    val virtualTotal = int("virtual_total")
        .generated(price * quantity, GeneratedColumnStorage.Virtual)
        .nullable()
    val storedTotal = int("stored_total").generated(price * quantity, GeneratedColumnStorage.Stored)
}

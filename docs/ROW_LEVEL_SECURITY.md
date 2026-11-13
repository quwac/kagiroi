# Native row-level security

Kagiroi registers native security definitions in `Table.init`, exports independent
security objects, plans security changes, renders native DDL, and sets context on
the transaction's physical session. The database enforces row visibility and write
checks. Query ASTs and SQL predicates are not rewritten by Kagiroi.

| Dialect | Schema DSL | Native DDL | Runtime context |
| --- | --- | --- | --- |
| PostgreSQL / PGlite | `postgresRls` | RLS table state and policies | Transaction-local custom settings |
| SQL Server | `sqlServerRls` | Security policies and filter/block predicates | `SESSION_CONTEXT` |
| Oracle | `oracleVpd` | `DBMS_RLS` policy calls | Explicit trusted setter configuration |
| MySQL / MariaDB / SQLite / D1 | Unsupported | Explicit error | No default adapter |

## PostgreSQL / PGlite

```kotlin
import dev.quwac.kagiroi.postgres.currentSetting
import dev.quwac.kagiroi.postgres.postgresRls
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.sql.eq
import kotlin.uuid.Uuid

object Documents : Table("documents") {
    val id = uuid("id").primaryKey()
    val tenantId = uuid("tenant_id")
    val body = string("body")

    init {
        postgresRls {
            force()
            policy("tenant_isolation") {
                using { tenantId eq currentSetting("app.tenant_id").cast<Uuid>() }
                withCheck { tenantId eq currentSetting("app.tenant_id").cast<Uuid>() }
            }
        }
    }
}
```

`postgresRls {}` implicitly enables RLS, including when it contains no policies.
PostgreSQL's default-deny behavior applies when RLS is enabled without an applicable
policy. `force()` adds owner enforcement; its default is false. PostgreSQL superuser
and `BYPASSRLS` privileges still bypass RLS.

Policies default to `ALL`, `PUBLIC`, and `PERMISSIVE`. Optional selectors are
`forAll()`, `forSelect()`, `forInsert()`, `forUpdate()`, and `forDelete()`.
`restrictive()` changes composition to `RESTRICTIVE`; `permissive()` restores the
default. Roles use `to(role("app_user"), role("support_user"))`, with typed
`Public`, `CurrentUser`, and `SessionUser` selectors in the PostgreSQL package.
Role names are quoted identifiers; special selectors are SQL keywords.

| Command | Allowed clauses |
| --- | --- |
| ALL | USING, WITH CHECK |
| SELECT | USING |
| INSERT | WITH CHECK |
| UPDATE | USING, WITH CHECK |
| DELETE | USING |

Invalid command/clause pairs fail at registration. Clauses may be omitted where
PostgreSQL permits them; native fallback behavior applies. Duplicate policy names,
repeated clauses, and references to other tables or logical computed columns are
rejected. Predicates use the existing typed Boolean expression AST. Unsupported
DDL expressions fail explicitly during rendering.

`currentSetting(name)` returns `SqlExpression<String>` and raises a database error
when the setting is missing. `currentSetting(name, missingOk = true)` returns a
nullable expression. `.cast<Uuid>()` preserves nullability; existing `.castTo(uuidType())`
also works. Custom result mappings use `castTo` with an explicit target.

```sql
ALTER TABLE "documents" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "documents" FORCE ROW LEVEL SECURITY;
CREATE POLICY "tenant_isolation" ON "documents"
USING ("tenant_id" = CAST(current_setting('app.tenant_id') AS UUID))
WITH CHECK ("tenant_id" = CAST(current_setting('app.tenant_id') AS UUID));
```

Policy expressions are schema DDL: literal values are encoded and escaped through
typed expression rendering. Query-time function arguments remain bind parameters.

## SQL Server

```kotlin
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.sqlserver.AfterInsert
import dev.quwac.kagiroi.sqlserver.function
import dev.quwac.kagiroi.sqlserver.sqlServerRls

object ServerDocuments : Table("documents", "dbo") {
    val tenantId = uuid("tenant_id")
    val region = string("region")

    init {
        sqlServerRls("tenant_security", schema = "security") {
            filterPredicate(
                function = function(schema = "security", name = "tenant_predicate"),
                tenantId,
                region,
            )
            blockPredicate(
                function = function(schema = "security", name = "tenant_predicate"),
                timing = AfterInsert,
                tenantId,
                region,
            )
        }
    }
}
```

Policies default to enabled. `disabled()` creates an inactive policy. The policy
schema defaults to the table schema, or `dbo` when the table has no explicit schema.
Function schemas are explicit. Predicate functions must already exist and satisfy
SQL Server's inline table-valued function and schema-binding requirements.
Kagiroi does not generate functions.

Block timings are `AfterInsert`, `AfterUpdate`, `BeforeUpdate`, and `BeforeDelete`.
Arguments are ordered references to physical columns on the declaring table.
The model supports zero or multiple arguments. A policy accepts one filter
predicate and one block predicate per timing for each table.

Contributions from multiple registered tables merge by policy schema and name.
Their enabled states must agree. The canonical policy retains the target table and
operation as predicate identity. Predicate order is normalized; argument order is
preserved.

```sql
CREATE SECURITY POLICY [security].[tenant_security]
ADD FILTER PREDICATE [security].[tenant_predicate]([tenant_id], [region])
    ON [dbo].[documents],
ADD BLOCK PREDICATE [security].[tenant_predicate]([tenant_id], [region])
    ON [dbo].[documents] AFTER INSERT
WITH (STATE = ON);
```

## Oracle

```kotlin
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.oracle.Delete
import dev.quwac.kagiroi.oracle.Insert
import dev.quwac.kagiroi.oracle.Select
import dev.quwac.kagiroi.oracle.Update
import dev.quwac.kagiroi.oracle.oracleVpd

object OracleDocuments : Table("DOCUMENTS", "APP") {
    val tenantId = uuid("TENANT_ID")

    init {
        oracleVpd("tenant_isolation") {
            function(
                schema = "SECURITY",
                packageName = "TENANT_SECURITY",
                name = "TENANT_PREDICATE",
            )
            forStatements(Select, Insert, Update, Delete)
        }
    }
}
```

`packageName` is optional. References retain schema, package, and function names
separately. Function references use physical identifier spelling, as table
references do. Functions and packages must already exist.

Statement types are an unordered native set: `Select`, `Insert`, `Update`,
`Delete`, and `Index`. The default is SELECT, UPDATE, DELETE. `updateCheck()`
validates the new row image for INSERT/UPDATE. Selecting INSERT automatically
sets its mandatory update check. Explicit update checks without INSERT or UPDATE
are rejected. Policies default to enabled; `disabled()` creates an inactive policy.

```sql
BEGIN DBMS_RLS.ADD_POLICY(
    object_schema => 'APP', object_name => 'DOCUMENTS',
    policy_name => 'tenant_isolation', function_schema => 'SECURITY',
    policy_function => '"TENANT_SECURITY"."TENANT_PREDICATE"',
    statement_types => 'DELETE,INSERT,SELECT,UPDATE',
    update_check => TRUE, enable => TRUE
); END;
```

The rendered executable block has no SQL*Plus `/` terminator. Script hosts that
require `/` supply that delimiter separately. `DBMS_RLS` calls have Oracle's native
implicit-commit behavior; wrapping their execution in a Kagiroi transaction does
not make them transactional.

## Registration, export, and security differences

Dialect extensions register immutable security definitions through the table's
metadata bridge. No property declaration is required. A registered schema seals
its tables against subsequent security registration, like other schema metadata.

```kotlin
val desired = schema(Documents)
val projectionJson = desired.project().encode()
val securityDdl = desired.createSecurityObjects()

// All referenced tables and functions must exist first.
db.execute {
    createTable(Documents)
    createSecurityObjects(desired)
}

// Both inputs are ordinary Table-based registries.
val changes = diffSecurity(previousRegistry, desired)
db.executeSecurity(changes)
```

Import `schema` from `dev.quwac.kagiroi.schema`; import `execute`,
`createSecurityObjects`, `diffSecurity`, and `executeSecurity` from
`dev.quwac.kagiroi.sql`.

Canonical security objects contain stable JSON identities and normalized
semantics, with no builder, runtime table instance, mapping lambda, or
implementation class name:

| Canonical object | Stable identity | Definition |
| --- | --- | --- |
| `PostgreSqlRlsTable` | Table qualified name | Enabled and force state |
| `PostgreSqlPolicy` | Table qualified name + policy name | Command, roles, mode, typed expression projection |
| `SqlServerSecurityPolicy` | Policy qualified name | State and merged predicates keyed by table + operation |
| `OracleVpdPolicy` | Object qualified name + policy name | Function reference, statement set, check and enabled state |

Security-bearing projections without backfills use `formatVersion: 2`; projections with
`notNullBackfill` use version 3. Both include
`schema.securityObjects`. Registries without security retain the version-1 export
and identical existing table/column/index/sequence representation. Kagiroi Migration
reads version 2 and includes security in its physical hash,
semantic plan, generated SQL, and artifact delta replay. Older version-1 readers
reject security-bearing projections.

`diffSecurity` compares only security objects. It plans PostgreSQL enable/disable,
force/no-force, create/drop policies, and policy replacements. SQL Server changes
use create/drop policy, native state changes, and add/drop predicates; function or
argument changes replace the affected predicate. Oracle changes use add/drop VPD,
native enabled-state changes, and drop/recreate for configuration changes.

Create tables and referenced functions before applying security additions. Apply
security removals before dropping tables and functions. PostgreSQL RLS enable
precedes FORCE and CREATE POLICY. SQL Server policies covering multiple tables must
be created from the complete registry. `executeSecurity` renders the complete plan
before executing it, so a later unsupported operation cannot partially apply the
plan. DDL execution itself adds no transaction or rollback guarantees.

`diffSecurity` and the projection are the Kagiroi integration boundary.
Kagiroi Migration consumes security-bearing projections in its normal schema export,
diff, generation, and verification pipeline. Function DDL, grants, roles, and user provisioning
remain application-managed prerequisites.

## Runtime context

```kotlin
import dev.quwac.kagiroi.sql.rlsContext

val rows = db.transaction {
    rlsContext("app.tenant_id" to tenantId) {
        Documents.select().all()
    }
}
```

`rlsContext` is available only on `SqlTransaction`. The callback retains normal
Kagiroi query and DML APIs, including table selection on the current execution
receiver. All queries must finish within the callback; returning an uncollected
Flow or a deferred query does not extend the context lifetime.

Values support `String`, `Int`, `Long`, `Boolean`, UUID, and null. Unsupported
values and duplicate keys fail before mutation. SQL text never interpolates
runtime keys or values. PostgreSQL settings are natively text and receive the
scalar's textual representation; SQL Server and Oracle use existing typed bindings.

PostgreSQL keys are complete custom setting names, such as `app.tenant_id`, and
are used verbatim. No `tenant_id` to `app.tenant_id` mapping is performed.
`set_config(..., true)` runs inside a savepoint on the current physical transaction.
Successful scopes restore the previous setting; failed scopes roll back the
savepoint, preserving the original exception and restoring prior state. Native
transaction-local settings cannot survive transaction completion. PostgreSQL may
represent a previously undefined custom setting as an empty string after its first
use. Predicates must treat missing/empty values appropriately; casting an empty
UUID raises an error and does not grant row access.

SQL Server uses bound `sp_set_session_context` calls with `read_only=0`. Scope keys
must initially be empty; preexisting values are rejected. Every attempted key,
including partially failed setup, is cleared with NULL on exit. Clearing runs under
`NonCancellable`. Failed cleanup makes queries and commit unusable and destroys the
physical connection. The Node driver also closes a protected physical connection
before node-mssql can return it after an asynchronous server rollback.

Oracle requires explicit configuration for the existing trusted setter:

```kotlin
import dev.quwac.kagiroi.oracle.OracleRlsContext
import dev.quwac.kagiroi.oracle.function

val tenantContext = OracleRlsContext(
    namespace = "APP_CONTEXT",
    setter = function(
        schema = "SECURITY",
        packageName = "TENANT_SECURITY",
        name = "SET_CONTEXT_VALUE",
    ),
)

val rows = db.transaction {
    rlsContext("TENANT_ID" to tenantId, adapter = tenantContext) {
        OracleDocuments.select().all()
    }
}
```

The namespace is explicit, and keys are attribute names passed verbatim to the
setter. The procedure must accept `(attribute, value)`, set exactly that namespace
attribute, and clear it for NULL. It must accept the supplied native value mappings,
propagate errors, and avoid committing or modifying other context. Kagiroi reads
with `SYS_CONTEXT`, rejects populated keys, and invokes the trusted setter for both
setup and clearing. Failed cleanup marks the transaction unusable and discards the
physical Oracle session when it is released. `CREATE CONTEXT ... USING`, package
definitions, and their privileges remain application-managed.

Nested RLS scopes are explicitly rejected. Built-in drivers also reject overlapping
scopes on the same transaction. Session-local adapters require the driver's physical
connection guard; unsupported drivers fail before context mutation. Do not change
RLS context through raw SQL while a managed scope is active.

## Native feature references

The semantics follow [PostgreSQL CREATE POLICY](https://www.postgresql.org/docs/current/sql-createpolicy.html),
[SQL Server CREATE SECURITY POLICY](https://learn.microsoft.com/en-us/sql/t-sql/statements/create-security-policy-transact-sql),
[sp_set_session_context](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-set-session-context-transact-sql),
and [Oracle DBMS_RLS](https://docs.oracle.com/en/database/oracle/oracle-database/26/arpls/DBMS_RLS.html).
Database ownership, privilege bypasses, and server configuration are enforced by
the database rather than Kotlin's type system.

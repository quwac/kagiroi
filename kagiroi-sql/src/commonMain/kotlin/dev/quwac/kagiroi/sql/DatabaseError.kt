package dev.quwac.kagiroi.sql

/** Stable Kagiroi errors. Driver exceptions are retained only as causes and metadata. */
sealed class DatabaseError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reports an error raised while executing a SQL statement.
 *
 * @property vendorCode database-specific error code, when available.
 * @property metadata normalized diagnostic metadata.
 */
class ExecutionFailure(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

/** Constraint category associated with a database error. */
enum class ConstraintKind {
    /** A uniqueness constraint was violated. */
    UNIQUE,

    /** A foreign-key constraint was violated. */
    FOREIGN_KEY,

    /** A not-null constraint was violated. */
    NOT_NULL,

    /** A check constraint was violated. */
    CHECK,

    /** The constraint category is unknown or not classified. */
    OTHER,
}

/** Normalized database details retained alongside a Kagiroi error.
 *
 * @property sqlState SQLSTATE reported by the database, when available.
 * @property vendorCode database-specific error code, when available.
 * @property attributes additional driver-provided diagnostic values.
 */
data class DatabaseErrorMetadata(
    val sqlState: String? = null,
    val vendorCode: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/** Reports a database constraint violation.
 *
 * @property vendorCode database-specific error code, when available.
 * @property kind classified constraint category.
 * @property metadata normalized diagnostic metadata.
 */
class ConstraintViolation(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val kind: ConstraintKind = ConstraintKind.OTHER,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

/** Reports a value that could not be converted to or from a column's Kotlin type.
 *
 * @property column column name associated with the failed conversion.
 * @property value value that failed conversion.
 * @property expectedType expected Kotlin value type.
 */
class InvalidValueConversion(val column: String, val value: Any?, val expectedType: String, cause: Throwable? = null) :
    DatabaseError("Cannot convert column $column to $expectedType", cause)

/** Reports a failure converting a returned database value into its Kotlin result type.
 *
 * @property vendorCode database-specific error code, when available.
 * @property metadata normalized diagnostic metadata.
 */
class ResultConversionFailure(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

/** Reports that a single-row query returned no rows. */
class ExpectedOneRowButFoundZero : DatabaseError("Expected one row but found zero")

/** Reports that a single-row query returned more than one row.
 *
 * @property observedRows number of rows returned by the query.
 */
class ExpectedOneRowButFoundMultiple(val observedRows: Int) : DatabaseError("Expected one row but found multiple")

/** Reports a failure while managing a transaction. */
class TransactionFailure(message: String, cause: Throwable? = null) : DatabaseError(message, cause)

/** Reports a transaction serialization conflict.
 *
 * @property metadata normalized database diagnostic metadata.
 */
class SerializationFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

/** Reports that a database transaction was selected as a deadlock victim.
 *
 * @property metadata normalized database diagnostic metadata.
 */
class DeadlockFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

/** Reports that a database lock could not be acquired before the configured timeout.
 *
 * @property metadata normalized database diagnostic metadata.
 */
class LockTimeoutFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

/** Reports an error establishing or maintaining a database connection.
 *
 * @property metadata normalized database diagnostic metadata.
 */
class ConnectionFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

/** Reports that the database driver does not support nested transactions. */
class NestedTransactionUnsupported : DatabaseError("Nested transactions are not supported")

/** Reports that an operation was attempted after the database was closed. */
class DatabaseClosed : DatabaseError("Database is closed")

/** Reports use of a database capability that the active driver does not provide.
 *
 * @property capabilityName name of the unavailable capability.
 */
class UnsupportedCapability(val capabilityName: String) :
    DatabaseError("Database capability is not supported: $capabilityName")

/** Reports a DDL operation that the selected dialect cannot express.
 *
 * @property operation DDL operation that was rejected.
 * @property reason dialect limitation that prevented rendering.
 */
class UnsupportedDdlOperation(val operation: String, val reason: String) :
    DatabaseError("DDL operation is not supported: $operation ($reason)")

/** Reports a parameter value that the database driver could not bind.
 *
 * @property vendorCode database-specific error code, when available.
 * @property metadata normalized diagnostic metadata.
 */
class InvalidBinding(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

/** Reports a failure executing a batch of SQL statements.
 *
 * @property vendorCode database-specific error code, when available.
 */
class BatchFailure(message: String, cause: Throwable? = null, val vendorCode: String? = null) :
    DatabaseError(message, cause)

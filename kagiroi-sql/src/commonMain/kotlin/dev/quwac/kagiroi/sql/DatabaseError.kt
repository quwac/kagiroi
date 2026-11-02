package dev.quwac.kagiroi.sql

/** Stable Kagiroi errors. Driver exceptions are retained only as causes and metadata. */
sealed class DatabaseError(message: String, cause: Throwable? = null) : Exception(message, cause)

class ExecutionFailure(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

enum class ConstraintKind {
    UNIQUE,
    FOREIGN_KEY,
    NOT_NULL,
    CHECK,
    OTHER,
}

data class DatabaseErrorMetadata(
    val sqlState: String? = null,
    val vendorCode: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

class ConstraintViolation(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val kind: ConstraintKind = ConstraintKind.OTHER,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

class InvalidValueConversion(val column: String, val value: Any?, val expectedType: String, cause: Throwable? = null) :
    DatabaseError("Cannot convert column $column to $expectedType", cause)

class ResultConversionFailure(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

class ExpectedOneRowButFoundZero : DatabaseError("Expected one row but found zero")

class ExpectedOneRowButFoundMultiple(val observedRows: Int) : DatabaseError("Expected one row but found multiple")

class TransactionFailure(message: String, cause: Throwable? = null) : DatabaseError(message, cause)

class SerializationFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

class DeadlockFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

class LockTimeoutFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

class ConnectionFailure(
    message: String,
    cause: Throwable? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(),
) : DatabaseError(message, cause)

class NestedTransactionUnsupported : DatabaseError("Nested transactions are not supported")

class DatabaseClosed : DatabaseError("Database is closed")

class UnsupportedCapability(val capabilityName: String) :
    DatabaseError("Database capability is not supported: $capabilityName")

class UnsupportedDdlOperation(val operation: String, val reason: String) :
    DatabaseError("DDL operation is not supported: $operation ($reason)")

class InvalidBinding(
    message: String,
    cause: Throwable? = null,
    val vendorCode: String? = null,
    val metadata: DatabaseErrorMetadata = DatabaseErrorMetadata(vendorCode = vendorCode),
) : DatabaseError(message, cause)

class BatchFailure(message: String, cause: Throwable? = null, val vendorCode: String? = null) :
    DatabaseError(message, cause)

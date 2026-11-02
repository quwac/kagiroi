package dev.quwac.kagiroi.schema

/** Internal bridge used by the SQL DDL module to construct one-off index operations. */
@RequiresOptIn(
    message = "This API is reserved for Kagiroi SQL DDL implementations.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class InternalSchemaDdlApi

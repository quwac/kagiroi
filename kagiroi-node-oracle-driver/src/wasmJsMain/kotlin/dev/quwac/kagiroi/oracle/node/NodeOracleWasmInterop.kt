@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("MaximumLineLength", "TooManyFunctions", "UnusedParameter")

package dev.quwac.kagiroi.oracle.node

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber
import kotlin.js.JsString

internal fun oraclePoolConfig(
    connectString: String,
    user: String,
    password: String,
    minConnections: Int,
    maxConnections: Int,
    increment: Int,
    acquisitionTimeoutMilliseconds: Int,
    idleTimeoutSeconds: Int,
): JsAny = js(
    "({ connectString, user, password, poolMin: minConnections, poolMax: maxConnections, " +
        "poolIncrement: increment, queueTimeout: acquisitionTimeoutMilliseconds, poolTimeout: idleTimeoutSeconds })",
)

internal fun emptyOracleObject(): JsAny = js("({})")

internal fun oracleDefaultExport(module: JsAny): JsAny = js("module.default ?? module")

internal fun oracleBindings(
    values: JsArray<JsAny?>,
    kinds: JsArray<JsString>,
    returningNames: JsArray<JsString>,
    returningKinds: JsArray<JsString>,
    returningMaxSizes: JsArray<JsNumber?>,
    oracle: JsAny,
): JsAny = js(
    "(() => { const binds = {}; for (let i = 0; i < values.length; i++) { " +
        "const kind = kinds[i]; const value = values[i]; const name = 'p' + (i + 1); " +
        "if (kind === 'NULL' || kind === 'FLOAT' || kind === 'TEXT' || kind === 'NUMBER_TEXT') binds[name] = value; " +
        "else { const type = kind === 'NUMBER' ? oracle.DB_TYPE_NUMBER : " +
        "kind === 'BOOLEAN' ? oracle.DB_TYPE_BOOLEAN : kind === 'RAW' ? oracle.DB_TYPE_RAW : " +
        "kind === 'BLOB' ? oracle.DB_TYPE_BLOB : kind === 'CLOB' ? oracle.DB_TYPE_CLOB : " +
        "kind === 'NCLOB' ? oracle.DB_TYPE_NCLOB : kind === 'DATE' ? oracle.DB_TYPE_DATE : " +
        "kind === 'TIMESTAMP' ? oracle.DB_TYPE_TIMESTAMP : kind === 'TIMESTAMP_TZ' ? oracle.DB_TYPE_TIMESTAMP_TZ : oracle.DB_TYPE_TIMESTAMP_LTZ; " +
        "binds[name] = { val: value, type: type }; } } " +
        "for (let i = 0; i < returningNames.length; i++) { const kind = returningKinds[i]; " +
        "const type = kind === 'TEXT' || kind === 'DECIMAL' ? oracle.STRING : " +
        "kind === 'BOOLEAN' ? oracle.DB_TYPE_BOOLEAN : oracle.BUFFER; " +
        "const bind = { dir: oracle.BIND_OUT, type: type }; const maxSize = returningMaxSizes[i]; " +
        "if (maxSize != null) bind.maxSize = maxSize; binds[returningNames[i]] = bind; } return binds; })()",
)

internal fun oracleReturningValues(value: JsAny, name: String): JsArray<JsAny?> = js(
    "Array.isArray(value.outBinds?.[name]) ? value.outBinds[name] : " +
        "(() => { throw new Error('Oracle DML RETURNING INTO did not return bind: ' + name); })()",
)

internal fun oracleQueryOptions(oracle: JsAny): JsAny = js(
    "({ outFormat: oracle.OUT_FORMAT_ARRAY, fetchTypeHandler: function(metadata) { " +
        "if (metadata.dbType === oracle.DB_TYPE_NUMBER) return { type: oracle.STRING }; " +
        "if (metadata.dbType === oracle.DB_TYPE_RAW || metadata.dbType === oracle.DB_TYPE_BLOB) return { type: oracle.BUFFER }; " +
        "if (metadata.dbType === oracle.DB_TYPE_CLOB || metadata.dbType === oracle.DB_TYPE_NCLOB) return { type: oracle.STRING }; " +
        "} })",
)

internal fun oracleRowsAffected(value: JsAny): Double? = oracleRowsAffectedRaw(
    value,
)?.unsafeCast<JsNumber>()?.toDouble()

private fun oracleRowsAffectedRaw(value: JsAny): JsAny? =
    js("typeof value.rowsAffected === 'number' ? value.rowsAffected : null")

internal fun oracleMetadata(value: JsAny): JsArray<JsAny> = js("value.metaData == null ? [] : value.metaData")

internal fun oracleResultRows(value: JsAny): JsArray<JsArray<JsAny?>> = js("value.rows == null ? [] : value.rows")

internal fun oracleColumnName(value: JsAny): String = js("value.name")

internal fun oracleIsNumberColumn(value: JsAny, oracle: JsAny): Boolean = js("value.dbType === oracle.DB_TYPE_NUMBER")

internal fun oracleJsType(value: JsAny): String = js("typeof value")

internal fun oracleIsBytes(value: JsAny): Boolean = js("value instanceof Uint8Array || Buffer.isBuffer(value)")

internal fun oracleBooleanValue(value: JsAny): Boolean = js("value")

internal fun oracleStringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")

internal fun oracleNumberProperty(value: JsAny, name: String): Double? =
    oracleNumberPropertyRaw(value, name)?.unsafeCast<JsNumber>()?.toDouble()

private fun oracleNumberPropertyRaw(value: JsAny, name: String): JsAny? =
    js("typeof value[name] === 'number' ? value[name] : null")

internal fun oracleBooleanProperty(value: JsAny, name: String): Boolean? =
    js("typeof value[name] === 'boolean' ? value[name] : null")

internal fun oracleCloseOptions(discard: Boolean): JsAny =
    if (discard) discardOracleCloseOptions() else emptyOracleObject()

private fun discardOracleCloseOptions(): JsAny = js("({ drop: true })")

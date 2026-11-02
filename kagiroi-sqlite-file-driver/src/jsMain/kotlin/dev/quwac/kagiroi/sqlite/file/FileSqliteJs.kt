@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions", "UnsafeCastFromDynamic")

package dev.quwac.kagiroi.sqlite.file

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue

@JsModule("better-sqlite3")
@JsNonModule
private external val BetterSqlite3: dynamic

@JsModule("node:buffer")
@JsNonModule
private external object NodeBufferModule {
    val Buffer: dynamic
}

internal actual fun openFileSqliteEngine(settings: FileSqliteSettings): FileSqliteEngine = translate(
    FileSqliteFailureKind.OPEN,
) {
    val options = js("({})")
    options.readonly = settings.readOnly
    options.fileMustExist = settings.fileMustExist
    options.timeout = settings.busyTimeoutMilliseconds
    val database = createDatabase(BetterSqlite3, settings.path, options)
    database.defaultSafeIntegers(true)
    JsFileSqliteEngine(database)
}

private class JsFileSqliteEngine(private val database: dynamic) : FileSqliteEngine {
    override fun execute(sql: String, parameters: List<FileSqliteBinding>): Long = translate(
        FileSqliteFailureKind.EXECUTION,
    ) {
        val result = runStatement(database.prepare(sql), parameters.map(::bindingValue).toTypedArray())
        affectedRows(result.changes)
    }

    override fun query(sql: String, parameters: List<FileSqliteBinding>): List<FileSqliteEngineRow> {
        val statement = translate(FileSqliteFailureKind.EXECUTION) {
            database.prepare(sql).safeIntegers(true).raw(true)
        }
        val rows = translate(FileSqliteFailureKind.EXECUTION) {
            allStatement(statement, parameters.map(::bindingValue).toTypedArray()) as Array<Array<dynamic>>
        }
        return translate(FileSqliteFailureKind.RESULT_CONVERSION) {
            val columnNames = (statement.columns() as Array<dynamic>).map { it.name as String }
            rows.map { row ->
                require(row.size == columnNames.size) { "SQLite result value count does not match columns" }
                FileSqliteEngineRow(columnNames, row.map(::toSqliteValue))
            }
        }
    }

    override fun close() {
        translate(FileSqliteFailureKind.CLOSE) { database.close() }
    }
}

private fun bindingValue(value: FileSqliteBinding): dynamic = when (value) {
    SqlitePhysicalValue.Null -> null
    is SqlitePhysicalValue.Integer -> bigInt(value.decimal)
    is SqlitePhysicalValue.Real -> value.value
    is SqlitePhysicalValue.Text -> value.value
    is SqlitePhysicalValue.Blob -> NodeBufferModule.Buffer.from(value.value.toUint8Array())
}

private fun toSqliteValue(value: dynamic): FileSqliteValue = when {
    value == null -> SqlitePhysicalValue.Null

    jsTypeOf(value) == "string" -> SqlitePhysicalValue.Text(value as String)

    jsTypeOf(value) == "bigint" -> SqlitePhysicalValue.Integer(value.toString())

    jsTypeOf(value) == "number" -> {
        val number = value as Double
        require(number.isFinite()) { "SQLite returned a non-finite numeric value" }
        if (isSafeInteger(number)) {
            SqlitePhysicalValue.Integer(number.toLong().toString())
        } else {
            SqlitePhysicalValue.Real(number)
        }
    }

    isByteArray(value) -> SqlitePhysicalValue.Blob(toByteArray(value))

    else -> throw IllegalArgumentException("better-sqlite3 returned unsupported ${jsTypeOf(value)} value")
}

private fun affectedRows(value: dynamic): Long {
    val count = value as? Number ?: throw IllegalArgumentException("better-sqlite3 returned a non-numeric change count")
    require(isSafeInteger(count)) { "better-sqlite3 returned an unsafe change count" }
    return count.toLong()
}

private fun toByteArray(value: dynamic): ByteArray = ByteArray(value.length as Int) { index ->
    ((value[index] as Number).toInt() and BYTE_MASK).toByte()
}

private external class Uint8Array(length: Int) {
    operator fun set(index: Int, value: Int)
}

private fun ByteArray.toUint8Array(): Uint8Array = Uint8Array(size).also { output ->
    forEachIndexed { index, value -> setByte(output, index, value.toInt() and BYTE_MASK) }
}

private inline fun <T> translate(kind: FileSqliteFailureKind, block: () -> T): T = try {
    block()
} catch (error: FileSqliteDriverException) {
    throw error
} catch (error: Throwable) {
    throw error.asFileSqliteDriverException(kind)
}

private fun Throwable.asFileSqliteDriverException(kind: FileSqliteFailureKind): FileSqliteDriverException {
    val native = asDynamic()
    val code = native.code as? String
    val extendedResultCode = (native.errno as? Number)?.toInt()
    val attributes = buildMap {
        (native.errno as? Number)?.let { put("errno", it.toInt().toString()) }
        (native.message as? String)?.let { put("nativeMessage", it) }
    }
    return FileSqliteDriverException(
        kind = kind,
        sqliteCode = code,
        extendedResultCode = extendedResultCode,
        attributes = attributes,
        message = native.message as? String ?: message ?: toString(),
        cause = this,
    )
}

@Suppress("UnusedParameter")
private fun createDatabase(constructor: dynamic, path: String, options: dynamic): dynamic = js(
    "new constructor(path, options)",
)

@Suppress("UnusedParameter")
private fun runStatement(statement: dynamic, parameters: Array<dynamic>): dynamic =
    js("statement.run.apply(statement, parameters)")

@Suppress("UnusedParameter")
private fun allStatement(statement: dynamic, parameters: Array<dynamic>): dynamic =
    js("statement.all.apply(statement, parameters)")

@Suppress("UnusedParameter")
private fun bigInt(value: String): dynamic = js("BigInt(value)")

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Number): Boolean = js("Number.isSafeInteger(value)")

@Suppress("UnusedParameter")
private fun isByteArray(value: dynamic): Boolean = js("value instanceof Uint8Array || value instanceof Int8Array")

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private const val BYTE_MASK = 0xff

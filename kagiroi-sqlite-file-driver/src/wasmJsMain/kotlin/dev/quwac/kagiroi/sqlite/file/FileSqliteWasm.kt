@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "PropertyName", "TooGenericExceptionCaught", "TooManyFunctions")

package dev.quwac.kagiroi.sqlite.file

import dev.quwac.kagiroi.sqlite.SqlitePhysicalValue
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsException
import kotlin.js.JsNumber
import kotlin.js.JsString
import kotlin.js.toDouble
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlin.js.unsafeCast

@JsModule("better-sqlite3")
private external val BetterSqlite3: JsAny

@JsModule("node:buffer")
private external object NodeBufferModule : JsAny {
    val Buffer: NodeBufferConstructor
}

private external interface NodeBufferConstructor : JsAny {
    fun from(value: Uint8Array): JsAny
}

private external interface NativeDatabase : JsAny {
    fun prepare(sql: String): NativeStatement

    fun defaultSafeIntegers(enabled: Boolean): NativeDatabase

    fun close(): NativeDatabase
}

private external interface NativeStatement : JsAny {
    fun run(vararg parameters: JsAny?): NativeRunResult

    fun all(vararg parameters: JsAny?): JsArray<JsArray<JsAny?>>

    fun safeIntegers(enabled: Boolean): NativeStatement

    fun raw(enabled: Boolean): NativeStatement

    fun columns(): JsArray<NativeColumn>
}

private external interface NativeRunResult : JsAny {
    val changes: JsAny
}

private external interface NativeColumn : JsAny {
    val name: String
}

private external class Uint8Array(length: Int) : JsAny {
    val length: Int
    operator fun get(index: Int): Int
    operator fun set(index: Int, value: Int)
}

internal actual fun openFileSqliteEngine(settings: FileSqliteSettings): FileSqliteEngine = translate(
    FileSqliteFailureKind.OPEN,
) {
    val database = createDatabase(
        constructor = BetterSqlite3,
        path = settings.path,
        readonly = settings.readOnly,
        fileMustExist = settings.fileMustExist,
        timeout = settings.busyTimeoutMilliseconds,
    )
    database.defaultSafeIntegers(true)
    WasmFileSqliteEngine(database)
}

private class WasmFileSqliteEngine(private val database: NativeDatabase) : FileSqliteEngine {
    override fun execute(sql: String, parameters: List<FileSqliteBinding>): Long = translate(
        FileSqliteFailureKind.EXECUTION,
    ) {
        database.prepare(sql).run(*parameters.map(::bindingValue).toTypedArray()).changes.toAffectedRows()
    }

    override fun query(sql: String, parameters: List<FileSqliteBinding>): List<FileSqliteEngineRow> {
        val statement = translate(FileSqliteFailureKind.EXECUTION) {
            database.prepare(sql).safeIntegers(true).raw(true)
        }
        val rows = translate(FileSqliteFailureKind.EXECUTION) {
            statement.all(*parameters.map(::bindingValue).toTypedArray()).toList().filterNotNull()
        }
        return translate(FileSqliteFailureKind.RESULT_CONVERSION) {
            val columnNames = statement.columns().toList().filterNotNull().map(NativeColumn::name)
            rows.map { row ->
                val values = row.toList()
                require(values.size == columnNames.size) { "SQLite result value count does not match columns" }
                FileSqliteEngineRow(columnNames, values.map(::toSqliteValue))
            }
        }
    }

    override fun close() {
        translate(FileSqliteFailureKind.CLOSE) { database.close() }
    }
}

private fun bindingValue(value: FileSqliteBinding): JsAny? = when (value) {
    SqlitePhysicalValue.Null -> null
    is SqlitePhysicalValue.Integer -> bigInt(value.decimal)
    is SqlitePhysicalValue.Real -> value.value.toJsNumber()
    is SqlitePhysicalValue.Text -> value.value.toJsString()
    is SqlitePhysicalValue.Blob -> NodeBufferModule.Buffer.from(value.value.toUint8Array())
}

private fun toSqliteValue(value: JsAny?): FileSqliteValue = when {
    value == null -> SqlitePhysicalValue.Null

    jsType(value) == "string" -> SqlitePhysicalValue.Text(value.unsafeCast<JsString>().toString())

    jsType(value) == "bigint" -> SqlitePhysicalValue.Integer(bigIntString(value))

    jsType(value) == "number" -> {
        val number = value.unsafeCast<JsNumber>().toDouble()
        require(number.isFinite()) { "SQLite returned a non-finite numeric value" }
        if (isSafeInteger(number)) {
            SqlitePhysicalValue.Integer(number.toLong().toString())
        } else {
            SqlitePhysicalValue.Real(number)
        }
    }

    isByteArray(value) -> SqlitePhysicalValue.Blob(value.toByteArray())

    else -> throw IllegalArgumentException("better-sqlite3 returned unsupported ${jsType(value)} value")
}

private fun JsAny.toAffectedRows(): Long {
    require(jsType(this) == "number") { "better-sqlite3 returned a non-numeric change count" }
    val value = unsafeCast<JsNumber>().toDouble()
    require(isSafeInteger(value)) { "better-sqlite3 returned an unsafe change count" }
    return value.toLong()
}

private fun JsAny.toByteArray(): ByteArray {
    val bytes = unsafeCast<Uint8Array>()
    return ByteArray(bytes.length) { index -> (bytes[index] and BYTE_MASK).toByte() }
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
    val thrown = (this as? JsException)?.thrownValue
    val code = thrown?.let { stringProperty(it, "code") }
    val errno = thrown?.let { numberProperty(it, "errno") }?.toInt()
    val attributes = buildMap {
        errno?.let { put("errno", it.toString()) }
        thrown?.let { stringProperty(it, "message") }?.let { put("nativeMessage", it) }
    }
    return FileSqliteDriverException(
        kind = kind,
        sqliteCode = code,
        extendedResultCode = errno,
        attributes = attributes,
        message = thrown?.let { stringProperty(it, "message") } ?: message ?: toString(),
        cause = this,
    )
}

@Suppress("UnusedParameter")
private fun createDatabase(
    constructor: JsAny,
    path: String,
    readonly: Boolean,
    fileMustExist: Boolean,
    timeout: Int,
): NativeDatabase = js("new constructor(path, { readonly, fileMustExist, timeout })")

@Suppress("UnusedParameter")
private fun jsType(value: JsAny): String = js("typeof value")

@Suppress("UnusedParameter")
private fun bigInt(value: String): JsAny = js("BigInt(value)")

@Suppress("UnusedParameter")
private fun bigIntString(value: JsAny): String = js("value.toString()")

@Suppress("UnusedParameter")
private fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")

@Suppress("UnusedParameter")
private fun isByteArray(value: JsAny): Boolean = js("value instanceof Uint8Array || value instanceof Int8Array")

@Suppress("UnusedParameter")
private fun stringProperty(value: JsAny, name: String): String? = js(
    "typeof value[name] === 'string' ? value[name] : null",
)

@Suppress("UnusedParameter")
private fun numberProperty(value: JsAny, name: String): Double? = js(
    "typeof value[name] === 'number' ? value[name] : null",
)

@Suppress("UnusedParameter")
private fun setByte(output: Uint8Array, index: Int, value: Int) {
    js("output[index] = value")
}

private const val BYTE_MASK = 0xff

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter")

package dev.quwac.kagiroi.sqlite.file

import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.Test

@JsModule("node:fs")
private external object NodeFileSystem : JsAny {
    fun mkdtempSync(prefix: String): String

    fun rmSync(path: String, options: JsAny)
}

@JsModule("node:os")
private external object NodeOperatingSystem : JsAny {
    fun tmpdir(): String
}

@JsModule("node:path")
private external object NodePath : JsAny {
    fun join(vararg paths: String): String
}

class FileSqliteIntegrationTest {
    @Test
    fun fileBackedDriverContract() = runTest {
        val directory = NodeFileSystem.mkdtempSync(NodePath.join(NodeOperatingSystem.tmpdir(), "kagiroi-sqlite-"))
        try {
            fileSqliteDriverContract(NodePath.join(directory, "app.db"))
        } finally {
            NodeFileSystem.rmSync(directory, removalOptions())
        }
    }
}

private fun removalOptions(): JsAny = js("({ recursive: true, force: true })")

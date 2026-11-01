@file:Suppress("UnsafeCastFromDynamic", "UnusedParameter")

package dev.quwac.kagiroi.sqlite.file

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@JsModule("node:fs")
@JsNonModule
private external object NodeFileSystem {
    fun mkdtempSync(prefix: String): String

    fun rmSync(path: String, options: dynamic)
}

@JsModule("node:os")
@JsNonModule
private external object NodeOperatingSystem {
    fun tmpdir(): String
}

@JsModule("node:path")
@JsNonModule
private external object NodePath {
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

private fun removalOptions(): dynamic = js("({ recursive: true, force: true })")

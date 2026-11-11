@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter", "UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.quwac.kagiroi.d1

import kotlin.js.JsAny

internal actual fun replaceD1HttpFetch(json: String, status: Int): Any = replaceFetch(json, status)
internal actual fun restoreD1HttpFetch(previous: Any) {
    restoreFetch(previous)
}
internal actual fun capturedD1HttpRequest(fixture: Any): D1HttpRequest? {
    val native = fixture
    return if (hasRequest(
            native,
        )
    ) {
        D1HttpRequest(requestUrl(native), requestAuthorization(native), requestBody(native))
    } else {
        null
    }
}

private fun replaceFetch(json: String, status: Int): JsAny = js(
    """(() => {
        const fixture = { previous: globalThis.fetch, request: null };
        globalThis.fetch = (url, options) => {
            fixture.request = { url, authorization: options.headers.Authorization, body: options.body };
            return Promise.resolve({ ok: status >= 200 && status < 300, status,
                json: () => Promise.resolve(JSON.parse(json)) });
        };
        return fixture;
    })()""",
)

private fun restoreFetch(fixture: JsAny): Unit = js("{ globalThis.fetch = fixture.previous; }")
private fun hasRequest(fixture: JsAny): Boolean = js("fixture.request !== null")
private fun requestUrl(fixture: JsAny): String = js("fixture.request.url")
private fun requestAuthorization(fixture: JsAny): String = js("fixture.request.authorization")
private fun requestBody(fixture: JsAny): String = js("fixture.request.body")

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.quwac.kagiroi.postgres.node

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.js.JsException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies Promise completion and error propagation at the coroutine boundary. */
class NodePostgresPromiseTest {
    /** Resumes the coroutine with the fulfilled value. */
    @Test
    fun returnsTheFulfilledValue() = runTest {
        assertEquals("completed", Promise.resolve("completed".toJsString()).awaitCompletion().toString())
    }

    /** Retains the original Promise rejection. */
    @Test
    fun propagatesTheOriginalRejection() = runTest {
        val original = createPromiseProbe().error
        val failure = assertFailsWith<JsException> {
            Promise.reject(original).awaitCompletion()
        }
        assertSame(original, failure.thrownValue)
    }

    /** Keeps downstream failures from being replaced by a second resume. */
    @Test
    fun preservesExceptionsThrownAfterPromiseCompletion() = runTest {
        val probe = createPromiseProbe()
        var completionCount = 0
        val operation: suspend () -> Unit = { probe.promise.awaitCompletion() }

        operation.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                result.getOrThrow()
                completionCount++
                throw probe.error.asJsException()
            }
        })

        val failure = probe.failure().awaitCompletion()
        assertTrue(probe.isExpectedFailure(failure))
        assertEquals(1, completionCount)
    }
}

private external interface PromiseProbe : JsAny {
    val promise: Promise<JsString>
    val error: JsAny
    fun failure(): Promise<JsAny?>
    fun isExpectedFailure(value: JsAny?): Boolean
}

// Observe the terminal rejection without letting the test runner consume an unhandled rejection.
// Tracking catch descendants also detects the second resume in a then(...).catch(...) bridge.
private fun createPromiseProbe(): PromiseProbe = js(
    """
    (() => {
        const error = new Error('downstream failure');
        let failure;
        const promise = {
            then(onFulfilled, onRejected) {
                const next = Promise.resolve('completed').then(onFulfilled, onRejected);
                failure = next.then(() => null, error => error);
                next.catch = handler => {
                    const caught = Promise.prototype.catch.call(next, handler);
                    failure = caught.then(() => null, error => error);
                    return caught;
                };
                return next;
            }
        };
        return { promise, error, failure: () => failure, isExpectedFailure: value => value === error };
    })()
""",
)

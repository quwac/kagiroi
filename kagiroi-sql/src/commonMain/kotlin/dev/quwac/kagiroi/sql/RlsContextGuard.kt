package dev.quwac.kagiroi.sql

/** Driver-owned lifecycle guard for a transaction using session-local RLS context.
 * @property onRelease removes host protection after successful cleanup.
 * @property onAcquire host hook which protects against asynchronous server rollback and pool release.
 */
class RlsContextGuard(private val onAcquire: () -> Unit = {}, private val onRelease: () -> Unit = {}) :
    RlsContextConnectionGuard {
    private var active = false
    private var failure: Throwable? = null

    /** Whether a failed cleanup requires physical-connection discard. */
    val requiresDiscard: Boolean get() = failure != null
    override fun invalidateRlsContext(cause: Throwable) {
        failure = cause
    }
    override fun acquireRlsContext() {
        requireUsable()
        check(!active) { "Overlapping RLS context scopes are not supported" }
        onAcquire()
        active = true
    }
    override fun releaseRlsContext() {
        if (!requiresDiscard) onRelease()
        active = false
    }

    /** Rejects queries and commit after failed security-context cleanup. */
    fun requireUsable() {
        failure?.let { throw ExecutionFailure("RLS context cleanup failed; transaction cannot continue", it) }
    }
}

package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability

/** Represents d1 batch capability. */
data object D1BatchCapability : DatabaseCapability

/** Represents d1 sessions capability. */
data object D1SessionsCapability : DatabaseCapability

/** D1-only capabilities which do not belong in the generic SQL contract. */
object D1Capabilities {
    /** Value associated with batch. */
    val Batch = CapabilityKey<D1BatchCapability>("d1-batch")

    /** Value associated with sessions. */
    val Sessions = CapabilityKey<D1SessionsCapability>("d1-sessions")
}

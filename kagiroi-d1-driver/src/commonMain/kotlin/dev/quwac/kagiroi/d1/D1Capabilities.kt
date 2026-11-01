package dev.quwac.kagiroi.d1

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability

data object D1BatchCapability : DatabaseCapability

data object D1SessionsCapability : DatabaseCapability

/** D1-only capabilities which do not belong in the generic SQL contract. */
object D1Capabilities {
    val Batch = CapabilityKey<D1BatchCapability>("d1-batch")
    val Sessions = CapabilityKey<D1SessionsCapability>("d1-sessions")
}

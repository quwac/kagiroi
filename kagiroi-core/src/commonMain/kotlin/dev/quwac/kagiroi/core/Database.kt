package dev.quwac.kagiroi.core

/** A database endpoint without assumptions about connections, SQL, or transactions. */
interface Database {
    /** Returns an optional driver-defined capability. Keys use instance identity. */
    fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C?
}

/** An operation or feature exposed by a database implementation. */
interface DatabaseCapability

/** A typed identity key for a database capability. */
class CapabilityKey<C : DatabaseCapability>(val name: String) {
    init {
        require(name.isNotBlank()) { "Capability name must not be blank" }
    }

    override fun toString(): String = "CapabilityKey($name)"
}

/** Returns a capability or fails before an unsupported operation is attempted. */
fun <C : DatabaseCapability> Database.requireCapability(key: CapabilityKey<C>): C =
    capability(key) ?: error("Database capability is not available: ${key.name}")

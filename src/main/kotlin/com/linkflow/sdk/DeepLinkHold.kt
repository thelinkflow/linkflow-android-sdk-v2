package com.linkflow.sdk

/** The two ways a deep link reaches the app. */
enum class DeepLinkKind {
    /** Resolved after install from the attribution request (an attributed result carrying a link). */
    DEFERRED,

    /** Opened while the app is installed: App Link, custom scheme or `handleDeepLink`. */
    DIRECT,
}

/**
 * Holds deep-link deliveries while the host app is not ready to navigate
 * (2.2.0+), e.g. during onboarding, a splash screen or before a navigation
 * container has mounted.
 *
 * Bounded by design: only the latest item of each [DeepLinkKind] is kept, so a
 * user tapping several links while held results in exactly one navigation per
 * kind on release. Release returns the kept items in the order they arrived.
 *
 * Pure Kotlin so it is unit-testable on the JVM without an Android runtime.
 */
internal class DeepLinkHoldQueue<T : Any> {

    private val lock = Any()
    private var held = false
    private val pending = LinkedHashMap<DeepLinkKind, T>()

    fun hold() = synchronized(lock) { held = true }

    val isHeld: Boolean get() = synchronized(lock) { held }

    /** Number of deliveries currently waiting for release (at most one per kind). */
    fun size(): Int = synchronized(lock) { pending.size }

    /**
     * Queues [item] when held. Returns true when it was queued, in which case
     * the caller must not deliver it now; false means "not held, deliver now".
     */
    fun offer(kind: DeepLinkKind, item: T): Boolean = synchronized(lock) {
        if (!held) return false
        // Remove first so iteration order reflects the latest arrival.
        pending.remove(kind)
        pending[kind] = item
        true
    }

    /** Stops holding and hands back what was queued, oldest arrival first. */
    fun release(): List<T> = synchronized(lock) {
        held = false
        val out = pending.values.toList()
        pending.clear()
        out
    }
}

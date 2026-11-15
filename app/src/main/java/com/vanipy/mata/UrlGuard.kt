package com.vanipy.mata

import java.net.URI

/**
 * Decides which URLs the capture engine may load.
 *
 * The queue arrives over a messaging channel; even a trusted contact's
 * account could be compromised or a queue malformed, so the app refuses
 * anything that is not a public http(s) page. Captures of private-network
 * targets (router admin pages, LAN services, loopback) could leak internal
 * details into screenshots, so those hosts are blocked both at queue parse
 * time and on WebView redirects.
 *
 * Literal-IP hosts are checked against private/loopback ranges. Hostnames
 * are not resolved here (DNS rebinding is out of scope for v1; the paired
 * contact is the trust boundary).
 */
object UrlGuard {

    /**
     * Test-only escape hatch for the instrumented fixture: permits
     * loopback http targets so the capture pipeline can be exercised
     * against a server running inside the test process. Hard release
     * boundary: the flag is honored only in DEBUG builds, so a release
     * build can never load loopback targets no matter how the flag is
     * set. Only androidTest sets it, and only around the fixture. Other
     * private ranges stay blocked even with the flag on.
     */
    @Volatile
    internal var allowLoopbackForTests = false

    internal fun loopbackUnlocked(): Boolean =
        BuildConfig.DEBUG && allowLoopbackForTests

    fun isAllowed(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty()) return false
        if (isPrivateHost(host)) {
            if (loopbackUnlocked() && isLoopbackHost(host)) return true
            return false
        }
        return true
    }

    internal fun isLoopbackHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare == "::1") return true
        val parts = host.split(".")
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } &&
            parts[0].toInt() == 127
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) return true
        // IPv4 literal ranges.
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 10 || a == 127 || a == 0) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a == 100 && b in 64..127) return true // CGNAT
            if (a >= 224) return true // multicast/reserved/broadcast
        }
        // IPv6 literals arrive bracketed.
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare == "::1" || bare.startsWith("fe80") || bare.startsWith("fc") ||
            bare.startsWith("fd")
        ) return true
        return false
    }
}

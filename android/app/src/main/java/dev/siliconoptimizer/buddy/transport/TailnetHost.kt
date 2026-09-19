package dev.siliconoptimizer.buddy.transport

/**
 * Which addresses this app will ever dial.
 *
 * The Mac binds its Silicon Buddy listener to a tailnet address or loopback and nothing
 * else — its own `isBindableTailnetAddress` refuses anything wider, because binding one
 * of those is how a private API becomes a public one. This is the same rule on the
 * client side, and it is the thing that makes a scanned QR safe: a code is just text
 * until something dials the host inside it, and a link that says
 * `siliconbuddy://pair?host=evil.example.com` must not get that far.
 *
 * Parsed as an address, never scanned for digits: `100.64.0.1.evil.example.com`
 * contains a tailnet address and is a name someone else controls.
 */
object TailnetHost {

    const val EXPLANATION =
        "Silicon Buddy only pairs over your tailnet. " +
            "Use the Mac's Tailscale address (100.x.y.z), or 10.0.2.2 in the emulator."

    /**
     * True for the addresses the Mac can actually be listening on:
     * loopback, 10.0.2.2 (the emulator's name for its host), Tailscale's IPv4 range
     * 100.64.0.0/10, and Tailscale's IPv6 range fd7a:115c:a1e0::/48.
     */
    fun isAllowed(host: String): Boolean {
        val trimmed = host.trim().trim('[', ']').lowercase()
        if (trimmed == "localhost") return true
        ipv4Bytes(trimmed)?.let { bytes ->
            if (bytes[0] == 127) return true
            if (bytes.contentEquals(intArrayOf(10, 0, 2, 2))) return true
            return bytes[0] == 100 && bytes[1] in 64..127
        }
        ipv6Groups(trimmed)?.let { words ->
            if (words.contentEquals(intArrayOf(0, 0, 0, 0, 0, 0, 0, 1))) return true
            return words[0] == 0xfd7a && words[1] == 0x115c && words[2] == 0xa1e0
        }
        return false
    }

    /** Four octets, or null when this is not a dotted-quad IPv4 literal. */
    fun ipv4Bytes(text: String): IntArray? {
        val parts = text.split(".")
        if (parts.size != 4) return null
        val bytes = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            // "064" is 52 to one parser and 64 to another. An address two readers
            // disagree about is not an address this app will dial.
            if (part != "0" && part.startsWith("0")) return null
            val value = part.toIntOrNull() ?: return null
            if (value > 255) return null
            bytes[index] = value
        }
        return bytes
    }

    /** Eight groups, or null when this is not an IPv6 literal. Handles one `::`. */
    fun ipv6Groups(text: String): IntArray? {
        if (!text.contains(":")) return null
        if (text.contains("%")) return null // A zone index names an interface.

        val halves = text.split("::")
        if (halves.size > 2) return null

        fun groups(piece: String): IntArray? {
            if (piece.isEmpty()) return IntArray(0)
            val parts = piece.split(":")
            val result = IntArray(parts.size)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 4) return null
                result[index] = part.toIntOrNull(16) ?: return null
            }
            return result
        }

        val head = groups(halves[0]) ?: return null
        if (halves.size == 1) return if (head.size == 8) head else null
        val tail = groups(halves[1]) ?: return null
        if (head.size + tail.size > 7) return null
        return head + IntArray(8 - head.size - tail.size) + tail
    }
}

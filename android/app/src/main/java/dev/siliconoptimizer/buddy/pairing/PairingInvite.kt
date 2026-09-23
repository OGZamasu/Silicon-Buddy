package dev.siliconoptimizer.buddy.pairing

import android.net.Uri
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TailnetHost
import dev.siliconoptimizer.buddy.transport.TransportError

/**
 * What the QR on the Mac's screen encodes:
 * `siliconbuddy://pair?host=<tailnet address>&port=<port>&code=<6 digits>`.
 * The same three things, typed in by hand, come through [typed].
 */
data class PairingInvite(
    val host: String,
    val port: Int,
    val code: String,
) {
    /** The other direction, so the format has one definition and the tests round-trip it. */
    fun toUriString(): String = "siliconbuddy://pair?host=$host&port=$port&code=$code"

    /** Where it points, as a dialog names it: an IPv6 host in brackets. */
    val displayAddress: String get() = "${TailnetHost.forUrl(host)}:$port"

    /**
     * Spends the code at `POST /buddy/pair` — with no token, because the code is the
     * credential — and returns where and how this device talks to the Mac from now on.
     * However the invite arrived, this is the one way it becomes a token.
     */
    suspend fun exchange(
        deviceName: String,
        platform: String,
        client: (ServerConfig) -> ControlTransport = { ControlClient(it) },
    ): ServerConfig {
        if (!TailnetHost.isAllowed(host)) throw TransportError.Forbidden(TailnetHost.EXPLANATION)
        val paired = client(ServerConfig(host, port, token = "")).pair(code, deviceName, platform)
        return ServerConfig(
            host = host,
            port = paired.port,
            token = paired.token,
            macName = paired.macName,
            deviceID = paired.deviceID,
            scope = DeviceScope.from(paired.scope),
        )
    }

    sealed class ParseError(message: String) : Exception(message) {
        data object NotAUri : ParseError("That isn't a Silicon Buddy code.")
        data class WrongScheme(val scheme: String?) :
            ParseError("That code is for ${scheme ?: "something else"}, not Silicon Buddy.")
        data class WrongAction(val action: String?) :
            ParseError("That Silicon Buddy code isn't a pairing code.")
        data class Missing(val field: String) :
            ParseError("The pairing code is missing its $field.")
        data class BadPort(val value: String) : ParseError("\"$value\" isn't a port number.")
        data class BadCode(val value: String) :
            ParseError("\"$value\" isn't a six-digit pairing code.")
        data object LinkNotTyped :
            ParseError("That's a pairing link. Silicon Buddy asks before it uses one.")
        data object NoAddress :
            ParseError("Type the Mac's address too. Silicon Optimizer shows it beside the code.")
        data class HostNotOnTailnet(val host: String) :
            ParseError("$host isn't a tailnet address. " + TailnetHost.EXPLANATION)
    }

    companion object {
        /**
         * Parses the scanned text. Strict on purpose: a QR code is scanned from whatever
         * happens to be in frame, so anything that is not exactly our invite is rejected
         * rather than half-understood.
         *
         * Pure string work rather than [Uri] parsing, so the same code runs in a JVM
         * unit test and on the device.
         */
        fun parse(text: String): PairingInvite {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw ParseError.NotAUri

            val schemeSeparator = trimmed.indexOf(':')
            if (schemeSeparator <= 0) throw ParseError.NotAUri
            val scheme = trimmed.substring(0, schemeSeparator)
            if (!scheme.equals("siliconbuddy", ignoreCase = true)) {
                throw ParseError.WrongScheme(scheme)
            }

            var rest = trimmed.substring(schemeSeparator + 1).trimStart('/')
            val queryStart = rest.indexOf('?')
            if (queryStart < 0) throw ParseError.WrongAction(rest.lowercase().ifEmpty { null })
            val action = rest.substring(0, queryStart).trim('/').lowercase()
            if (action != "pair") throw ParseError.WrongAction(action.ifEmpty { null })
            rest = rest.substring(queryStart + 1)

            val fields = rest.split('&').mapNotNull { pair ->
                val equals = pair.indexOf('=')
                if (equals <= 0) null else pair.substring(0, equals) to
                    pair.substring(equals + 1).trim()
            }.toMap()

            val host = fields["host"]?.takeIf { it.isNotEmpty() } ?: throw ParseError.Missing("host")
            // A code is only text until something dials the host inside it. This is
            // where `siliconbuddy://pair?host=evil.example.com&…` stops.
            if (!TailnetHost.isAllowed(host)) throw ParseError.HostNotOnTailnet(host)
            val portText = fields["port"]?.takeIf { it.isNotEmpty() }
                ?: throw ParseError.Missing("port")
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw ParseError.BadPort(portText)
            val code = fields["code"]?.takeIf { it.isNotEmpty() } ?: throw ParseError.Missing("code")
            if (code.length != 6 || !code.all { it in '0'..'9' }) throw ParseError.BadCode(code)

            return PairingInvite(host, port, code)
        }

        /** The same thing, for an Intent's data URI. */
        fun parse(uri: Uri): PairingInvite = parse(uri.toString())

        /**
         * The Mac's Silicon Buddy listener. Fixed across launches, so a code read off the
         * screen needs only the address the Mac prints beside it.
         */
        const val DEFAULT_PORT = 8788

        /** Whether pasted text is a whole pairing link rather than an address. */
        fun isLink(text: String): Boolean =
            text.trim().startsWith("siliconbuddy:", ignoreCase = true)

        /**
         * A `siliconbuddy://pair` link pasted where an address goes: the invite when
         * [text] is one, null when it is not a link at all, and the parse error when it
         * is a link that doesn't parse.
         *
         * The code form's own Pair button never spends one. Someone else chose a link's
         * host — the Mac has no button that copies one — so it goes to the confirmation a
         * tapped link gets, with its "only pair with a code on your own Mac's screen".
         */
        fun pasted(text: String): PairingInvite? = if (isLink(text)) parse(text) else null

        /**
         * [text] with every decimal digit, from any script, as its ASCII one, and
         * everything else but a space dropped. For the code and port fields: a keyboard
         * that types ٤ or ４ puts 4 in the field, rather than a character [typed] then
         * refuses as "not a six-digit pairing code" without saying why.
         */
        fun asciiDigits(text: String, keepSpaces: Boolean = false): String = buildString {
            for (character in text) {
                val value = Character.digit(character, 10)
                if (value >= 0) {
                    append('0' + value)
                } else if (keepSpaces && character == ' ') {
                    append(' ')
                }
            }
        }

        /**
         * What someone types when the camera won't do: the code the Mac shows under its
         * QR, the address beside it, and — only if it isn't [DEFAULT_PORT] — a port.
         *
         * Held to the same rules as a scan, because it ends at the same `/buddy/pair`.
         * The code may carry the space the Mac shows it with, or a dash; nothing else.
         * A link is refused here: see [pasted].
         */
        fun typed(address: String, code: String, port: String = ""): PairingInvite {
            if (isLink(address)) throw ParseError.LinkNotTyped
            var host = address.trim()
            var portText = port.trim()
            if (host.startsWith("[") && host.contains("]:")) {
                // `[fd7a:115c:a1e0::9]:8788`: the only way to put a port after IPv6.
                portText = host.substringAfter("]:").trim()
                host = host.substringBefore("]:").removePrefix("[")
            } else if (host.count { it == ':' } == 1) {
                // `100.64.0.9:8788`, as the address is often written. One colon only:
                // a bare IPv6 address is all colons and is never split.
                portText = host.substringAfter(':').trim()
                host = host.substringBefore(':').trim()
            }
            host = host.removePrefix("[").removeSuffix("]")
            if (host.isEmpty()) throw ParseError.NoAddress
            if (!TailnetHost.isAllowed(host)) throw ParseError.HostNotOnTailnet(host)
            val portNumber = if (portText.isEmpty()) {
                DEFAULT_PORT
            } else {
                portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw ParseError.BadPort(portText)
            }
            val digits = code.filterNot { it.isWhitespace() || it == '-' }
            if (digits.length != 6 || !digits.all { it in '0'..'9' }) {
                throw ParseError.BadCode(code.trim())
            }
            return PairingInvite(host, portNumber, digits)
        }
    }
}

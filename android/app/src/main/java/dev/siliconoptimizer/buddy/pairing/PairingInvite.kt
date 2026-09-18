package dev.siliconoptimizer.buddy.pairing

import android.net.Uri

/**
 * What the QR on the Mac's screen encodes:
 * `siliconbuddy://pair?host=<tailnet address>&port=<port>&code=<6 digits>`.
 */
data class PairingInvite(
    val host: String,
    val port: Int,
    val code: String,
) {
    /** The other direction, so the format has one definition and the tests round-trip it. */
    fun toUriString(): String = "siliconbuddy://pair?host=$host&port=$port&code=$code"

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
    }
}

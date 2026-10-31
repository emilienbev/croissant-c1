package com.bleural.croissant.setup

import java.net.URLDecoder

/**
 * What croissant-server's QR code holds, printed at its start:
 * `croissant://server?host=<ipv4>&port=<port>&name=<name>`, and `&token=<token>` for its LAN
 * address (server/croissant_server/pairing.py), the values encoded as Python's urlencode writes
 * them (a space as `+`, the rest UTF-8 percent escapes). [name] and [token] are empty when the
 * code gives none. A token that is not one the server makes refuses the whole code.
 */
data class ServerQr(val host: String, val port: Int, val name: String, val token: String = "") {
    companion object {
        private const val PREFIX = "croissant://server?"
        /** URL-safe base64, as Python's `secrets.token_urlsafe` writes it; the server's is 32 characters. */
        private val TOKEN = Regex("[A-Za-z0-9_-]{16,128}")

        fun parse(text: String): ServerQr? {
            val t = text.trim()
            if (!t.startsWith(PREFIX, ignoreCase = true)) return null
            val fields = mutableMapOf<String, String>()
            for (pair in t.substring(PREFIX.length).split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val value = try { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") } catch (e: IllegalArgumentException) { return null }
                fields.putIfAbsent(pair.substring(0, eq), value)
            }
            val host = fields["host"]?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c == '/' } } ?: return null
            val port = fields["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val token = fields["token"]?.let { t -> t.takeIf { TOKEN.matches(it) } ?: return null }.orEmpty()
            return ServerQr(host, port, fields["name"]?.trim().orEmpty(), token)
        }
    }
}

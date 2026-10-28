package com.bleural.croissant.setup

import java.net.URLDecoder

/**
 * What croissant-server's QR code holds, printed at its start:
 * `croissant://server?host=<ipv4>&port=<port>&name=<name>`, the values encoded as Python's
 * urlencode writes them (a space as `+`, the rest UTF-8 percent escapes). [name] is empty
 * when the code gives none.
 */
data class ServerQr(val host: String, val port: Int, val name: String) {
    companion object {
        private const val PREFIX = "croissant://server?"

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
            return ServerQr(host, port, fields["name"]?.trim().orEmpty())
        }
    }
}

package com.bleural.croissant.wifi

/**
 * What a Wi-Fi QR code holds, the format phones share a network with and routers print:
 * `WIFI:T:WPA;S:<ssid>;P:<password>;H:false;;`. Fields in any order; `\` escapes `;`, `,`,
 * `:`, `"` and itself. T is WPA (which covers WPA2 and WPA3 as phones write it), SAE, WEP
 * or nopass.
 */
data class WifiQr(val ssid: String, val security: Security, val password: String?) {
    /** As the list would have it, so it joins the way a tapped row does. */
    fun asNet(inRange: WifiNet?): WifiNet = inRange ?: WifiNet(ssid, security, level = -1, netId = null, connected = false)

    companion object {
        fun parse(text: String): WifiQr? {
            if (!text.startsWith("WIFI:", ignoreCase = true)) return null
            val fields = mutableMapOf<Char, String>()
            val body = text.substring(5)
            var i = 0
            while (i < body.length) {
                if (body[i] == ';') { i++; continue }
                val colon = body.indexOf(':', i)
                if (colon != i + 1) break
                val key = body[i].uppercaseChar()
                val value = StringBuilder()
                var j = colon + 1
                while (j < body.length && body[j] != ';') {
                    if (body[j] == '\\' && j + 1 < body.length) j++
                    value.append(body[j]); j++
                }
                fields[key] = value.toString()
                i = j + 1
            }
            val ssid = fields['S']?.takeIf { it.isNotEmpty() } ?: return null
            val security = when (fields['T']?.uppercase()) {
                null, "", "NOPASS" -> Security.OPEN
                "SAE" -> Security.SAE
                "WEP" -> Security.WEP
                else -> Security.PSK
            }
            return WifiQr(ssid, security, fields['P']?.takeIf { security != Security.OPEN })
        }
    }
}

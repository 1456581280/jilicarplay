package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    private val secret = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val namedDevice = Regex("(?i)(phone|device|peer|host)?name[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    fun redact(line: String): String? {
        if (line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return null
        // carlito: Dedicated network diagnostics retain RFC1918 IPv4 to compare subnet paths.
        // All other logs, public addresses, hardware identities and secrets remain redacted.
        val localIps = mutableListOf<String>()
        val networkLine = Regex("(?:^|\\s)LOCAL_NETWORK (path|path_change|peer|route|listener|accepted|protocol|discovery|probe|publication|coverage|config|monitor)\\b")
            .containsMatchIn(line)
        val protected = if (networkLine) address.replace(line) { match ->
            val octets = match.value.split('.').mapNotNull(String::toIntOrNull)
            val privateIp = octets.size == 4 && octets.all { it in 0..255 } &&
                (octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 ||
                    octets[0] == 192 && octets[1] == 168)
            if (privateIp) { localIps.add(match.value); "[localip${localIps.lastIndex}]" } else "[ip]"
        } else line
        var safe = protected.replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]")
        localIps.forEachIndexed { index, value -> safe = safe.replace("[localip$index]", value) }
        return safe.take(700)
    }
}

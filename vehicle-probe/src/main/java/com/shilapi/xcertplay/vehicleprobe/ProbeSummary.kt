// carlito | DiPlay vehicle probe integration. GD source, GPL-3.0.
package com.shilapi.xcertplay.vehicleprobe

internal data class ProbeSummary(val total: Int = 0, val readable: Int = 0, val invalid: Int = 0) {
    val unavailable: Int get() = (total - readable - invalid).coerceAtLeast(0)

    companion object {
        fun parse(report: String): ProbeSummary {
            val xui = Regex("verified_rows=(\\d+) unavailable_rows=\\d+ total_rows=(\\d+)").find(report)
            val vhal = Regex("configs=\\d+ entries=(\\d+)").find(report)
            var invalid = 0
            var readXui = 0
            var xuiRows = 0
            report.lineSequence().filter { it.startsWith("\"adapt_api\"") ||
                it.startsWith("\"ecarx_service\"") || it.startsWith("\"direct_binder\"") }.forEach { line ->
                val fields = csvFields(line)
                if (fields.size >= 14) {
                    xuiRows++
                    val status = fields[8]
                    val number = fields[7].toDoubleOrNull()
                    val invalidNumber = number != null && (!number.isFinite() ||
                        (number != 0.0 && kotlin.math.abs(number) < 1e-20) ||
                        (fields[1] == "function" && number == 255.0))
                    if (status == "INVALID_VALUE" || (status == "READ_OK" && invalidNumber)) invalid++
                    else if (status == "READ_OK") readXui++
                }
            }
            val vhalRows = report.lineSequence().dropWhile {
                !it.startsWith("property_hex,property_id,area_id,")
            }.drop(1)
            val readVhal = vhalRows.count { line ->
                if (!line.startsWith("\"0x")) false else {
                    val value = csvFields(line).getOrNull(8).orEmpty()
                    if (value.startsWith("INVALID_VALUE")) invalid++
                    value.isNotBlank() && listOf("UNAVAILABLE", "SKIPPED", "TIMEOUT", "ERROR", "EMPTY", "INVALID_VALUE")
                        .none(value::startsWith)
                }
            }
            val total = (xui?.groupValues?.get(2)?.toIntOrNull() ?: 0) +
                (vhal?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            val readable = (if (xuiRows > 0) readXui else xui?.groupValues?.get(1)?.toIntOrNull() ?: 0) + readVhal
            return ProbeSummary(total, readable.coerceAtMost(total), invalid.coerceAtMost(total))
        }
    }
}

// Reports contain quoted ranges, embedded commas and escaped quotes.
internal fun csvFields(line: String): List<String> {
    val fields = mutableListOf<String>()
    val value = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                value.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                fields += value.toString()
                value.setLength(0)
            }
            else -> value.append(char)
        }
        index++
    }
    fields += value.toString()
    return fields
}

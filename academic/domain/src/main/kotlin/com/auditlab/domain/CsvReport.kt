package com.auditlab.domain

/** Pure serialization of a detached snapshot. Document selection/writing belongs to I09. */
object CsvReport {
    const val HEADER = "eventId,timestampEpochMs,kind,direction,isSimulated,consentId,policyVersion"

    fun render(snapshot: List<Event>): String = buildString {
        append(HEADER).append("\r\n")
        snapshot.toList()
            .sortedWith(compareByDescending<Event> { it.timestampEpochMs }.thenBy { it.eventId.toString() })
            .forEach { event ->
                val cells = listOf(
                    event.eventId.toString(), event.timestampEpochMs.toString(),
                    event.kind.name, event.direction.name, event.isSimulated.toString(),
                    event.consent.consentId.toString(), event.consent.policyVersion,
                )
                append(cells.joinToString(",", transform = ::encodeCell)).append("\r\n")
            }
    }

    /** Tested with synthetic text; the event model does not accept free-form user content. */
    internal fun encodeCell(value: String): String {
        val significant = value.dropWhile {
            it.isWhitespace() || it.isISOControl() || it == '\uFEFF' || it == '\u200B'
        }
        val formula = significant.firstOrNull() in setOf('=', '+', '-', '@') ||
            value.firstOrNull() in setOf('\t', '\r', '\n')
        val safe = if (formula) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else safe
    }
}

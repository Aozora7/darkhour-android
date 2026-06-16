package one.aozora.darkhour.ui.actogram

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatActogramDateTime(
    instant: Instant,
    offset: ZoneOffset,
    use24HourTime: Boolean,
    useIsoDateTime: Boolean = false,
    locale: Locale = Locale.getDefault(),
): String {
    if (useIsoDateTime) {
        return instant.atOffset(offset)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
    }
    val date = instant.atOffset(offset)
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(locale) else character.toString()
        }
    return "$date ${formatActogramClock(instant, offset, use24HourTime, locale)}"
}

internal fun formatActogramDate(
    date: LocalDate,
    useIsoDateTime: Boolean,
    locale: Locale = Locale.getDefault(),
): String {
    if (useIsoDateTime) return date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(locale) else character.toString()
        }
}

internal fun formatActogramClock(
    instant: Instant,
    offset: ZoneOffset,
    use24HourTime: Boolean,
    locale: Locale = Locale.getDefault(),
): String {
    val pattern = if (use24HourTime) "HH:mm" else "h:mm a"
    return instant.atOffset(offset).format(DateTimeFormatter.ofPattern(pattern, locale))
}

internal fun formatActogramRowLabel(
    instant: Instant,
    offset: ZoneOffset,
    use24HourTime: Boolean,
    useIsoDateTime: Boolean = false,
    locale: Locale = Locale.getDefault(),
): String {
    if (useIsoDateTime) {
        return instant.atOffset(offset)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
    }
    val pattern = if (use24HourTime) "MMM d, yyyy HH:mm" else "MMM d, yyyy h:mm a"
    return instant.atOffset(offset)
        .format(DateTimeFormatter.ofPattern(pattern, locale))
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(locale) else character.toString()
        }
}

internal fun formatActogramAxisHour(hour: Int, use24HourTime: Boolean): String {
    val normalized = ((hour % 24) + 24) % 24
    if (use24HourTime) return normalized.toString().padStart(2, '0')

    val displayHour = when (val value = normalized % 12) {
        0 -> 12
        else -> value
    }
    return "$displayHour${if (normalized < 12) " AM" else " PM"}"
}

internal fun actogramMaxLabelWidthDp(
    showDateLabels: Boolean,
    tauMode: Boolean,
    useIsoDateTime: Boolean,
): Float = when {
    !showDateLabels -> 10f
    tauMode && useIsoDateTime -> 122f
    tauMode -> 136f
    useIsoDateTime -> 78f
    else -> 92f
}

internal fun actogramMinLabelWidthDp(
    showDateLabels: Boolean,
    tauMode: Boolean,
): Float = when {
    !showDateLabels -> 10f
    tauMode -> 64f
    else -> 48f
}

internal fun actogramLabelTextSizePx(
    rowHeightPx: Float,
    density: Float,
    tauMode: Boolean,
): Float {
    val preferredSizePx = (if (tauMode) 8f else 10f) * density
    return preferredSizePx.coerceAtMost(rowHeightPx * 0.5f)
}

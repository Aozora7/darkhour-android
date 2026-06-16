package one.aozora.darkhour.core.circadian.csf

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal fun LocalDate.utcStartInstant(): Instant = atStartOfDay().toInstant(ZoneOffset.UTC)

internal fun LocalDate.startInstant(offset: ZoneOffset): Instant = atStartOfDay().toInstant(offset)

internal fun daysBetween(firstDate: LocalDate, date: LocalDate): Int =
    ChronoUnit.DAYS.between(firstDate, date).toInt()

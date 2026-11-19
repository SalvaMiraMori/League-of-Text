package com.leagueoftext.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Formatters {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun localDate(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    /** "dissabte, 14 de novembre" (segons l'idioma del dispositiu). */
    fun dayHeader(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(date)

    /** "18:00" o "6:00 PM" segons l'idioma. */
    fun time(instant: Instant, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(instant.atZone(zone))

    /** "14/11/26" o equivalent. */
    fun shortDate(instant: Instant, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(instant.atZone(zone))

    fun clock(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun initials(text: String): String =
        text.split(' ', '.', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
            .ifBlank { "?" }
}

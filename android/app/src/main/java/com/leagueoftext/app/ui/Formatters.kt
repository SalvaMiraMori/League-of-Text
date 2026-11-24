package com.leagueoftext.app.ui

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

object Formatters {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun localDate(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    /** "dissabte, 14 de novembre" (segons l'idioma del dispositiu). */
    fun dayHeader(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(date)

    /** Els set dies de la setmana de [date], començant pel primer dia segons l'idioma (dilluns o diumenge). */
    fun weekOf(date: LocalDate, locale: Locale = Locale.getDefault()): List<LocalDate> {
        val start = date.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
        return List(7) { start.plusDays(it.toLong()) }
    }

    /** "Novembre 2026". */
    fun monthTitle(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        capitalized(DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(date), locale)

    /** "16 de nov. – 22 de nov." */
    fun weekRange(week: List<LocalDate>, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofPattern("d MMM", locale)
        return "${format.format(week.first())} – ${format.format(week.last())}"
    }

    /** "Dl.", "Mon"... */
    fun weekdayShort(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        capitalized(day.getDisplayName(TextStyle.SHORT_STANDALONE, locale), locale)

    /** "DL", "M"... per a la capçalera de la graella mensual. */
    fun weekdayNarrow(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        day.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)

    fun capitalized(text: String, locale: Locale = Locale.getDefault()): String =
        text.replaceFirstChar { it.titlecase(locale) }

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

package com.leagueoftext.app.ui.esports

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leagueoftext.app.R
import com.leagueoftext.app.data.CalendarMode
import com.leagueoftext.app.data.Leagues
import com.leagueoftext.app.data.Match
import com.leagueoftext.app.ui.Formatters
import com.leagueoftext.app.ui.theme.LeagueColors
import com.leagueoftext.app.ui.theme.RiotColors
import java.time.LocalDate
import java.time.YearMonth

/**
 * Calendari de partits, alternativa a la llista de dies plegables (s'activa al Perfil).
 *
 * - **Mensual:** graella del mes; en tocar un dia se'n mostren els partits a sota.
 * - **Setmanal:** franja amb els set dies i, a sota, tots els partits de la setmana per dies.
 *   En tocar un dia de la franja s'obre la vista diària d'aquell dia.
 * - **Diària:** els partits del dia agrupats per hora d'inici.
 *
 * Sota cada dia hi ha un punt per competició, del color del seu logo i més gran com més
 * partits té aquell dia. Si el filtre té una sola competició, en canvi, hi ha un punt per
 * partit (fins a tres).
 *
 * Els partits es pinten com a elements de la [androidx.compose.foundation.lazy.LazyColumn]
 * de la pantalla, perquè només es componguin els que són visibles.
 */
internal fun LazyListScope.calendarItems(
    tab: EsportsTab,
    sections: List<DaySection>,
    mode: CalendarMode,
    leagueOrder: List<String>,
    singleLeague: Boolean,
    selected: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onSelectMode: (CalendarMode) -> Unit,
    matchItem: @Composable LazyItemScope.(Match) -> Unit,
) {
    val byDate = sections.associate { it.date to it.matches }
    val dotsByDate = byDate.mapValues { (_, matches) -> calendarDots(matches, singleLeague, leagueOrder) }

    item(key = "calendar-controls") {
        Column(Modifier.padding(top = 12.dp)) {
            CalendarModeSelector(mode = mode, onSelect = onSelectMode)
            CalendarNavigator(mode = mode, selected = selected, today = today, onSelectDate = onSelectDate)
        }
    }

    when (mode) {
        CalendarMode.MONTH -> {
            item(key = "calendar-month") {
                MonthGrid(selected = selected, today = today, byDate = byDate, dotsByDate = dotsByDate, onSelect = onSelectDate)
            }
            item(key = "calendar-month-day-$tab-$selected") {
                CalendarDayHeader(date = selected, today = today)
            }
            val matches = byDate[selected].orEmpty()
            if (matches.isEmpty()) noMatches("calendar-month-empty-$selected", R.string.calendar_no_matches_day)
            matchItems(tab, matches, matchItem)
        }

        CalendarMode.WEEK -> {
            val week = Formatters.weekOf(selected)
            item(key = "calendar-week") {
                WeekStrip(
                    week = week,
                    selected = selected,
                    today = today,
                    byDate = byDate,
                    dotsByDate = dotsByDate,
                    onOpenDay = { date ->
                        onSelectDate(date)
                        onSelectMode(CalendarMode.DAY)
                    },
                )
            }
            val days = week.filter { byDate.containsKey(it) }
            if (days.isEmpty()) noMatches("calendar-week-empty-${week.first()}", R.string.calendar_no_matches_week)
            days.forEach { date ->
                item(key = "calendar-week-day-$tab-$date") { CalendarDayHeader(date = date, today = today) }
                matchItems(tab, byDate.getValue(date), matchItem)
            }
        }

        CalendarMode.DAY -> {
            val matches = byDate[selected].orEmpty().sortedBy { it.startTime }
            if (matches.isEmpty()) noMatches("calendar-day-empty-$selected", R.string.calendar_no_matches_day)
            matches.groupBy { Formatters.time(it.startTime) }.forEach { (time, slot) ->
                item(key = "calendar-slot-$tab-$selected-$time") { TimeSlotHeader(time) }
                matchItems(tab, slot, matchItem)
            }
        }
    }
}

private fun LazyListScope.matchItems(
    tab: EsportsTab,
    matches: List<Match>,
    matchItem: @Composable LazyItemScope.(Match) -> Unit,
) {
    items(matches, key = { "$tab-${it.id}" }) { matchItem(it) }
}

private fun LazyListScope.noMatches(key: String, @StringRes text: Int) {
    item(key = key) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = RiotColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
        )
    }
}

/** Selector Mes · Setmana · Dia. */
@Composable
private fun CalendarModeSelector(mode: CalendarMode, onSelect: (CalendarMode) -> Unit) {
    val options = listOf(
        CalendarMode.MONTH to R.string.calendar_month,
        CalendarMode.WEEK to R.string.calendar_week,
        CalendarMode.DAY to R.string.calendar_day,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RiotColors.Surface)
            .padding(4.dp),
    ) {
        options.forEach { (option, label) ->
            val isSelected = option == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) RiotColors.Gold else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.Black else RiotColors.TextSecondary,
                )
            }
        }
    }
}

/** Fletxes per anar al mes, la setmana o el dia anterior/següent, amb el títol del període i "Avui". */
@Composable
private fun CalendarNavigator(
    mode: CalendarMode,
    selected: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    val title = when (mode) {
        CalendarMode.MONTH -> Formatters.monthTitle(selected)
        CalendarMode.WEEK -> Formatters.weekRange(Formatters.weekOf(selected))
        CalendarMode.DAY -> Formatters.capitalized(Formatters.dayHeader(selected))
    }
    val showsToday = when (mode) {
        CalendarMode.MONTH -> YearMonth.from(selected) == YearMonth.from(today)
        CalendarMode.WEEK -> today in Formatters.weekOf(selected)
        CalendarMode.DAY -> selected == today
    }
    fun shift(amount: Long): LocalDate = when (mode) {
        CalendarMode.MONTH -> selected.plusMonths(amount)
        CalendarMode.WEEK -> selected.plusWeeks(amount)
        CalendarMode.DAY -> selected.plusDays(amount)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSelectDate(shift(-1)) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.calendar_previous),
                tint = RiotColors.TextPrimary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = RiotColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (!showsToday) {
            Text(
                text = stringResource(R.string.calendar_today),
                style = MaterialTheme.typography.labelMedium,
                color = RiotColors.Gold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(RiotColors.Chip)
                    .clickable(role = Role.Button) { onSelectDate(today) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        IconButton(onClick = { onSelectDate(shift(1)) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.calendar_next),
                tint = RiotColors.TextPrimary,
            )
        }
    }
}

/** Graella del mes de [selected], amb setmanes completes (els dies d'altres mesos, atenuats). */
@Composable
private fun MonthGrid(
    selected: LocalDate,
    today: LocalDate,
    byDate: Map<LocalDate, List<Match>>,
    dotsByDate: Map<LocalDate, List<CalendarDot>>,
    onSelect: (LocalDate) -> Unit,
) {
    val month = YearMonth.from(selected)
    val firstWeek = Formatters.weekOf(month.atDay(1))
    val weeks = generateSequence(firstWeek) { week -> Formatters.weekOf(week.last().plusDays(1)) }
        .takeWhile { week -> !week.first().isAfter(month.atEndOfMonth()) }
        .toList()

    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            firstWeek.forEach { day ->
                Text(
                    text = Formatters.weekdayNarrow(day.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = RiotColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    MonthDayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isSelected = date == selected,
                        isToday = date == today,
                        count = byDate[date]?.size ?: 0,
                        dots = dotsByDate[date].orEmpty(),
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = RiotColors.Divider, thickness = 1.dp)
    }
}

@Composable
private fun MonthDayCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    count: Int,
    dots: List<CalendarDot>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = dayDescription(date, count)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = isSelected
                role = Role.Button
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (isSelected) RiotColors.Gold else Color.Transparent)
                .then(if (isToday && !isSelected) Modifier.border(1.5.dp, RiotColors.Gold, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isSelected -> Color.Black
                    !inMonth -> RiotColors.TextTertiary
                    isToday -> RiotColors.Gold
                    else -> RiotColors.TextPrimary
                },
            )
        }
        Spacer(Modifier.height(3.dp))
        MatchDots(dots = dots, dimmed = !inMonth)
    }
}

/** Franja de la setmana: dia, número i punts segons els partits que hi ha. */
@Composable
private fun WeekStrip(
    week: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    byDate: Map<LocalDate, List<Match>>,
    dotsByDate: Map<LocalDate, List<CalendarDot>>,
    onOpenDay: (LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        week.forEach { date ->
            val count = byDate[date]?.size ?: 0
            val isSelected = date == selected
            val description = dayDescription(date, count)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) RiotColors.SurfaceHigh else RiotColors.Surface)
                    .then(if (date == today) Modifier.border(1.5.dp, RiotColors.Gold, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onOpenDay(date) }
                    .clearAndSetSemantics {
                        contentDescription = description
                        this.selected = isSelected
                        role = Role.Button
                    }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = Formatters.weekdayShort(date.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = RiotColors.TextSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        date == today -> RiotColors.Gold
                        count == 0 -> RiotColors.TextTertiary
                        else -> RiotColors.TextPrimary
                    },
                )
                Spacer(Modifier.height(4.dp))
                MatchDots(dots = dotsByDate[date].orEmpty())
            }
        }
    }
}

/** Un punt del calendari: color de la competició i mida segons els partits. */
private data class CalendarDot(val color: Color, val size: Dp)

/**
 * Punts d'un dia. Amb una sola competició al filtre, un per partit (fins a tres);
 * si no, un per competició, en l'ordre de la fila de lligues, que creix amb els partits:
 * 1 partit → 4 dp, 2 → 6 dp, 3 o més → 8 dp.
 */
private fun calendarDots(matches: List<Match>, singleLeague: Boolean, leagueOrder: List<String>): List<CalendarDot> {
    if (matches.isEmpty()) return emptyList()
    val perLeague = matches.groupingBy { match -> match.league?.let(Leagues::canonicalKey) }.eachCount()
    if (singleLeague) {
        val color = LeagueColors.forKey(perLeague.keys.first())
        return List(matches.size.coerceAtMost(3)) { CalendarDot(color, 5.dp) }
    }
    return perLeague.entries
        .sortedBy { (key, _) -> leagueOrder.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        .map { (key, count) ->
            val size = when (count) {
                1 -> 4.dp
                2 -> 6.dp
                else -> 8.dp
            }
            CalendarDot(LeagueColors.forKey(key), size)
        }
}

@Composable
private fun MatchDots(dots: List<CalendarDot>, dimmed: Boolean = false) {
    Row(
        modifier = Modifier
            .height(8.dp)
            .alpha(if (dimmed) 0.4f else 1f),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dots.forEach { dot ->
            Box(Modifier.size(dot.size).clip(CircleShape).background(dot.color))
        }
    }
}

@Composable
private fun CalendarDayHeader(date: LocalDate, today: LocalDate) {
    Text(
        text = Formatters.capitalized(Formatters.dayHeader(date)),
        style = MaterialTheme.typography.titleSmall,
        color = if (date == today) RiotColors.Gold else RiotColors.TextSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Hora d'inici a la vista diària, amb una línia com a les agendes. */
@Composable
private fun TimeSlotHeader(time: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = MaterialTheme.typography.titleSmall, color = RiotColors.Gold)
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(color = RiotColors.Divider, thickness = 1.dp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun dayDescription(date: LocalDate, count: Int): String =
    "${Formatters.dayHeader(date)}, ${pluralStringResource(R.plurals.calendar_matches, count, count)}"

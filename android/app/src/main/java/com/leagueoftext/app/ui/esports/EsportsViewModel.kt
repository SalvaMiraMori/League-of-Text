package com.leagueoftext.app.ui.esports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leagueoftext.app.AppContainer
import com.leagueoftext.app.data.CalendarMode
import com.leagueoftext.app.data.Feed
import com.leagueoftext.app.data.League
import com.leagueoftext.app.data.Leagues
import com.leagueoftext.app.data.Match
import com.leagueoftext.app.ui.Formatters
import com.leagueoftext.app.ui.components.ManualRefresh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

enum class EsportsTab { UPCOMING, LIVE, RESULTS }

data class DaySection(val date: LocalDate, val matches: List<Match>)

data class EsportsUiState(
    val tab: EsportsTab = EsportsTab.UPCOMING,
    val leagues: List<League> = emptyList(),
    /** Competicions triades al filtre. Buit = totes. */
    val selectedLeagues: Set<String> = emptySet(),
    val sections: List<DaySection> = emptyList(),
    val liveCount: Int = 0,
    val loading: Boolean = true,
    val error: Boolean = false,
    val subscriptions: Set<String> = emptySet(),
    val hideScores: Boolean = true,
    val revealed: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val calendarView: Boolean = false,
    val calendarMode: CalendarMode = CalendarMode.MONTH,
)

private data class Filters(
    val selectedLeagues: Set<String>,
    val subscriptions: Set<String>,
    val hideScores: Boolean,
    val revealed: Set<String>,
    val calendarView: Boolean,
    val calendarMode: CalendarMode,
)

class EsportsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.matches

    /** `null` = encara no triada: s'obre "En directe" si n'hi ha cap, si no "Pròximament". */
    private val selectedTab = MutableStateFlow<EsportsTab?>(null)
    private val selectedLeagues = MutableStateFlow<Set<String>>(emptySet())
    private val revealed = MutableStateFlow<Set<String>>(emptySet())
    private val manualRefresh = ManualRefresh(this)

    /** Totes les lligues vistes en qualsevol pestanya, per conservar-ne el logotip. */
    private val seenLeagues = MutableStateFlow<Map<String, League>>(emptyMap())

    private val tab = combine(selectedTab, repository.live) { chosen, live ->
        chosen ?: if (live.data.isNotEmpty()) EsportsTab.LIVE else EsportsTab.UPCOMING
    }

    // Només s'escolta la llista de la pestanya visible (i la de directe, per al punt vermell).
    private val tabFeed = tab.flatMapLatest { current ->
        when (current) {
            EsportsTab.UPCOMING -> repository.scheduled
            EsportsTab.LIVE -> repository.live
            EsportsTab.RESULTS -> repository.completed
        }.map { feed -> current to feed }
    }

    private val filters = combine(
        selectedLeagues,
        container.subscriptions.subscriptions,
        container.settings.hideScores,
        revealed,
        combine(container.settings.calendarView, container.settings.calendarMode, ::Pair),
    ) { league, subs, hide, shown, (calendar, mode) -> Filters(league, subs, hide, shown, calendar, mode) }

    val uiState: StateFlow<EsportsUiState> =
        combine(tabFeed, repository.live, filters, manualRefresh.refreshing) { (currentTab, feed), live, f, refreshing ->
            buildState(currentTab, feed, live, f).copy(refreshing = refreshing)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EsportsUiState())

    fun selectTab(tab: EsportsTab) {
        selectedTab.value = tab
    }

    /** Estirar la llista cap avall des de dalt. */
    fun refresh() = manualRefresh.launch { repository.refreshLists() }

    /** Afegeix o treu una competició del filtre (se'n poden triar diverses). */
    fun toggleLeague(key: String) {
        selectedLeagues.update { if (key in it) it - key else it + key }
    }

    /** Treu el filtre: es mostren totes les competicions. */
    fun selectAllLeagues() {
        selectedLeagues.value = emptySet()
    }

    fun revealScore(matchId: String) {
        revealed.update { it + matchId }
    }

    fun setCalendarMode(mode: CalendarMode) = container.settings.setCalendarMode(mode)

    fun toggleSubscription(matchId: String) {
        container.subscriptions.toggle(matchId)
    }

    private fun rememberLeagues(matches: List<Match>) {
        val found = matches.mapNotNull { it.league }
        if (found.isEmpty()) return
        seenLeagues.update { current ->
            var next = current
            for (league in found) {
                val key = Leagues.canonicalKey(league)
                val old = next[key]
                if (old == null || (old.image.isBlank() && league.image.isNotBlank())) next = next + (key to league)
            }
            next
        }
    }

    private fun buildState(
        tab: EsportsTab,
        feed: Feed<List<Match>>,
        live: Feed<List<Match>>,
        f: Filters,
    ): EsportsUiState {
        rememberLeagues(feed.data + live.data)
        val leagues = Leagues.merge(seenLeagues.value.values)
        val chosen = f.selectedLeagues.filterTo(mutableSetOf()) { key -> leagues.any { it.key == key } }
        val visible = if (chosen.isEmpty()) feed.data else feed.data.filter { match ->
            match.league?.let(Leagues::canonicalKey) in chosen
        }
        val sections = visible
            .groupBy { Formatters.localDate(it.startTime) }
            .map { (date, matches) -> DaySection(date, matches) }
        return EsportsUiState(
            tab = tab,
            leagues = leagues,
            selectedLeagues = chosen,
            sections = sections,
            liveCount = live.data.size,
            loading = feed.loading,
            error = feed.error != null,
            subscriptions = f.subscriptions,
            hideScores = f.hideScores,
            revealed = f.revealed,
            calendarView = f.calendarView,
            calendarMode = f.calendarMode,
        )
    }
}

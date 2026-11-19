package com.leagueoftext.app.ui.esports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leagueoftext.app.AppContainer
import com.leagueoftext.app.data.Feed
import com.leagueoftext.app.data.League
import com.leagueoftext.app.data.Match
import com.leagueoftext.app.ui.Formatters
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
    val selectedLeague: String? = null,
    val sections: List<DaySection> = emptyList(),
    val liveCount: Int = 0,
    val loading: Boolean = true,
    val error: Boolean = false,
    val subscriptions: Set<String> = emptySet(),
    val hideScores: Boolean = true,
    val revealed: Set<String> = emptySet(),
)

private data class Filters(
    val selectedLeague: String?,
    val subscriptions: Set<String>,
    val hideScores: Boolean,
    val revealed: Set<String>,
)

class EsportsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.matches

    /** `null` = encara no triada: s'obre "En directe" si n'hi ha cap, si no "Pròximament". */
    private val selectedTab = MutableStateFlow<EsportsTab?>(null)
    private val selectedLeague = MutableStateFlow<String?>(null)
    private val revealed = MutableStateFlow<Set<String>>(emptySet())

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
        selectedLeague,
        container.subscriptions.subscriptions,
        container.settings.hideScores,
        revealed,
    ) { league, subs, hide, shown -> Filters(league, subs, hide, shown) }

    val uiState: StateFlow<EsportsUiState> =
        combine(tabFeed, repository.live, filters) { (currentTab, feed), live, f ->
            buildState(currentTab, feed, live, f)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EsportsUiState())

    fun selectTab(tab: EsportsTab) {
        selectedTab.value = tab
    }

    fun selectLeague(key: String?) {
        selectedLeague.value = key
    }

    fun revealScore(matchId: String) {
        revealed.update { it + matchId }
    }

    fun toggleSubscription(matchId: String) {
        container.subscriptions.toggle(matchId)
    }

    private fun buildState(
        tab: EsportsTab,
        feed: Feed<List<Match>>,
        live: Feed<List<Match>>,
        f: Filters,
    ): EsportsUiState {
        val leagues = feed.data.mapNotNull { it.league }.distinctBy { it.key }
        val league = f.selectedLeague?.takeIf { key -> leagues.any { it.key == key } }
        val visible = if (league == null) feed.data else feed.data.filter { it.league?.key == league }
        val sections = visible
            .groupBy { Formatters.localDate(it.startTime) }
            .map { (date, matches) -> DaySection(date, matches) }
        return EsportsUiState(
            tab = tab,
            leagues = leagues,
            selectedLeague = league,
            sections = sections,
            liveCount = live.data.size,
            loading = feed.loading,
            error = feed.error != null,
            subscriptions = f.subscriptions,
            hideScores = f.hideScores,
            revealed = f.revealed,
        )
    }
}

package com.leagueoftext.app.ui.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leagueoftext.app.AppContainer
import com.leagueoftext.app.data.Game
import com.leagueoftext.app.data.GameEvent
import com.leagueoftext.app.data.GameState
import com.leagueoftext.app.data.Match
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

data class MatchDetailUiState(
    val loading: Boolean = true,
    val match: Match? = null,
    val games: List<Game> = emptyList(),
    val selectedGame: Game? = null,
    val events: List<GameEvent> = emptyList(),
    val subscribed: Boolean = false,
    val spoilersHidden: Boolean = true,
)

class MatchDetailViewModel(
    private val matchId: String,
    private val container: AppContainer,
) : ViewModel() {

    private val repository = container.matches

    /** Número de partida triat per l'usuari; `null` = la més recent que s'ha jugat. */
    private val selectedNumber = MutableStateFlow<Int?>(null)
    private val revealed = MutableStateFlow(false)

    private val match = repository.match(matchId).map<Match?, Loadable<Match?>> { Loadable(it) }
        .onStart { emit(Loadable(null, loading = true)) }

    // Compartit: el fan servir tant la llista de partides com la partida seleccionada (un sol listener).
    private val games = repository.games(matchId)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val selectedGame = combine(games, selectedNumber) { list, number ->
        val played = list.filter { it.state != GameState.UNSTARTED }
        (number?.let { n -> list.firstOrNull { it.number == n } }) ?: played.lastOrNull() ?: list.firstOrNull()
    }

    private val events = selectedGame
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { gameId -> if (gameId == null) flowOf(emptyList()) else repository.events(matchId, gameId) }

    private val spoilersHidden = combine(container.settings.hideScores, revealed) { hide, shown -> hide && !shown }

    val uiState: StateFlow<MatchDetailUiState> = combine(
        match,
        games,
        selectedGame,
        events,
        combine(container.subscriptions.subscriptions, spoilersHidden) { subs, hidden -> (matchId in subs) to hidden },
    ) { m, g, selected, e, (subscribed, hidden) ->
        MatchDetailUiState(
            loading = m.loading,
            match = m.value,
            games = g,
            selectedGame = selected,
            events = e,
            subscribed = subscribed,
            spoilersHidden = hidden,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchDetailUiState())

    fun selectGame(number: Int) {
        selectedNumber.value = number
    }

    fun revealSpoilers() {
        revealed.value = true
    }

    fun toggleSubscription() {
        container.subscriptions.toggle(matchId)
    }

    private data class Loadable<T>(val value: T, val loading: Boolean = false)
}

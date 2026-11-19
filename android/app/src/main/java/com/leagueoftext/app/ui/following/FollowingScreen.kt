package com.leagueoftext.app.ui.following

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.leagueoftext.app.AppContainer
import com.leagueoftext.app.R
import com.leagueoftext.app.appContainer
import com.leagueoftext.app.data.Match
import com.leagueoftext.app.data.MatchState
import com.leagueoftext.app.ui.components.EmptyState
import com.leagueoftext.app.ui.components.LoadingState
import com.leagueoftext.app.ui.components.MatchCard
import com.leagueoftext.app.ui.components.ScreenHeader
import com.leagueoftext.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class FollowingUiState(
    val loading: Boolean = true,
    val live: List<Match> = emptyList(),
    val upcoming: List<Match> = emptyList(),
    val finished: List<Match> = emptyList(),
    val subscriptions: Set<String> = emptySet(),
    val hideScores: Boolean = true,
    val revealed: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = live.isEmpty() && upcoming.isEmpty() && finished.isEmpty()
}

class FollowingViewModel(private val container: AppContainer) : ViewModel() {

    private val revealed = MutableStateFlow<Set<String>>(emptySet())
    private val matches = container.matches

    private val prefs = combine(container.subscriptions.subscriptions, container.settings.hideScores, revealed) { subs, hide, shown ->
        Triple(subs, hide, shown)
    }

    val uiState: StateFlow<FollowingUiState> =
        combine(matches.live, matches.scheduled, matches.completed, prefs) { live, scheduled, completed, (subs, hide, shown) ->
            FollowingUiState(
                loading = live.loading || scheduled.loading || completed.loading,
                live = live.data.filter { it.id in subs },
                upcoming = scheduled.data.filter { it.id in subs },
                finished = completed.data.filter { it.id in subs },
                subscriptions = subs,
                hideScores = hide,
                revealed = shown,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowingUiState())

    fun revealScore(matchId: String) = revealed.update { it + matchId }

    fun toggleSubscription(matchId: String) = container.subscriptions.toggle(matchId)
}

@Composable
fun FollowingRoute(onOpenMatch: (String) -> Unit) {
    val container = LocalContext.current.appContainer
    val viewModel: FollowingViewModel = viewModel(factory = viewModelFactory { initializer { FollowingViewModel(container) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") { ScreenHeader(stringResource(R.string.tab_following)) }
        when {
            state.isEmpty && state.loading -> item(key = "loading") { LoadingState() }
            state.isEmpty -> item(key = "empty") {
                EmptyState(
                    Icons.Outlined.NotificationsNone,
                    stringResource(R.string.empty_following_title),
                    stringResource(R.string.empty_following_message),
                )
            }
            else -> {
                section(R.string.tab_live, state.live, state, viewModel, onOpenMatch)
                section(R.string.tab_upcoming, state.upcoming, state, viewModel, onOpenMatch)
                section(R.string.tab_results, state.finished, state, viewModel, onOpenMatch)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    titleRes: Int,
    matches: List<Match>,
    state: FollowingUiState,
    viewModel: FollowingViewModel,
    onOpenMatch: (String) -> Unit,
) {
    if (matches.isEmpty()) return
    item(key = "section-$titleRes") { SectionHeader(stringResource(titleRes)) }
    items(matches, key = { "following-${it.id}" }) { match ->
        MatchCard(
            match = match,
            subscribed = match.id in state.subscriptions,
            scoreHidden = state.hideScores && match.id !in state.revealed,
            onRevealScore = { viewModel.revealScore(match.id) },
            onToggleSubscription = { viewModel.toggleSubscription(match.id) },
            onClick = if (match.state == MatchState.SCHEDULED) null else ({ onOpenMatch(match.id) }),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            showDate = true,
        )
    }
}

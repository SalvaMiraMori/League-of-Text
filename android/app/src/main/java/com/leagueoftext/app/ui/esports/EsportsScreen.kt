package com.leagueoftext.app.ui.esports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.leagueoftext.app.R
import com.leagueoftext.app.appContainer
import com.leagueoftext.app.data.MatchState
import com.leagueoftext.app.ui.Formatters
import com.leagueoftext.app.ui.LocalRequestNotificationPermission
import com.leagueoftext.app.ui.components.EmptyState
import com.leagueoftext.app.ui.components.LeagueFilterRow
import com.leagueoftext.app.ui.components.LoadingState
import com.leagueoftext.app.ui.components.MatchCard
import com.leagueoftext.app.ui.components.RiotTabs
import com.leagueoftext.app.ui.components.ScreenHeader
import com.leagueoftext.app.ui.components.SectionHeader
import com.leagueoftext.app.ui.theme.RiotColors

@Composable
fun EsportsRoute(onOpenMatch: (String) -> Unit) {
    val container = LocalContext.current.appContainer
    val viewModel: EsportsViewModel = viewModel(factory = viewModelFactory { initializer { EsportsViewModel(container) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestPermission = LocalRequestNotificationPermission.current

    EsportsScreen(
        state = state,
        onSelectTab = viewModel::selectTab,
        onSelectLeague = viewModel::selectLeague,
        onRevealScore = viewModel::revealScore,
        onToggleSubscription = { id ->
            requestPermission()
            viewModel.toggleSubscription(id)
        },
        onOpenMatch = onOpenMatch,
    )
}

@Composable
fun EsportsScreen(
    state: EsportsUiState,
    onSelectTab: (EsportsTab) -> Unit,
    onSelectLeague: (String?) -> Unit,
    onRevealScore: (String) -> Unit,
    onToggleSubscription: (String) -> Unit,
    onOpenMatch: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.tab, state.selectedLeague) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            ScreenHeader(title = stringResource(R.string.tab_esports)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(RiotColors.Chip)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.game_name_short),
                        style = MaterialTheme.typography.labelLarge,
                        color = RiotColors.Gold,
                    )
                }
            }
        }
        item(key = "leagues") {
            LeagueFilterRow(
                leagues = state.leagues,
                selectedKey = state.selectedLeague,
                onSelect = onSelectLeague,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        item(key = "tabs") {
            RiotTabs(
                tabs = listOf(
                    stringResource(R.string.tab_upcoming),
                    stringResource(R.string.tab_live),
                    stringResource(R.string.tab_results),
                ),
                selected = state.tab.ordinal,
                onSelect = { onSelectTab(EsportsTab.entries[it]) },
                dotTabs = if (state.liveCount > 0) setOf(EsportsTab.LIVE.ordinal) else emptySet(),
            )
        }

        when {
            state.loading && state.sections.isEmpty() -> item(key = "loading") { LoadingState() }
            state.sections.isEmpty() -> item(key = "empty") {
                if (state.error) {
                    EmptyState(Icons.Filled.CloudOff, stringResource(R.string.error_title), stringResource(R.string.error_message))
                } else {
                    when (state.tab) {
                        EsportsTab.UPCOMING -> EmptyState(Icons.Filled.CalendarMonth, stringResource(R.string.empty_upcoming_title), stringResource(R.string.empty_upcoming_message))
                        EsportsTab.LIVE -> EmptyState(Icons.Filled.Videocam, stringResource(R.string.empty_live_title), stringResource(R.string.empty_live_message))
                        EsportsTab.RESULTS -> EmptyState(Icons.Filled.EmojiEvents, stringResource(R.string.empty_results_title), stringResource(R.string.empty_results_message))
                    }
                }
            }
            else -> state.sections.forEach { section ->
                item(key = "day-${state.tab}-${section.date}") {
                    SectionHeader(Formatters.dayHeader(section.date))
                }
                items(section.matches, key = { "${state.tab}-${it.id}" }) { match ->
                    MatchCard(
                        match = match,
                        subscribed = match.id in state.subscriptions,
                        scoreHidden = state.hideScores && match.id !in state.revealed,
                        onRevealScore = { onRevealScore(match.id) },
                        onToggleSubscription = { onToggleSubscription(match.id) },
                        onClick = if (match.state == MatchState.SCHEDULED) null else ({ onOpenMatch(match.id) }),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
    }
}

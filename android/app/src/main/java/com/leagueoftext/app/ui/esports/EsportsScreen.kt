package com.leagueoftext.app.ui.esports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.leagueoftext.app.data.CalendarMode
import com.leagueoftext.app.data.Match
import com.leagueoftext.app.data.MatchState
import com.leagueoftext.app.ui.Formatters
import com.leagueoftext.app.ui.LocalRequestNotificationPermission
import com.leagueoftext.app.ui.components.CollapsibleSectionHeader
import com.leagueoftext.app.ui.components.EmptyState
import com.leagueoftext.app.ui.components.LeagueFilterRow
import com.leagueoftext.app.ui.components.LoadingState
import com.leagueoftext.app.ui.components.MatchCard
import com.leagueoftext.app.ui.components.RefreshableContent
import com.leagueoftext.app.ui.components.RiotTabs
import com.leagueoftext.app.ui.components.ScreenHeader
import com.leagueoftext.app.ui.theme.RiotColors
import java.time.Instant
import java.time.LocalDate

/** Desa els dies que l'usuari ha plegat o desplegat (també en girar la pantalla). */
private val ToggledDaysSaver = listSaver<MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)

@Composable
fun EsportsRoute(onOpenMatch: (String) -> Unit) {
    val container = LocalContext.current.appContainer
    val viewModel: EsportsViewModel = viewModel(factory = viewModelFactory { initializer { EsportsViewModel(container) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestPermission = LocalRequestNotificationPermission.current

    EsportsScreen(
        state = state,
        onSelectTab = viewModel::selectTab,
        onToggleLeague = viewModel::toggleLeague,
        onSelectAllLeagues = viewModel::selectAllLeagues,
        onRefresh = viewModel::refresh,
        onRevealScore = viewModel::revealScore,
        onToggleSubscription = { id ->
            requestPermission()
            viewModel.toggleSubscription(id)
        },
        onOpenMatch = onOpenMatch,
        onSelectCalendarMode = viewModel::setCalendarMode,
    )
}

@Composable
fun EsportsScreen(
    state: EsportsUiState,
    onSelectTab: (EsportsTab) -> Unit,
    onToggleLeague: (String) -> Unit,
    onSelectAllLeagues: () -> Unit,
    onRefresh: () -> Unit,
    onRevealScore: (String) -> Unit,
    onToggleSubscription: (String) -> Unit,
    onOpenMatch: (String) -> Unit,
    onSelectCalendarMode: (CalendarMode) -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.tab, state.selectedLeagues) { listState.scrollToItem(0) }

    // Per defecte només s'obre el dia d'avui (o, si avui no hi ha partits, el primer dia de la llista).
    // Aquí es desen els dies on l'usuari ha canviat aquest estat per defecte.
    var toggledDays by rememberSaveable(saver = ToggledDaysSaver) { mutableStateOf(emptySet<String>()) }
    val today = remember { Formatters.localDate(Instant.now()) }

    // Dia triat al calendari (en dies des de 1970, perquè es pugui desar). `null` = el dia per defecte.
    var pickedDay by rememberSaveable(state.tab) { mutableStateOf<Long?>(null) }

    val matchItem: @Composable LazyItemScope.(Match) -> Unit = { match ->
        MatchCard(
            match = match,
            subscribed = match.id in state.subscriptions,
            scoreHidden = state.hideScores && match.id !in state.revealed,
            onRevealScore = { onRevealScore(match.id) },
            onToggleSubscription = { onToggleSubscription(match.id) },
            onClick = if (match.state == MatchState.SCHEDULED) null else ({ onOpenMatch(match.id) }),
            modifier = Modifier
                .animateItem()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }

    RefreshableContent(refreshing = state.refreshing, onRefresh = onRefresh) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
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
                    selectedKeys = state.selectedLeagues,
                    onToggle = onToggleLeague,
                    onSelectAll = onSelectAllLeagues,
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
                else -> {
                    val openByDefault = state.sections.firstOrNull { it.date == today }?.date
                        ?: state.sections.first().date
                    // A "En directe" tots els partits són d'avui: ni calendari ni dies plegables,
                    // només les targetes dels partits.
                    if (state.tab == EsportsTab.LIVE) {
                        items(state.sections.flatMap { it.matches }, key = { "${state.tab}-${it.id}" }) { match ->
                            matchItem(match)
                        }
                    } else if (state.calendarView) calendarItems(
                        tab = state.tab,
                        sections = state.sections,
                        mode = state.calendarMode,
                        leagueOrder = state.leagues.map { it.key },
                        singleLeague = state.selectedLeagues.size == 1,
                        selected = pickedDay?.let(LocalDate::ofEpochDay) ?: openByDefault,
                        today = today,
                        onSelectDate = { pickedDay = it.toEpochDay() },
                        onSelectMode = onSelectCalendarMode,
                        matchItem = matchItem,
                    ) else state.sections.forEach { section ->
                        val dayKey = "${state.tab}-${section.date}"
                        val expanded = (section.date == openByDefault) != (dayKey in toggledDays)
                        item(key = "day-$dayKey") {
                            CollapsibleSectionHeader(
                                text = Formatters.dayHeader(section.date),
                                count = section.matches.size,
                                expanded = expanded,
                                onToggle = {
                                    toggledDays = if (dayKey in toggledDays) toggledDays - dayKey else toggledDays + dayKey
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (expanded) items(section.matches, key = { "${state.tab}-${it.id}" }) { match -> matchItem(match) }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

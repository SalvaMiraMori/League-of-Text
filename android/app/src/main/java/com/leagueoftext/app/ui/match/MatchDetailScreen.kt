package com.leagueoftext.app.ui.match

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.leagueoftext.app.R
import com.leagueoftext.app.appContainer
import com.leagueoftext.app.data.EventSide
import com.leagueoftext.app.data.EventType
import com.leagueoftext.app.data.Game
import com.leagueoftext.app.data.GameEvent
import com.leagueoftext.app.data.GameState
import com.leagueoftext.app.data.GameStats
import com.leagueoftext.app.data.Match
import com.leagueoftext.app.data.MatchState
import com.leagueoftext.app.ui.Formatters
import com.leagueoftext.app.ui.LocalRequestNotificationPermission
import com.leagueoftext.app.ui.components.EmptyState
import com.leagueoftext.app.ui.components.LiveBadge
import com.leagueoftext.app.ui.components.LoadingState
import com.leagueoftext.app.ui.components.MatchTeamsRow
import com.leagueoftext.app.ui.components.RefreshableContent
import com.leagueoftext.app.ui.components.RemoteLogo
import com.leagueoftext.app.ui.components.SectionHeader
import com.leagueoftext.app.ui.components.SubscribeButton
import com.leagueoftext.app.ui.theme.RiotColors

@Composable
fun MatchDetailRoute(matchId: String, onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    val viewModel: MatchDetailViewModel = viewModel(
        key = "match-$matchId",
        factory = viewModelFactory { initializer { MatchDetailViewModel(matchId, container) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestPermission = LocalRequestNotificationPermission.current
    MatchDetailScreen(
        state = state,
        onBack = onBack,
        onSelectGame = viewModel::selectGame,
        onReveal = viewModel::revealSpoilers,
        onRefresh = viewModel::refresh,
        onToggleSubscription = {
            requestPermission()
            viewModel.toggleSubscription()
        },
    )
}

@Composable
fun MatchDetailScreen(
    state: MatchDetailUiState,
    onBack: () -> Unit,
    onSelectGame: (Int) -> Unit,
    onReveal: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSubscription: () -> Unit,
) {
    val match = state.match
    Column(Modifier.fillMaxWidth()) {
        TopBar(match, state.subscribed, onBack, onToggleSubscription)
        RefreshableContent(refreshing = state.refreshing, onRefresh = onRefresh) {
            when {
                match == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (state.loading) {
                        LoadingState()
                    } else {
                        EmptyState(Icons.Filled.Info, stringResource(R.string.match_not_found_title), stringResource(R.string.match_not_found_message))
                    }
                }
                else -> MatchContent(match, state, onSelectGame, onReveal)
            }
        }
    }
}

@Composable
private fun TopBar(match: Match?, subscribed: Boolean, onBack: () -> Unit, onToggleSubscription: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = RiotColors.TextPrimary)
        }
        Spacer(Modifier.weight(1f))
        if (match != null) {
            if (match.state != MatchState.COMPLETED) {
                ChipButton { SubscribeButton(subscribed, onToggleSubscription) }
                Spacer(Modifier.width(10.dp))
            }
            val shareText = stringResource(
                R.string.share_text,
                match.title,
                match.league?.name.orEmpty(),
                Formatters.shortDate(match.startTime),
            )
            ChipButton {
                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    context.startActivity(Intent.createChooser(intent, null))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share), tint = RiotColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ChipButton(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(RiotColors.Chip),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MatchContent(
    match: Match,
    state: MatchDetailUiState,
    onSelectGame: (Int) -> Unit,
    onReveal: () -> Unit,
) {
    val hidden = state.spoilersHidden
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "teams") {
            Box(Modifier.background(RiotColors.SurfaceHigh)) {
                MatchTeamsRow(match, scoreHidden = hidden, onRevealScore = onReveal)
            }
        }
        item(key = "league") { LeagueRow(match) }
        item(key = "selector") {
            Column {
                Spacer(Modifier.height(8.dp))
                GameSelector(match.bestOf, state.games, state.selectedGame, hidden, onSelectGame)
            }
        }

        val game = state.selectedGame
        when {
            game == null || (game.state == GameState.UNSTARTED && state.events.isEmpty()) -> item(key = "not-started") {
                EmptyState(Icons.Filled.HourglassEmpty, stringResource(R.string.game_not_started_title), stringResource(R.string.game_not_started_message))
            }
            hidden -> item(key = "hidden") { SpoilerCard(onReveal) }
            else -> {
                item(key = "scoreboard-${game.id}") { Scoreboard(game, finalStats(state.events)) }
                item(key = "timeline-header") { SectionHeader(stringResource(R.string.timeline)) }
                if (state.events.isEmpty()) {
                    item(key = "no-events") {
                        Text(
                            stringResource(R.string.no_events),
                            style = MaterialTheme.typography.bodyMedium,
                            color = RiotColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                items(state.events, key = { "event-${game.id}-${it.id}" }) { event -> EventRow(event) }
            }
        }
    }
}

@Composable
private fun LeagueRow(match: Match) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RiotColors.Surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(RiotColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            RemoteLogo(url = match.league?.image.orEmpty(), fallbackText = match.league?.name ?: "LoL", size = 26.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val format = pluralStringResource(R.plurals.best_of, match.bestOf, match.bestOf)
            Text(
                text = listOf(match.blockName, format).filter { it.isNotBlank() }.joinToString(" - "),
                style = MaterialTheme.typography.titleSmall,
                color = RiotColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(match.league?.name, "${Formatters.shortDate(match.startTime)} ${Formatters.time(match.startTime)}")
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = RiotColors.TextTertiary,
            )
        }
        if (match.state == MatchState.LIVE) LiveBadge()
    }
}

@Composable
private fun GameSelector(
    bestOf: Int,
    games: List<Game>,
    selected: Game?,
    spoilersHidden: Boolean,
    onSelect: (Int) -> Unit,
) {
    val count = maxOf(bestOf, games.maxOfOrNull { it.number } ?: 0, 1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RiotColors.Surface)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.game_label), style = MaterialTheme.typography.titleSmall, color = RiotColors.TextTertiary)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (number in 1..count) {
                val game = games.firstOrNull { it.number == number }
                // Amb els resultats amagats no es revela quantes partides s'han jugat.
                val enabled = game != null && (spoilersHidden || game.state != GameState.UNSTARTED)
                val isSelected = selected?.number == number
                val shape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(shape)
                        .then(if (isSelected) Modifier.border(BorderStroke(2.dp, RiotColors.TextPrimary), shape) else Modifier)
                        .clickable(enabled = enabled) { onSelect(number) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) RiotColors.TextPrimary else RiotColors.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpoilerCard(onReveal: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RiotColors.Surface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.spoilers_hidden_title), style = MaterialTheme.typography.titleMedium, color = RiotColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.spoilers_hidden_message),
            style = MaterialTheme.typography.bodyMedium,
            color = RiotColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(RiotColors.Chip)
                .clickable(onClick = onReveal)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null, tint = RiotColors.Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_show_results), style = MaterialTheme.typography.labelLarge, color = RiotColors.TextPrimary)
        }
    }
}

@Composable
private fun Scoreboard(game: Game, fallbackStats: GameStats?) {
    val stats = game.stats ?: fallbackStats ?: return
    val blueName = game.blueTeam.ifBlank { "BLUE" }
    val redName = game.redTeam.ifBlank { "RED" }
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RiotColors.Surface)
            .padding(vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(blueName, color = RiotColors.BlueSide, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            val status = when {
                game.state == GameState.COMPLETED -> stringResource(R.string.game_finished)
                game.paused -> stringResource(R.string.game_paused)
                game.playingSeconds > 0 -> Formatters.clock(game.playingSeconds)
                else -> ""
            }
            Text(status, style = MaterialTheme.typography.labelLarge, color = RiotColors.TextSecondary)
            Text(
                redName,
                color = RiotColors.RedSide,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        game.winnerId?.let { winnerId ->
            val winnerName = when (winnerId) {
                game.blueTeamId -> blueName
                game.redTeamId -> redName
                else -> null
            }
            if (winnerName != null) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(RiotColors.Win.copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = RiotColors.Win, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.game_winner, winnerName), color = RiotColors.Win, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        StatsBlock(stats, horizontalPadding = 20.dp)
    }
}

/**
 * Bloc d'estadístiques blau contra vermell, compartit pel resum final i per les notificacions de la cronologia.
 * L'or va primer, amb la barra just a sota: la part blava/vermella és la proporció d'or de cada equip.
 */
@Composable
private fun StatsBlock(stats: GameStats, horizontalPadding: Dp, compact: Boolean = false) {
    val rowPadding = if (compact) 4.dp else 7.dp
    StatRow(Icons.Filled.Stars, stringResource(R.string.stat_gold), stats.blue.goldLabel, stats.red.goldLabel, horizontalPadding, rowPadding, compact)
    GoldBar(stats.blue.gold, stats.red.gold, horizontalPadding)
    Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
    StatRow(Icons.Filled.GpsFixed, stringResource(R.string.stat_kills), stats.blue.kills.toString(), stats.red.kills.toString(), horizontalPadding, rowPadding, compact)
    StatRow(Icons.Filled.Castle, stringResource(R.string.stat_towers), stats.blue.towers.toString(), stats.red.towers.toString(), horizontalPadding, rowPadding, compact)
    StatRow(Icons.Filled.Whatshot, stringResource(R.string.stat_dragons), stats.blue.dragons.toString(), stats.red.dragons.toString(), horizontalPadding, rowPadding, compact)
    StatRow(Icons.Filled.AutoAwesome, stringResource(R.string.stat_barons), stats.blue.barons.toString(), stats.red.barons.toString(), horizontalPadding, rowPadding, compact)
    StatRow(Icons.Filled.Shield, stringResource(R.string.stat_inhibitors), stats.blue.inhibitors.toString(), stats.red.inhibitors.toString(), horizontalPadding, rowPadding, compact)
}

/**
 * Estadístiques finals del joc segons els esdeveniments: les de la notificació "X wins the game"
 * i, si no n'hi ha, les de l'esdeveniment més recent que en tingui (la llista ve del més nou al més antic).
 */
internal fun finalStats(events: List<GameEvent>): GameStats? =
    events.firstOrNull { it.type == EventType.GAME_END && it.stats != null }?.stats
        ?: events.firstOrNull { it.stats != null }?.stats

/** Fracció de la barra que correspon a l'equip blau segons l'or (meitat i meitat si no hi ha dades). */
internal fun blueGoldFraction(blue: Int, red: Int): Float {
    val total = blue + red
    if (total <= 0) return 0.5f
    return (blue.toFloat() / total).coerceIn(0.02f, 0.98f)
}

@Composable
private fun GoldBar(blue: Int, red: Int, horizontalPadding: Dp) {
    val blueFraction = blueGoldFraction(blue, red)
    Row(
        Modifier
            .padding(horizontal = horizontalPadding)
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        Box(Modifier.weight(blueFraction).fillMaxHeight().background(RiotColors.BlueSide))
        Spacer(Modifier.width(2.dp))
        Box(Modifier.weight(1f - blueFraction).fillMaxHeight().background(RiotColors.RedSide))
    }
}

@Composable
private fun StatRow(
    icon: ImageVector,
    label: String,
    blue: String,
    red: String,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    compact: Boolean,
) {
    val valueStyle = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
    val labelStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(blue, style = valueStyle, color = RiotColors.TextPrimary, modifier = Modifier.weight(1f))
        Icon(icon, contentDescription = null, tint = RiotColors.TextTertiary, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = labelStyle, color = RiotColors.TextSecondary)
        Text(red, style = valueStyle, color = RiotColors.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EventRow(event: GameEvent) {
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    val sideColor = when (event.side) {
        EventSide.BLUE -> RiotColors.BlueSide
        EventSide.RED -> RiotColors.RedSide
        null -> RiotColors.Gold
    }
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = event.stats != null) { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = event.playingTime,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = RiotColors.TextSecondary,
                    modifier = Modifier.width(52.dp).padding(top = 6.dp),
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(sideColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(event.type.icon(), contentDescription = null, tint = sideColor, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = event.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (event.type.isHighlight()) RiotColors.TextPrimary else RiotColors.TextPrimary.copy(alpha = 0.85f),
                    fontWeight = if (event.type.isHighlight()) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).padding(top = 5.dp),
                )
            }
            AnimatedVisibility(visible = expanded && event.stats != null) {
                event.stats?.let { stats -> EventStatsCard(event, stats) }
            }
        }
        HorizontalDivider(color = RiotColors.Divider.copy(alpha = 0.6f), modifier = Modifier.padding(start = 94.dp))
    }
}

/** Estadístiques del moment de la notificació, amb el mateix format que el resum final però més compacte. */
@Composable
private fun EventStatsCard(event: GameEvent, stats: GameStats) {
    Column(
        modifier = Modifier
            .padding(start = 74.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RiotColors.Surface)
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                event.blueTeam.ifBlank { "BLUE" },
                color = RiotColors.BlueSide,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                event.playingTime,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = RiotColors.TextSecondary,
            )
            Text(
                event.redTeam.ifBlank { "RED" },
                color = RiotColors.RedSide,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        StatsBlock(stats, horizontalPadding = 14.dp, compact = true)
    }
}

private fun EventType.icon(): ImageVector = when (this) {
    EventType.GAME_START -> Icons.Filled.Flag
    EventType.GAME_END -> Icons.Filled.EmojiEvents
    EventType.KILL -> Icons.Filled.GpsFixed
    EventType.FIRST_BLOOD -> Icons.Filled.WaterDrop
    EventType.MULTIKILL -> Icons.Filled.LocalFireDepartment
    EventType.TOWER, EventType.FIRST_TOWER -> Icons.Filled.Castle
    EventType.INHIBITOR -> Icons.Filled.Shield
    EventType.BARON -> Icons.Filled.Stars
    EventType.DRAGON -> Icons.Filled.Whatshot
    EventType.ELDER, EventType.SOUL -> Icons.Filled.AutoAwesome
    EventType.PAUSE -> Icons.Filled.Pause
    EventType.UNPAUSE -> Icons.Filled.PlayArrow
    EventType.OTHER -> Icons.Filled.Info
}

private fun EventType.isHighlight(): Boolean = this in setOf(
    EventType.GAME_END, EventType.FIRST_BLOOD, EventType.MULTIKILL, EventType.BARON,
    EventType.SOUL, EventType.ELDER, EventType.FIRST_TOWER,
)

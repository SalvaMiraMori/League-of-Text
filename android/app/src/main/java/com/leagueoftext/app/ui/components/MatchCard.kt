package com.leagueoftext.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leagueoftext.app.R
import com.leagueoftext.app.data.Match
import com.leagueoftext.app.data.MatchState
import com.leagueoftext.app.data.Team
import com.leagueoftext.app.ui.Formatters
import com.leagueoftext.app.ui.theme.RiotColors

/**
 * Targeta d'un partit a l'estil de Riot Mobile:
 * capçalera amb la lliga i l'estat, fila d'equips amb el marcador al mig
 * (amagat amb un ull fins que l'usuari el vol veure) i peu amb la fase i el format.
 */
@Composable
fun MatchCard(
    match: Match,
    subscribed: Boolean,
    scoreHidden: Boolean,
    onRevealScore: () -> Unit,
    onToggleSubscription: () -> Unit,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RiotColors.Surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        MatchBanner(match, showDate)
        MatchTeamsRow(match, scoreHidden, onRevealScore)
        HorizontalDivider(color = RiotColors.Divider, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = match.blockName.ifBlank { match.league?.name.orEmpty() },
                    style = MaterialTheme.typography.titleSmall,
                    color = RiotColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.best_of, match.bestOf, match.bestOf),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RiotColors.TextTertiary,
                )
            }
            if (match.state != MatchState.COMPLETED) {
                SubscribeButton(subscribed, onToggleSubscription)
            }
        }
    }
}

@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (subscribed) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
            contentDescription = stringResource(if (subscribed) R.string.action_unfollow else R.string.action_follow),
            tint = if (subscribed) RiotColors.Gold else RiotColors.TextSecondary,
        )
    }
}

@Composable
private fun MatchBanner(match: Match, showDate: Boolean) {
    val gradient = Brush.horizontalGradient(
        listOf(RiotColors.BlueSide.copy(alpha = 0.16f), RiotColors.SurfaceHigh, RiotColors.RedSide.copy(alpha = 0.16f)),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(gradient)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(RiotColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            val league = match.league
            RemoteLogo(url = league?.image.orEmpty(), fallbackText = league?.name ?: "LoL", size = 24.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = match.league?.name ?: stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = RiotColors.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (match.state) {
            MatchState.LIVE -> LiveBadge()
            MatchState.COMPLETED -> Text(
                text = if (showDate) Formatters.shortDate(match.startTime) else stringResource(R.string.match_finished),
                style = MaterialTheme.typography.labelMedium,
                color = RiotColors.TextSecondary,
            )
            MatchState.SCHEDULED -> Text(
                text = if (showDate) "${Formatters.shortDate(match.startTime)} · ${Formatters.time(match.startTime)}"
                else Formatters.time(match.startTime),
                style = MaterialTheme.typography.labelMedium,
                color = RiotColors.GoldLight,
            )
        }
    }
}

@Composable
fun MatchTeamsRow(match: Match, scoreHidden: Boolean, onRevealScore: () -> Unit) {
    val winner = match.winnerId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamSide(match.team1, dimmed = !scoreHidden && winner != null && winner != match.team1.id, alignEnd = false, modifier = Modifier.weight(1f))
        ScorePill(match, scoreHidden, onRevealScore)
        TeamSide(match.team2, dimmed = !scoreHidden && winner != null && winner != match.team2.id, alignEnd = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TeamSide(team: Team, dimmed: Boolean, alignEnd: Boolean, modifier: Modifier = Modifier) {
    val color = if (dimmed) RiotColors.TextTertiary else RiotColors.TextPrimary
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        val code = @Composable {
            Text(
                text = team.code,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = color,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 72.dp),
                overflow = TextOverflow.Clip,
            )
        }
        val logo = @Composable {
            RemoteLogo(url = team.image, fallbackText = team.code, size = 44.dp, contentDescription = team.name)
        }
        if (alignEnd) {
            logo(); Spacer(Modifier.weight(1f)); code()
        } else {
            code(); Spacer(Modifier.weight(1f)); logo()
        }
    }
}

@Composable
private fun ScorePill(match: Match, scoreHidden: Boolean, onRevealScore: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .widthIn(min = 64.dp)
            .height(40.dp)
            .clip(shape)
            .background(RiotColors.Chip)
            .then(if (scoreHidden && match.hasScore) Modifier.clickable(onClick = onRevealScore) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            match.state == MatchState.SCHEDULED && !match.hasScore -> Text(
                text = stringResource(R.string.versus),
                style = MaterialTheme.typography.labelLarge,
                color = RiotColors.TextSecondary,
            )
            scoreHidden -> Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = stringResource(R.string.action_show_score),
                tint = RiotColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
            else -> Text(
                text = "${match.wins(match.team1)} - ${match.wins(match.team2)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (match.state == MatchState.LIVE) RiotColors.Live else RiotColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

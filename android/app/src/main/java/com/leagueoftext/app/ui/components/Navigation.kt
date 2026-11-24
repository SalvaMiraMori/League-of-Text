package com.leagueoftext.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leagueoftext.app.R
import com.leagueoftext.app.data.League
import com.leagueoftext.app.ui.theme.RiotColors

/**
 * Pestanyes amb subratllat daurat ("Pròximament", "En directe", "Resultats").
 * [dotTabs]: pestanyes que mostren un punt vermell (p. ex. si hi ha partits en directe).
 */
@Composable
fun RiotTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    dotTabs: Set<Int> = emptySet(),
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(index) }
                        .padding(horizontal = 12.dp)
                        .padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) RiotColors.TextPrimary else RiotColors.TextSecondary,
                            maxLines = 1,
                        )
                        if (index in dotTabs) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(7.dp).clip(CircleShape).background(RiotColors.Live))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (isSelected) RiotColors.Gold else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = RiotColors.Divider, thickness = 1.dp)
    }
}

/**
 * Fila de lligues (com la de Riot Mobile): "Totes" + una casella per lliga.
 * Se'n poden marcar diverses alhora; "Totes" treu el filtre.
 */
@Composable
fun LeagueFilterRow(
    leagues: List<League>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "all") {
            LeagueTile(
                label = stringResource(R.string.league_all),
                selected = selectedKeys.isEmpty(),
                onClick = onSelectAll,
            ) {
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = RiotColors.Gold,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        items(leagues, key = { it.key }) { league ->
            LeagueTile(
                label = league.name,
                selected = league.key in selectedKeys,
                onClick = { onToggle(league.key) },
            ) {
                RemoteLogo(url = league.image, fallbackText = league.name, size = 34.dp, contentDescription = league.name)
            }
        }
    }
}

@Composable
private fun LeagueTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val shape = RoundedCornerShape(18.dp)
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(shape)
                .background(RiotColors.Chip)
                .then(
                    if (selected) Modifier.border(BorderStroke(2.dp, RiotColors.TextPrimary), shape) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) RiotColors.TextPrimary else RiotColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

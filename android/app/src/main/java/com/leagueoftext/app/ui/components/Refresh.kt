package com.leagueoftext.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leagueoftext.app.ui.theme.RiotColors
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Contenidor "estira per refrescar": en arribar a dalt de la llista i continuar estirant cap avall,
 * crida [onRefresh]. El contingut ha de ser desplaçable (LazyColumn, verticalScroll...).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableContent(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = RiotColors.Surface,
                color = RiotColors.Gold,
            )
        },
        content = { Box(Modifier.fillMaxSize()) { content() } },
    )
}

/**
 * Estat d'un refresc manual dins d'un ViewModel. Ignora peticions mentre n'hi ha una en curs i
 * mostra l'indicador un mínim de temps perquè no parpellegi quan la resposta és immediata.
 */
class ManualRefresh(private val viewModel: ViewModel) {
    val refreshing: StateFlow<Boolean>
        field = MutableStateFlow(false)

    fun launch(block: suspend () -> Unit) {
        if (refreshing.value) return
        refreshing.value = true
        viewModel.viewModelScope.launch {
            try {
                val minimum = async { delay(MIN_VISIBLE_MS) }
                block()
                minimum.await()
            } finally {
                refreshing.value = false
            }
        }
    }

    private companion object {
        const val MIN_VISIBLE_MS = 600L
    }
}

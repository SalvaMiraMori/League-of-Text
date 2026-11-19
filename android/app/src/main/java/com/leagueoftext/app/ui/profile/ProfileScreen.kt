package com.leagueoftext.app.ui.profile

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.leagueoftext.app.AppContainer
import com.leagueoftext.app.BuildConfig
import com.leagueoftext.app.R
import com.leagueoftext.app.appContainer
import com.leagueoftext.app.data.SignInResult
import com.leagueoftext.app.ui.components.ScreenHeader
import com.leagueoftext.app.ui.theme.RiotColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val signedIn: Boolean = false,
    val name: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val followingCount: Int = 0,
    val hideScores: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val busy: Boolean = false,
    val messageRes: Int? = null,
)

class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<ProfileUiState> = combine(
        container.auth.user,
        container.subscriptions.subscriptions,
        combine(container.settings.hideScores, container.settings.notificationsEnabled) { a, b -> a to b },
        busy,
        message,
    ) { user, subs, (hide, notify), isBusy, msg ->
        ProfileUiState(
            signedIn = user != null,
            name = user?.displayName,
            email = user?.email,
            photoUrl = user?.photoUrl?.toString(),
            followingCount = subs.size,
            hideScores = hide,
            notificationsEnabled = notify,
            busy = isBusy,
            messageRes = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun signIn(activityContext: Context) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = when (container.auth.signInWithGoogle(activityContext)) {
                is SignInResult.Success -> {
                    container.subscriptions.onSignedIn()
                    null
                }
                SignInResult.Cancelled -> null
                SignInResult.MissingConfiguration -> R.string.sign_in_missing_config
                is SignInResult.Failure -> R.string.sign_in_error
            }
            busy.value = false
        }
    }

    fun signOut(context: Context) {
        viewModelScope.launch { container.auth.signOut(context) }
    }

    fun setHideScores(value: Boolean) = container.settings.setHideScores(value)

    fun setNotificationsEnabled(value: Boolean) = container.settings.setNotificationsEnabled(value)
}

@Composable
fun ProfileRoute() {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: ProfileViewModel = viewModel(factory = viewModelFactory { initializer { ProfileViewModel(container) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.tab_profile))
        ProfileBanner(state)

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.name ?: stringResource(R.string.profile_guest),
                style = MaterialTheme.typography.titleLarge,
                color = RiotColors.TextPrimary,
            )
            Text(
                text = state.email ?: stringResource(R.string.profile_guest_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = RiotColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))

            if (state.signedIn) {
                ActionButton(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.action_sign_out)) {
                    viewModel.signOut(context)
                }
            } else {
                ActionButton(Icons.Filled.Person, stringResource(R.string.action_sign_in_google), busy = state.busy) {
                    viewModel.signIn(context)
                }
            }
            state.messageRes?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = RiotColors.Live)
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = RiotColors.Divider, thickness = 6.dp)

        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text(stringResource(R.string.profile_preferences), style = MaterialTheme.typography.titleLarge, color = RiotColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            SettingRow(
                icon = Icons.Filled.VisibilityOff,
                title = stringResource(R.string.setting_hide_scores),
                subtitle = stringResource(R.string.setting_hide_scores_desc),
                checked = state.hideScores,
                onChange = viewModel::setHideScores,
            )
            SettingRow(
                icon = Icons.Filled.NotificationsActive,
                title = stringResource(R.string.setting_notifications),
                subtitle = pluralStringResource(R.plurals.following_count, state.followingCount, state.followingCount),
                checked = state.notificationsEnabled,
                onChange = viewModel::setNotificationsEnabled,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.profile_about, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = RiotColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun ProfileBanner(state: ProfileUiState) {
    Box(Modifier.fillMaxWidth().height(170.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2B1F4A), Color(0xFF5B2C6F), RiotColors.Gold.copy(alpha = 0.55f)),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .padding(start = 20.dp)
                .offset(y = 62.dp)
                .size(104.dp)
                .clip(CircleShape)
                .background(RiotColors.Background)
                .border(3.dp, RiotColors.Gold, CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(RiotColors.Chip),
            contentAlignment = Alignment.Center,
        ) {
            if (state.photoUrl != null) {
                AsyncImage(
                    model = state.photoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(92.dp).clip(CircleShape),
                )
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = RiotColors.TextSecondary, modifier = Modifier.size(52.dp))
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, text: String, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RiotColors.Surface)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = RiotColors.Gold, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(icon, contentDescription = null, tint = RiotColors.TextPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = RiotColors.TextPrimary)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = RiotColors.TextSecondary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RiotColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = RiotColors.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = RiotColors.Gold,
                uncheckedThumbColor = RiotColors.TextSecondary,
                uncheckedTrackColor = RiotColors.Chip,
                uncheckedBorderColor = RiotColors.Divider,
            ),
        )
    }
}

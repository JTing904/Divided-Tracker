package com.dividendstream.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.ThemePreference
import com.dividendstream.app.core.currencySymbol
import com.dividendstream.app.core.formatFull
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.DsTextField
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.PrimaryButton
import com.dividendstream.app.ui.components.SecondaryButton
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.MonoFigure
import java.time.ZoneId

/**
 * The account, and the handful of settings that belong to this device.
 *
 * Sign out lives here rather than in the top bar. It was one tap from every screen, next to
 * nothing else, which is a lot of exposure for the one action that throws away what is on
 * screen -- and it left the app with no home for anything else about the person.
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val profile = state.profile

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "You",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        when {
            state.isLoading && profile == null -> LoadingBox(Modifier.fillMaxSize())

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.error?.let { error ->
                    item { ErrorBanner(error = error, onRetry = viewModel::load) }
                }

                if (profile != null) {
                    item { IdentityCard(profile.name, profile.email, profile.createdAt) }

                    item { SectionHeader("Account") }

                    item {
                        DsCard(modifier = Modifier.fillMaxWidth()) {
                            DsTextField(
                                label = "Name",
                                value = state.name,
                                onValueChange = viewModel::onNameChange,
                                placeholder = "Your name",
                            )
                            Spacer(Modifier.height(14.dp))

                            OverlineText("Currency")
                            Spacer(Modifier.height(8.dp))
                            CurrencyRow(
                                selected = state.baseCurrency,
                                onSelect = viewModel::onCurrencyChange,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Changes how amounts are labelled. It does not convert anything " +
                                    "already recorded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(16.dp))
                            PrimaryButton(
                                text = if (state.savedMessage != null && !state.hasChanges) "Saved" else "Save changes",
                                onClick = viewModel::save,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.canSave,
                                loading = state.isSaving,
                            )
                        }
                    }
                }

                item { SectionHeader("Appearance") }

                item {
                    DsCard(modifier = Modifier.fillMaxWidth()) {
                        ThemePreference.entries.forEach { option ->
                            ThemeRow(
                                option = option,
                                selected = option == theme,
                                onSelect = { viewModel.setTheme(option) },
                            )
                        }
                    }
                }

                item { SectionHeader("About") }

                item {
                    DsCard(modifier = Modifier.fillMaxWidth()) {
                        AboutRow("App", viewModel.appVersion)
                        AboutRow("Service", state.backend?.service?.takeIf { it.isNotBlank() } ?: "unreachable")
                        state.backend?.commit?.take(7)?.let { AboutRow("Build", it) }
                        state.backend?.latestClient?.let { AboutRow("Latest release", it) }
                    }
                }

                item {
                    SecondaryButton(
                        text = "Sign out",
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun IdentityCard(name: String, email: String, createdAt: java.time.Instant) {
    DsCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.initials(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Here since ${createdAt.atZone(ZoneId.systemDefault()).toLocalDate().formatFull()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(option: ThemePreference, selected: Boolean, onSelect: () -> Unit) {
    val icon: ImageVector = when (option) {
        ThemePreference.System -> Icons.Default.BrightnessAuto
        ThemePreference.Light -> Icons.Default.Brightness7
        ThemePreference.Dark -> Icons.Default.Brightness4
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            option.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CurrencyRow(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CURRENCIES.forEach { code ->
            val isSelected = code == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) DividendColors.GrowthGlow
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(code) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "${currencySymbol(code)} $code",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlineText(label)
        Text(value, style = MonoFigure, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The currencies the app knows a symbol for; anything else would render as its own code. */
private val CURRENCIES = listOf("MYR", "SGD", "USD")

private fun String.initials(): String =
    trim().split(" ").filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

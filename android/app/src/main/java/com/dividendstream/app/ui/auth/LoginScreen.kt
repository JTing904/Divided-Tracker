package com.dividendstream.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.DsTextField
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LabelledDivider
import com.dividendstream.app.ui.components.PrimaryButton
import com.dividendstream.app.ui.components.SecondaryButton

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onNavigateToRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val googleLauncher = rememberGoogleSignInLauncher()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(72.dp))

        Text(
            text = "Dividend Stream",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Welcome back",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        // Google first, because it is the shorter path for anyone it applies to, and the form
        // below stays fully usable for everyone else. Hidden entirely unless this server has
        // it configured -- an button that cannot work is worse than no button.
        if (state.showGoogle && googleLauncher.isSupported) {
            DsCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            ) {
                SecondaryButton(
                    text = "Continue with Google",
                    onClick = { viewModel.signInWithGoogle(googleLauncher) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSubmitting,
                    loading = state.isGoogleSubmitting,
                )

                // Revealed only once the server has refused for want of one. Until then nobody
                // knows whether this Google account is new here, and asking everybody for a
                // code they mostly do not need is the wrong default.
                if (state.needsInviteCode) {
                    Spacer(Modifier.height(16.dp))
                    DsTextField(
                        label = "Invite code",
                        value = state.inviteCode,
                        onValueChange = viewModel::onInviteCodeChange,
                        placeholder = "Ask whoever shared this app",
                        leadingIcon = Icons.Default.Key,
                        imeAction = ImeAction.Done,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            LabelledDivider("or")
            Spacer(Modifier.height(20.dp))
        }

        DsCard(modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            DsTextField(
                label = "Email address",
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                placeholder = "name@example.com",
                leadingIcon = Icons.Default.MailOutline,
                keyboardType = KeyboardType.Email,
                isError = state.error?.fieldErrors?.containsKey("email") == true,
                supportingText = state.error?.fieldErrors?.get("email"),
            )

            Spacer(Modifier.height(16.dp))

            DsTextField(
                label = "Password",
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                placeholder = "Your password",
                leadingIcon = Icons.Default.Lock,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                isPassword = true,
                isError = state.error?.fieldErrors?.containsKey("password") == true,
                supportingText = state.error?.fieldErrors?.get("password"),
                trailingLabel = {
                    Text(
                        "Forgot password?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )

            state.error?.takeIf { it.fieldErrors.isEmpty() }?.let { error ->
                Spacer(Modifier.height(16.dp))
                ErrorBanner(error = error)
            }

            Spacer(Modifier.height(24.dp))

            PrimaryButton(
                text = "Log in",
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSubmit,
                loading = state.isSubmitting,
            )
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onNavigateToRegister) {
            Text(
                "New here? Create an account",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

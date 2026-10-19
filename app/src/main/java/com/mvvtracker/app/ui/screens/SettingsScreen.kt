package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mvvtracker.app.BuildConfig
import androidx.hilt.navigation.compose.hiltViewModel
import com.mvvtracker.app.R
import com.mvvtracker.app.util.AppInfo
import com.mvvtracker.app.util.LocaleHelper
import com.mvvtracker.app.util.ThemeHelper
import com.mvvtracker.app.util.ThemeMode
import com.mvvtracker.app.util.findActivity
import com.mvvtracker.app.viewmodel.ConsentViewModel
import com.mvvtracker.app.viewmodel.SettingsViewModel

/**
 * Exposes the GDPR rights a user can exercise directly in-app:
 *  - Withdraw consent at any time, per-purpose (Art. 7(3)); the advertising
 *    choice is changed in Google's consent dialog, reopened from here
 *  - Delete my data / erase (Art. 17) - rotates the pseudonymous ID and
 *    asks the backend to delete anything tied to it
 *  - View the privacy policy (Art. 13/14 transparency obligations)
 *  - See the random identifier, to name it in a request by mail (Art. 15 to 17)
 * It also leads to the data sources and the source code, and to the page for
 * reporting unlawful content (Art. 16 Digital Services Act).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    consentViewModel: ConsentViewModel = hiltViewModel(),
    onOpenPrivacyPolicy: () -> Unit,
    onOpenLegalNotice: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    val analyticsConsent by settingsViewModel.analyticsConsent.collectAsState(initial = false)
    val preciseLocationConsent by settingsViewModel.preciseLocationConsent.collectAsState(initial = false)
    val hasAdPrivacyOptions by settingsViewModel.hasAdPrivacyOptions.collectAsState()
    val randomId by settingsViewModel.randomId.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        LanguagePicker()

        ThemePicker()

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        SettingRow(stringResource(R.string.settings_analytics), analyticsConsent) { settingsViewModel.setAnalyticsConsent(it) }
        SettingRow(stringResource(R.string.settings_location), preciseLocationConsent) { settingsViewModel.setPreciseLocationConsent(it) }

        // The advertising choice is made in Google's consent dialog; this reopens it
        // (only where that dialog applies).
        if (hasAdPrivacyOptions) {
            OutlinedButton(
                onClick = { context.findActivity()?.let(settingsViewModel::showAdPrivacyOptions) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.settings_ad_privacy))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        OutlinedButton(onClick = onOpenPrivacyPolicy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_view_policy))
        }

        OutlinedButton(
            onClick = onOpenLegalNotice,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.settings_view_legal))
        }

        // Where the data comes from, and the app's own source code.
        OutlinedButton(
            onClick = onOpenAbout,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_about))
        }

        // Reporting unlawful content: the page explains it and offers a prepared mail.
        OutlinedButton(
            onClick = { uriHandler.openUri(AppInfo.REPORT_CONTENT_URL) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.settings_report_content))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        randomId?.let { RandomIdRow(it) }

        val deleteState by consentViewModel.deleteState.collectAsState()
        Button(
            onClick = { consentViewModel.deleteAllMyData() },
            enabled = deleteState != ConsentViewModel.DeleteState.Running,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.settings_delete))
        }
        when (deleteState) {
            ConsentViewModel.DeleteState.Done -> Text(
                stringResource(R.string.settings_delete_done),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
            ConsentViewModel.DeleteState.Failed -> Text(
                stringResource(R.string.settings_delete_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
            else -> Unit
        }
        Text(
            stringResource(R.string.settings_delete_desc),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp)
        )

        // Which version is installed: helps when someone reports a problem.
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}

/**
 * The random identifier of this installation with a copy button. The operator
 * knows nothing else about a user, so a request by mail has to name it.
 */
@Composable
private fun RandomIdRow(id: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(id) { mutableStateOf(false) }
    Text(stringResource(R.string.settings_random_id), style = MaterialTheme.typography.bodyLarge)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(id))
            copied = true
        }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.settings_random_id_copy))
        }
    }
    Text(
        stringResource(if (copied) R.string.settings_random_id_copied else R.string.settings_random_id_desc),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/** Light/Dark theme buttons, sitting next to the language picker. Tapping a
 *  button forces that appearance immediately (the root re-themes live). */
@Composable
private fun ThemePicker() {
    val context = LocalContext.current
    val mode by ThemeHelper.mode.collectAsState()
    val effectiveDark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeButton(stringResource(R.string.theme_light), selected = !effectiveDark) {
                ThemeHelper.setMode(context, ThemeMode.LIGHT)
            }
            ThemeButton(stringResource(R.string.theme_dark), selected = effectiveDark) {
                ThemeHelper.setMode(context, ThemeMode.DARK)
            }
        }
    }
}

@Composable
private fun ThemeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun SettingRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val current = LocaleHelper.language(context)
    val languages = listOf(
        "" to stringResource(R.string.lang_system),
        "en" to "English",
        "de" to "Deutsch"
    )
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = languages.firstOrNull { it.first == current }?.second
        ?: stringResource(R.string.lang_system)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        if (tag != LocaleHelper.language(context)) {
                            LocaleHelper.setLanguage(context, tag)
                            // Recreate so the whole UI re-renders in the new language.
                            context.findActivity()?.recreate()
                        }
                    }
                )
            }
        }
    }
}

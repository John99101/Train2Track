package com.mvvtracker.app.ui.screens

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mvvtracker.app.R

/**
 * In-app privacy policy. The full text lives in localized plain-text resources
 * (res/raw = English, res/raw-de = German), auto-selected by the app language.
 * The same text is hosted publicly at privacy-policy.html for the store listings.
 */
@Composable
fun PrivacyPolicyScreen() = RawTextScreen(R.raw.privacy_policy)

/**
 * In-app legal notice (Impressum). Kept as a separate document from the privacy
 * policy, as German law treats the two as distinct. Localized the same way; the
 * public copy is hosted at legal-notice.html.
 */
@Composable
fun LegalNoticeScreen() = RawTextScreen(R.raw.legal_notice)

/**
 * Renders a plain-text legal document with light structure so it reads well:
 * the first line is the title, numbered lines (e.g. "1. …") are section headings,
 * short unpunctuated lines that start a block are sub-headings, lines beginning
 * with "• " are bullets, and everything else is a body paragraph.
 */
@Composable
private fun RawTextScreen(@RawRes resId: Int) {
    val context = LocalContext.current
    val lines = remember(resId) {
        context.resources.openRawResource(resId)
            .bufferedReader().use { it.readText() }
            .replace("\r\n", "\n")
            .split("\n")
    }
    val sectionRegex = remember { Regex("^\\d+\\.\\s.*") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            val prevBlank = (lines.getOrNull(i - 1)?.isBlank()) ?: true
            val nextBlank = (lines.getOrNull(i + 1)?.isBlank()) ?: true
            when {
                line.isBlank() -> Spacer(Modifier.height(10.dp))

                i == 0 -> Text(
                    line,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 2.dp)
                )

                line.startsWith("Last updated:") || line.startsWith("Stand:") -> Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                sectionRegex.matches(line) -> Text(
                    line,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
                )

                line.startsWith("• ") -> Row(modifier = Modifier.padding(top = 2.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.removePrefix("• "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Sub-heading: a short line that opens a block and is not a sentence.
                prevBlank && !nextBlank && line.length <= 52 &&
                    !line.endsWith(".") && !line.endsWith(":") && !line.endsWith(",") -> Text(
                    line,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )

                else -> Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

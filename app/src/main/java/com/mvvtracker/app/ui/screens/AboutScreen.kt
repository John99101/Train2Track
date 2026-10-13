package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mvvtracker.app.R
import com.mvvtracker.app.util.AppInfo

/**
 * Where the app's data comes from and under which licences (the credit the data
 * sources ask for, in a visible place), and the link to the app's own source
 * code. Reached from the Settings and from the credit on the map. The same list
 * is on the website and in the terms of use (legal notice, "Data sources and
 * licences").
 */
@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.about_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Source(
            title = stringResource(R.string.about_transit_title),
            body = stringResource(R.string.about_transit_body),
            links = listOf(
                "transitous.org/sources" to "https://transitous.org/sources/",
                "delfi.de" to "https://www.delfi.de/"
            )
        )
        Source(
            title = stringResource(R.string.about_disruptions_title),
            body = stringResource(R.string.about_disruptions_body),
            links = listOf("gtfs.de" to "https://gtfs.de/de/realtime/")
        )
        Source(
            title = stringResource(R.string.about_map_title),
            body = stringResource(R.string.about_map_body),
            links = listOf("openstreetmap.org/copyright" to OSM_COPYRIGHT_URL)
        )
        Text(
            stringResource(R.string.about_converted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        Text(
            stringResource(R.string.about_open_source_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.about_open_source_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        val uriHandler = LocalUriHandler.current
        Button(
            onClick = { uriHandler.openUri(AppInfo.SOURCE_CODE_URL) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(painterResource(R.drawable.ic_github), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_github))
        }
    }
}

/** One data source: what it provides, its licence and the links to it. */
@Composable
private fun Source(title: String, body: String, links: List<Pair<String, String>>) {
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            links.forEach { (label, url) ->
                TextButton(onClick = { uriHandler.openUri(url) }) { Text(label) }
            }
        }
    }
}

const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ProactiveMessageScheduler
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingProactivePage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    val prefs = remember { ProactiveMessageScheduler.getPrefs(context) }
    val (currentMin, currentMax) = remember { ProactiveMessageScheduler.getConfig(context) }
    var enabled by remember { mutableStateOf(ProactiveMessageScheduler.isEnabled(context)) }
    var minInterval by remember { mutableFloatStateOf(currentMin.toFloat()) }
    var maxInterval by remember { mutableFloatStateOf(currentMax.toFloat()) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_proactive_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_proactive_title)) }
                ) {
                    // Enable/Disable toggle
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_proactive_enabled))
                        },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { newEnabled ->
                                    enabled = newEnabled
                                    if (newEnabled) {
                                        ProactiveMessageScheduler.enable(
                                            context,
                                            minInterval.roundToInt(),
                                            maxInterval.roundToInt()
                                        )
                                    } else {
                                        ProactiveMessageScheduler.disable(context)
                                    }
                                }
                            )
                        }
                    )

                    // Min interval slider
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.setting_proactive_min_interval,
                                    minInterval.roundToInt()
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.setting_proactive_min_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = minInterval,
                                onValueChange = { newValue ->
                                    minInterval = newValue
                                    if (newValue > maxInterval) {
                                        maxInterval = newValue + 1f
                                    }
                                },
                                valueRange = 5f..1440f,
                                steps = 283,  // ~5 minute steps: (1440-5)/5 rounded
                                onValueChangeFinished = {
                                    prefs.edit()
                                        .putInt("min_minutes", minInterval.roundToInt())
                                        .putInt("max_minutes", maxInterval.roundToInt())
                                        .apply()
                                    if (enabled) {
                                        ProactiveMessageScheduler.enable(
                                            context,
                                            minInterval.roundToInt(),
                                            maxInterval.roundToInt()
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${minInterval.roundToInt()} min",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Max interval slider
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.setting_proactive_max_interval,
                                    maxInterval.roundToInt()
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.setting_proactive_max_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = maxInterval,
                                onValueChange = { newValue ->
                                    maxInterval = newValue
                                    if (newValue < minInterval) {
                                        minInterval = (newValue - 1f).coerceAtLeast(5f)
                                    }
                                },
                                valueRange = 5f..1440f,
                                steps = 283,
                                onValueChangeFinished = {
                                    prefs.edit()
                                        .putInt("min_minutes", minInterval.roundToInt())
                                        .putInt("max_minutes", maxInterval.roundToInt())
                                        .apply()
                                    if (enabled) {
                                        ProactiveMessageScheduler.enable(
                                            context,
                                            minInterval.roundToInt(),
                                            maxInterval.roundToInt()
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${maxInterval.roundToInt()} min",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

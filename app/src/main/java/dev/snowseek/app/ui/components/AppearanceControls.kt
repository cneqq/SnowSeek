@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.snowseek.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.snowseek.app.ui.LocalWords

@Composable
internal fun AppearancePage(
    title: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val w = LocalWords.current
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, w.text("Назад", "Back"))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun AppearanceLink(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AppearanceChoice(title: String, selected: Boolean, onClick: () -> Unit, sample: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sample?.invoke()
        }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun AppearanceSlider(
    label: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    onFinished: (() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = description
            },
            thumb = { Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
        )
    }
}

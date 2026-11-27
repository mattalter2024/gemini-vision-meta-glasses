package com.geminivision.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.geminivision.app.data.AppSettings
import com.geminivision.app.data.VideoSourceMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsBottomSheet(
    settings: AppSettings,
    glassesStatusSummary: String,
    isRegisteredWithMeta: Boolean,
    onConnectMetaGlasses: () -> Unit,
    onDisconnectMetaGlasses: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var qaModel by remember(settings) { mutableStateOf(settings.qaModel) }
    var liveModel by remember(settings) { mutableStateOf(settings.liveModel) }
    var liveVoice by remember(settings) { mutableStateOf(settings.liveVoice) }
    var systemPrompt by remember(settings) { mutableStateOf(settings.systemPrompt) }
    var autoListenOnStart by remember(settings) { mutableStateOf(settings.autoListenOnStart) }
    var autoSpeakQa by remember(settings) { mutableStateOf(settings.autoSpeakQa) }
    var preferGlassesAudio by remember(settings) { mutableStateOf(settings.preferGlassesAudio) }
    var videoSource by remember(settings) { mutableStateOf(settings.videoSource) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Gemini & Meta Glasses Settings",
                style = MaterialTheme.typography.titleLarge
            )

            // Meta Glasses section
            Text(
                text = "Camera Source",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = glassesStatusSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoSourceMode.entries.forEach { source ->
                    FilterChip(
                        selected = videoSource == source,
                        onClick = { videoSource = source },
                        label = { Text(source.label) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onConnectMetaGlasses,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRegisteredWithMeta) "Re-sync Meta AI" else "Connect Meta Glasses")
                }
                if (isRegisteredWithMeta) {
                    OutlinedButton(
                        onClick = onDisconnectMetaGlasses,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Unregister")
                    }
                }
            }

            HorizontalDivider()

            // API Key
            Text(
                text = "Gemini API Key",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Google AI Studio API Key") },
                placeholder = { Text("AIza...") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle API key visibility"
                        )
                    }
                }
            )

            // Audio behavior
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-listen on startup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Ready for voice queries immediately when the app opens",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoListenOnStart, onCheckedChange = { autoListenOnStart = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Speak QA answers aloud", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Recommended for Meta glasses (no display)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoSpeakQa, onCheckedChange = { autoSpeakQa = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Prefer Glasses Bluetooth Audio", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Route Live voice through paired glasses mic & speakers",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = preferGlassesAudio, onCheckedChange = { preferGlassesAudio = it })
            }

            HorizontalDivider()

            // QA Model
            Text("Camera QA Model", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppSettings.QA_MODEL_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = qaModel == preset,
                        onClick = { qaModel = preset },
                        label = { Text(preset) }
                    )
                }
            }
            OutlinedTextField(
                value = qaModel,
                onValueChange = { qaModel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("QA Model ID") },
                singleLine = true
            )

            // Live Model & Voice
            Text("Gemini Live Model", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppSettings.LIVE_MODEL_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = liveModel == preset,
                        onClick = { liveModel = preset },
                        label = { Text(preset) }
                    )
                }
            }
            OutlinedTextField(
                value = liveModel,
                onValueChange = { liveModel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Live Model ID") },
                singleLine = true
            )

            Text("Gemini Live Voice", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.VOICE_PRESETS.forEach { voice ->
                    FilterChip(
                        selected = liveVoice == voice,
                        onClick = { liveVoice = voice },
                        label = { Text(voice) }
                    )
                }
            }

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("System Instruction") },
                minLines = 3,
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            apiKey = apiKey.trim(),
                            qaModel = qaModel.trim(),
                            liveModel = liveModel.trim(),
                            liveVoice = liveVoice.trim(),
                            systemPrompt = systemPrompt.trim(),
                            autoListenOnStart = autoListenOnStart,
                            autoSpeakQa = autoSpeakQa,
                            preferGlassesAudio = preferGlassesAudio,
                            videoSource = videoSource
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }
        }
    }
}

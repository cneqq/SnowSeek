@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.snowseek.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.snowseek.core.BuiltInInstructions
import dev.snowseek.core.ChatInstruction

@Composable
fun AppliedInstruction(instruction: ChatInstruction) {
    Text(instruction.title, Modifier.widthIn(max = 560.dp).padding(end = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun InstructionSelector(ui: ChatUiState, model: SnowSeekViewModel) {
    if (ui.selectedId != null || !ui.initialized) return
    val w = LocalWords.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settings by model.settings.collectAsStateWithLifecycle()
    val selected = ui.selectedInstruction
    var show by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChatInstruction?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatInstruction?>(null) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { keyboard?.hide(); show = true },
            enabled = !ui.generating && !ui.loading,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 280.dp),
            label = { Text(selected?.title ?: w.text("Инструкции", "Instructions"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) },
        )
    }
    if (show && !creating && editing == null && deleting == null) {
        ModalBottomSheet(
            onDismissRequest = { show = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(w.text("Инструкции", "Instructions"), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
                InstructionRow(w.text("Без инструкции", "No instruction"), selected == null,
                    onChoose = { model.selectInstruction(null); show = false })
                BuiltInInstructions.all.forEach { instruction ->
                    InstructionRow(instruction.title, selected?.id == instruction.id,
                        onChoose = { model.selectInstruction(instruction); show = false })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (settings.customInstructions.isNotEmpty()) {
                    Text(w.text("Мои инструкции", "My instructions"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                settings.customInstructions.forEach { instruction ->
                    InstructionRow(instruction.title, selected?.id == instruction.id,
                        onChoose = { model.selectInstruction(instruction); show = false },
                        onEdit = { editing = instruction }, onDelete = { deleting = instruction })
                }
                TextButton(onClick = { creating = true }, enabled = settings.customInstructions.size < 100,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(w.text("Новая инструкция", "New instruction"))
                }
            }
        }
    }
    if (creating || editing != null) InstructionEditor(editing,
        onDismiss = { creating = false; editing = null },
        onSave = { title, text -> model.saveInstruction(editing?.id, title, text); creating = false; editing = null })
    deleting?.let { instruction ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(w.text("Удалить инструкцию?", "Delete instruction?")) },
            text = { Text(instruction.title) },
            confirmButton = { TextButton(onClick = { model.deleteInstruction(instruction.id); deleting = null }) { Text(w.text("Удалить", "Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(w.text("Отмена", "Cancel")) } })
    }
}

@Composable
private fun InstructionRow(title: String, selected: Boolean, onChoose: () -> Unit,
    onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val w = LocalWords.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onChoose)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
        }
        if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, w.text("Изменить инструкцию", "Edit instruction")) }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, w.text("Удалить инструкцию", "Delete instruction")) }
    }
}

@Composable
private fun InstructionEditor(instruction: ChatInstruction?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val w = LocalWords.current
    var title by rememberSaveable(instruction?.id) { mutableStateOf(instruction?.title.orEmpty()) }
    var text by rememberSaveable(instruction?.id) { mutableStateOf(instruction?.text.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(w.text(if (instruction == null) "Новая инструкция" else "Изменить инструкцию",
                if (instruction == null) "New instruction" else "Edit instruction"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text(w.text("Название", "Title")) })
            OutlinedTextField(value = text, onValueChange = { text = it.take(8_000) }, minLines = 3, maxLines = 6,
                modifier = Modifier.fillMaxWidth(), label = { Text(w.text("Как отвечать", "How to respond")) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(w.text("Отмена", "Cancel")) }
                Button(onClick = { onSave(title.trim(), text.trim()) }, enabled = title.isNotBlank() && text.isNotBlank()) {
                    Text(w.text("Сохранить", "Save"))
                }
            }
        }
    }
}

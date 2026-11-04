package dev.snowseek.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.snowseek.core.BuiltInInstructions
import dev.snowseek.core.ChatInstruction

@Composable
fun AppliedInstruction(instruction: ChatInstruction) {
    val w = LocalWords.current
    var show by rememberSaveable(instruction.id, instruction.text) { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = Modifier.widthIn(max = 560.dp)) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(instruction.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (show) AlertDialog(onDismissRequest = { show = false },
        title = { Text(instruction.title) },
        text = { Text(instruction.text, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { show = false }) { Text(w.text("Закрыть", "Close")) } })
}

@Composable
fun InstructionSelector(ui: ChatUiState, model: SnowSeekViewModel) {
    val w = LocalWords.current
    val settings by model.settings.collectAsStateWithLifecycle()
    val messages = ui.chats.firstOrNull { it.id == ui.selectedId }?.messages.orEmpty()
    val canChoose = messages.isEmpty() && !ui.loading && !ui.generating
    val applied = messages.firstOrNull()?.instruction
    val selected = if (messages.isEmpty()) ui.selectedInstruction else applied
    var show by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ChatInstruction?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatInstruction?>(null) }
    TextButton(onClick = { show = true }, enabled = !ui.generating && !ui.loading,
        modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(selected?.title ?: w.text("Инструкции", "Instructions"), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (show) AlertDialog(onDismissRequest = { show = false },
        title = { Text(w.text("Инструкции", "Instructions")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(w.text(if (canChoose) "Выберите инструкцию для нового чата. Она будет действовать с первого сообщения."
                    else "Инструкция выбирается до первого сообщения. Свои шаблоны можно подготовить для новых чатов.",
                    if (canChoose) "Choose an instruction for this new chat. It applies from the first message."
                    else "Choose before the first message. You can manage templates for new chats."),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.selectInstruction(null); show = false }, enabled = canChoose) {
                    Text(w.text("Без инструкции", "No instruction"))
                }
                Text(w.text("Системные", "Built-in"), style = MaterialTheme.typography.labelLarge)
                BuiltInInstructions.all.forEach { instruction ->
                    InstructionRow(instruction, canChoose, selected?.id == instruction.id,
                        onChoose = { model.selectInstruction(instruction); show = false })
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(w.text("Мои инструкции", "My instructions"), style = MaterialTheme.typography.labelLarge)
                settings.customInstructions.forEach { instruction ->
                    InstructionRow(instruction, canChoose, selected?.id == instruction.id,
                        onChoose = { model.selectInstruction(instruction); show = false },
                        onEdit = { editing = instruction }, onDelete = { deleting = instruction })
                }
                TextButton(onClick = { creating = true }, enabled = settings.customInstructions.size < 100) {
                    Icon(Icons.Outlined.Add, null)
                    Text(w.text("Добавить свою", "Add instruction"))
                }
            }
        }, confirmButton = { TextButton(onClick = { show = false }) { Text(w.text("Закрыть", "Close")) } })
    if (creating || editing != null) InstructionEditor(editing,
        onDismiss = { creating = false; editing = null },
        onSave = { title, text -> model.saveInstruction(editing?.id, title, text); creating = false; editing = null })
    deleting?.let { instruction ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(w.text("Удалить инструкцию?", "Delete instruction?")) },
            text = { Text(w.text("В старых сообщениях сохранится применённая версия.", "Existing messages keep the applied version.")) },
            confirmButton = { TextButton(onClick = { model.deleteInstruction(instruction.id); deleting = null }) { Text(w.text("Удалить", "Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(w.text("Отмена", "Cancel")) } })
    }
}

@Composable
private fun InstructionRow(instruction: ChatInstruction, enabled: Boolean, selected: Boolean,
    onChoose: () -> Unit, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val w = LocalWords.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onChoose, enabled = enabled, modifier = Modifier.weight(1f)) {
                if (selected) { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                Text(instruction.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, w.text("Изменить инструкцию", "Edit instruction")) }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, w.text("Удалить инструкцию", "Delete instruction")) }
        }
        Text(instruction.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InstructionEditor(instruction: ChatInstruction?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val w = LocalWords.current
    var title by rememberSaveable(instruction?.id) { mutableStateOf(instruction?.title.orEmpty()) }
    var text by rememberSaveable(instruction?.id) { mutableStateOf(instruction?.text.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(w.text(if (instruction == null) "Новая инструкция" else "Изменить инструкцию",
            if (instruction == null) "New instruction" else "Edit instruction")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(w.text("Название", "Title")) })
                OutlinedTextField(value = text, onValueChange = { text = it.take(8_000) }, minLines = 5, maxLines = 10,
                    modifier = Modifier.fillMaxWidth(), label = { Text(w.text("Текст инструкции", "Instruction text")) },
                    supportingText = { Text(text.length.toString() + " / 8000") })
            }
        }, confirmButton = {
            TextButton(onClick = { onSave(title.trim(), text.trim()) }, enabled = title.isNotBlank() && text.isNotBlank()) {
                Text(w.text("Сохранить", "Save"))
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(w.text("Отмена", "Cancel")) } })
}

package dev.snowseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.snowseek.core.ChatSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal enum class ChatAge { PINNED, TODAY, YESTERDAY, WEEK, OLD }

internal fun groupChats(chats: List<ChatSession>, today: LocalDate, zone: ZoneId): Map<ChatAge, List<ChatSession>> {
    val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return chats.sortedByDescending { it.updatedAt }.groupBy { chat ->
        val date = Instant.ofEpochMilli(chat.updatedAt).atZone(zone).toLocalDate()
        when {
            chat.pinned -> ChatAge.PINNED
            date >= today -> ChatAge.TODAY
            date == today.minusDays(1) -> ChatAge.YESTERDAY
            date >= week -> ChatAge.WEEK
            else -> ChatAge.OLD
        }
    }
}

internal fun Words.ageName(age: ChatAge): String = when (age) {
    ChatAge.PINNED -> text("Закреплено", "Pinned")
    ChatAge.TODAY -> text("Сегодня", "Today")
    ChatAge.YESTERDAY -> text("Вчера", "Yesterday")
    ChatAge.WEEK -> text("На этой неделе", "This week")
    ChatAge.OLD -> text("Давно", "Older")
}

@Composable
internal fun ChatHistoryRow(
    title: String,
    selected: Boolean,
    pinned: Boolean,
    archived: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onStartSelection: () -> Unit,
    onToggleSelection: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val w = LocalWords.current
    var actions by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
            .combinedClickable(onClick = onSelect, onLongClick = onStartSelection).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = checked, onCheckedChange = { onToggleSelection() }, enabled = enabled,
                modifier = Modifier.padding(end = 4.dp))
        } else if (pinned) {
            Icon(Icons.Outlined.PushPin, w.text("Закреплённый чат", "Pinned chat"),
                Modifier.padding(end = 8.dp).size(15.dp), tint = colors.onSurfaceVariant)
        }
        Text(title, Modifier.weight(1f).padding(vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (!selectionMode) Box {
            IconButton(onClick = { actions = true }, enabled = enabled) {
                Icon(Icons.Outlined.MoreHoriz, w.text("Действия с чатом", "Chat actions"),
                    Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = actions, onDismissRequest = { actions = false },
                containerColor = colors.surfaceContainer,
            ) {
                DropdownMenuItem(
                    text = { Text(w.text("Переименовать", "Rename")) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { actions = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(if (pinned) w.text("Открепить", "Unpin") else w.text("Закрепить", "Pin")) },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                    onClick = { actions = false; onPin() },
                )
                DropdownMenuItem(
                    text = { Text(if (archived) w.text("Вернуть из архива", "Restore from archive") else w.text("В архив", "Archive")) },
                    leadingIcon = { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                    onClick = { actions = false; onArchive() },
                )
                DropdownMenuItem(
                    text = { Text(w.text("Удалить чат", "Delete chat")) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    onClick = { actions = false; onDelete() },
                )
            }
        }
    }
}

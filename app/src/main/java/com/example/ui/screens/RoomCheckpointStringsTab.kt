package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.TranslationEntryEntity
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BulkAmber
import com.example.ui.theme.CodeViolet
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PatchEmerald
import com.example.ui.theme.ScoutCyan

@Composable
fun RoomCheckpointStringsTab(
    entries: List<TranslationEntryEntity>,
    onManualEditSave: (TranslationEntryEntity, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var editingEntry by remember { mutableStateOf<TranslationEntryEntity?>(null) }

    val filteredEntries by remember(entries, searchQuery, statusFilter) {
        derivedStateOf {
            entries.filter { entry ->
                val matchesStatus = when (statusFilter) {
                    "TRANSLATED" -> entry.status != TranslationEntryEntity.STATUS_PENDING
                    "PENDING" -> entry.status == TranslationEntryEntity.STATUS_PENDING
                    else -> true
                }
                val matchesSearch = searchQuery.isBlank() ||
                    entry.originalText.contains(searchQuery, ignoreCase = true) ||
                    entry.translatedText.orEmpty().contains(searchQuery, ignoreCase = true)
                matchesStatus && matchesSearch
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_room_strings_input"),
            label = { Text("Tìm chuỗi thoại gốc hoặc bản dịch trong Room SQLite...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Tìm kiếm") },
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val translatedCount = entries.count { it.status != TranslationEntryEntity.STATUS_PENDING }
            val pendingCount = entries.size - translatedCount

            FilterChip(
                selected = statusFilter == "ALL",
                onClick = { statusFilter = "ALL" },
                label = { Text("Tất cả (${entries.size})") }
            )
            FilterChip(
                selected = statusFilter == "TRANSLATED",
                onClick = { statusFilter = "TRANSLATED" },
                label = { Text("Đã dịch ($translatedCount)") }
            )
            FilterChip(
                selected = statusFilter == "PENDING",
                onClick = { statusFilter = "PENDING" },
                label = { Text("Chờ dịch ($pendingCount)") }
            )
        }

        if (filteredEntries.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = "Room DB Empty",
                        tint = ScoutCyan
                    )
                    Text(
                        text = "Chưa có dữ liệu chuỗi trong Room Database",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Hãy bấm 'Nạp ROM Mẫu' và 'Chạy Việt Hóa AI 2 Tầng' ở tab Luồng Dịch để trích xuất & lưu các câu thoại vào SQLite.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("room_entries_lazy_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = filteredEntries,
                    key = { "${it.romId}_${it.stringIndex}" }
                ) { entry ->
                    RoomTranslationEntryCard(
                        entry = entry,
                        onClickEdit = { editingEntry = entry }
                    )
                }
            }
        }
    }

    editingEntry?.let { target ->
        var draftText by remember(target) {
            mutableStateOf(target.translatedText ?: target.originalText)
        }
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            title = { Text("Chỉnh sửa câu #${target.stringIndex + 1} (Lưu Room DB)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Câu gốc: ${target.originalText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        label = { Text("Bản dịch tiếng Việt (giữ nguyên mã %s, <color>, SYS_BTN)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onManualEditSave(target, draftText)
                        editingEntry = null
                    }
                ) {
                    Text("Lưu vào Room")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEntry = null }) {
                    Text("Hủy")
                }
            }
        )
    }
}

@Composable
private fun RoomTranslationEntryCard(
    entry: TranslationEntryEntity,
    onClickEdit: () -> Unit
) {
    val isTranslated = !entry.translatedText.isNullOrBlank()
    val statusColor = when (entry.status) {
        TranslationEntryEntity.STATUS_PATCHED -> PatchEmerald
        TranslationEntryEntity.STATUS_TRANSLATED -> ScoutCyan
        else -> BulkAmber
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .clickable { onClickEdit() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "#${entry.stringIndex + 1} • ${entry.status}",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                    Text(
                        text = "Offset 0x${entry.byteOffsetInIso.toString(16).uppercase()} (${entry.allocatedByteLength}B)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Sửa câu dịch",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Gốc: ${entry.originalText}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Masked (Tầng 2): ${entry.maskedText}",
                style = MaterialTheme.typography.labelSmall,
                color = CodeViolet
            )

            if (isTranslated) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Việt hóa: ${entry.translatedText}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = PatchEmerald
                )
            }
        }
    }
}

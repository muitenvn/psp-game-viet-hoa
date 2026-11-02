package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.domain.ai.SystemTagMasker
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BulkAmber
import com.example.ui.theme.CodeViolet
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PatchEmerald
import com.example.ui.theme.ScoutCyan
import com.example.ui.viewmodel.LocalizationUiState

@Composable
fun Tier1RulesAndTreeTab(
    uiState: LocalizationUiState,
    onUpdateRegexPattern: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var editableRegex by remember(uiState.translationRules.systemTagRegex) {
        mutableStateOf(uiState.translationRules.systemTagRegex)
    }
    var sampleTestLine by remember {
        mutableStateOf("<color=01>[Commander Vance]</color> Watch your six, %s! Press SYS_BTN_CROSS to deploy shield.\\n")
    }

    val compiledPreviewRegex = remember(editableRegex) {
        SystemTagMasker.compileSafeRegex(editableRegex)
    }
    val previewMasked = remember(sampleTestLine, compiledPreviewRegex) {
        SystemTagMasker.maskSystemTags(0, sampleTestLine, compiledPreviewRegex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Tier 1 Task 1: ISO-9660 Directory Tree & Candidate Script Files
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = "Cây thư mục ISO",
                        tint = ScoutCyan
                    )
                    Text(
                        text = "Nhiệm Vụ 1 (Tầng 1): Dò Cây Thư Mục PSP_GAME/USRDIR",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                Text(
                    text = uiState.scoutResult?.analysisSummary
                        ?: "Chưa quét file .iso. Bấm 'Nạp ROM Mẫu' ở tab Luồng Dịch để xem cây thư mục PSP_GAME/USRDIR.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val files = uiState.isoEntries.filter { !it.isDirectory }
                files.forEach { fileEntry ->
                    val isPrimary = fileEntry.path.equals(
                        uiState.scoutResult?.primaryTargetFile,
                        ignoreCase = true
                    )
                    val isSkippedEboot = fileEntry.isSystemExecutable
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    isPrimary -> ScoutCyan.copy(alpha = 0.14f)
                                    fileEntry.isCandidateArchiveOrScript -> PatchEmerald.copy(alpha = 0.10f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = when {
                                    isPrimary -> ScoutCyan
                                    fileEntry.isCandidateArchiveOrScript -> PatchEmerald.copy(alpha = 0.5f)
                                    else -> Color.Transparent
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = fileEntry.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPrimary) ScoutCyan else MaterialTheme.colorScheme.onSurface
                                )
                                if (isPrimary) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(ScoutCyan)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "AI TẦNG 1 CHỈ ĐIỂM",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Black
                                        )
                                    }
                                } else if (isSkippedEboot) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFEF4444).copy(alpha = 0.25f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "BỎ QUA EBOOT (CHỈ QUÉT USRDIR)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFFCA5A5)
                                        )
                                    }
                                } else if (fileEntry.isCandidateArchiveOrScript) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(PatchEmerald.copy(alpha = 0.22f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "USRDIR ARCHIVE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PatchEmerald
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "LBA: ${fileEntry.lbaSector} (0x${(fileEntry.byteOffsetInIso).toString(16).uppercase()}) • Size: ${fileEntry.sizeBytes} B • Magic: [${fileEntry.magic4BytesHex.ifBlank { "N/A" }}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Định dạng: ${fileEntry.containerFormatLabel}" +
                                    (if (fileEntry.subFileCount > 0) " • TOC Sub-files: ${fileEntry.subFileCount}" else "") +
                                    (if (fileEntry.hasPointerTable) " • Pointer Table: CÓ" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (fileEntry.hasPointerTable) PatchEmerald else CodeViolet
                            )
                            if (fileEntry.asciiSamplePreview.isNotBlank()) {
                                Text(
                                    text = "Header ASCII: \"${fileEntry.asciiSamplePreview.take(44)}\"",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BulkAmber
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Tier 1 Task 2: Regex Sandbox & System Code Masking ({0}, {1})
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = "Regex Bảo vệ mã hệ thống",
                        tint = BulkAmber
                    )
                    Text(
                        text = "Nhiệm Vụ 2 (Tầng 1): Regex Ẩn Mã Hệ Thống Thành {0}, {1}",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                OutlinedTextField(
                    value = editableRegex,
                    onValueChange = { editableRegex = it },
                    label = { Text("Regex nhận diện mã hệ thống KHÔNG ĐƯỢC DỊCH") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("regex_pattern_input"),
                    textStyle = MaterialTheme.typography.labelSmall
                )

                Button(
                    onClick = { onUpdateRegexPattern(editableRegex) },
                    modifier = Modifier.testTag("apply_regex_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Áp dụng Regex")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Áp Dụng Regex & Mask Lại Bộ Nhớ Room")
                }

                // Live Masking Sandbox Preview
                OutlinedTextField(
                    value = sampleTestLine,
                    onValueChange = { sampleTestLine = it },
                    label = { Text("Thử nghiệm chuỗi gốc trong ROM") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.labelSmall
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF06090F))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Chuỗi gửi cho AI Tầng 2 (Đã ẩn mã thành {0}, {1}):",
                        style = MaterialTheme.typography.labelSmall,
                        color = ScoutCyan
                    )
                    Text(
                        text = previewMasked.maskedText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Bảng ánh xạ khôi phục sau khi dịch (${previewMasked.placeholderMap.size} thẻ):",
                        style = MaterialTheme.typography.labelSmall,
                        color = BulkAmber
                    )
                    Text(
                        text = previewMasked.placeholderMap.entries.joinToString("  |  ") { "${it.key} => ${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = CodeViolet
                    )
                }
            }
        }

        // 3. `translation_rules.json` Mature Tone, Pronouns & Glossary Viewer
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "translation_rules.json",
                        tint = PatchEmerald
                    )
                    Text(
                        text = "Cấu Hình translation_rules.json (Từ AI Tầng 1)",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                Text(
                    text = "Bối cảnh: ${uiState.translationRules.gameGenreAndContext}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ScoutCyan
                )
                Text(
                    text = "Định hướng văn phong: ${uiState.translationRules.toneGuidelines}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF06090F))
                        .padding(12.dp)
                ) {
                    Text(
                        text = uiState.translationRules.toCompactJsonString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = PatchEmerald
                    )
                }
            }
        }
    }
}

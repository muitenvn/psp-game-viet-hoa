package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.PipelineStage
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BulkAmber
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.PatchEmerald
import com.example.ui.theme.ScoutCyan
import com.example.ui.viewmodel.LocalizationUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PipelineDashboardTab(
    uiState: LocalizationUiState,
    onPickSafIso: () -> Unit,
    onGenerateSampleIso: () -> Unit,
    onStartOrResumePipeline: () -> Unit,
    onPausePipeline: () -> Unit,
    onResetCheckpoint: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onTier1ModelChange: (String) -> Unit,
    onTier2ModelChange: (String) -> Unit,
    onBatchSizeChange: (Int) -> Unit,
    onRateLimitChange: (Int) -> Unit,
    onGoogleFallbackChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showApiKey by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero Banner + Active ROM Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = ObsidianCardElevated)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.ic_hero_psp_rom),
                    contentDescription = "PSP Localization Studio Hero Banner",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ScoutCyan.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "TWO-TIER AI ROM ENGINE",
                                style = MaterialTheme.typography.labelSmall,
                                color = ScoutCyan
                            )
                        }
                        if (uiState.isoSizeBytes > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BulkAmber.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${uiState.isoSizeBytes / 1024} KB • SAF RAF",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BulkAmber
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.romTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (uiState.isoFileName.isNotBlank()) {
                            "File: ${uiState.isoFileName} • Target: ${uiState.scoutResult?.primaryTargetFile ?: "PSP_GAME/USRDIR/..."}"
                        } else {
                            "Đọc/ghi trực tiếp file .iso qua SAF RandomAccessFile không tràn RAM"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action Buttons for ISO Selection & Sample Creation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPickSafIso,
                    enabled = !uiState.isRunning,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_saf_iso_button")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Chọn file ISO")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mở .ISO (SAF)")
                }

                Button(
                    onClick = onGenerateSampleIso,
                    enabled = !uiState.isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("generate_sample_iso_button")
                ) {
                    Icon(Icons.Default.Album, contentDescription = "Tạo ROM PSP Mẫu")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nạp ROM Mẫu")
                }
            }
        }

        // 2. 4-Stage Pipeline Progress Tracker
        PipelineProgressCard(
            uiState = uiState,
            onStartOrResume = onStartOrResumePipeline,
            onPause = onPausePipeline,
            onResetCheckpoint = onResetCheckpoint
        )

        // 3. API Key & Two-Tier Model Selector Card
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Cấu hình API & Model 2 Tầng",
                        tint = ScoutCyan
                    )
                    Text(
                        text = "Cấu Hình API Key & Chọn Model 2 Tầng",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                OutlinedTextField(
                    value = uiState.customApiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input"),
                    label = { Text("Gemini API Key (Để trống sẽ dùng Firebase AI Logic mặc định)") },
                    placeholder = { Text("AIzaSy... (Tùy chọn nhập key riêng nếu dùng REST trực tiếp)") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Ẩn/Hiện API Key"
                            )
                        }
                    }
                )

                // Tier 1 Model Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Tầng 1",
                            tint = ScoutCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Model Tầng 1: AI Trinh sát & Lập quy tắc (Chỉ gọi 1-2 request/game)",
                            style = MaterialTheme.typography.titleMedium,
                            color = ScoutCyan
                        )
                    }
                    val tier1Options = listOf(
                        "gemini-2.5-flash",
                        "gemini-3.8-flash",
                        "gemini-4-argon"
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tier1Options.forEach { model ->
                            FilterChip(
                                selected = uiState.tier1Model == model,
                                onClick = { onTier1ModelChange(model) },
                                label = { Text(model, fontFamily = JetBrainsMonoFontFamily) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ScoutCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = ScoutCyan
                                ),
                                modifier = Modifier.testTag("tier1_model_$model")
                            )
                        }
                    }
                }

                // Tier 2 Model Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Tầng 2",
                            tint = BulkAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Model Tầng 2: AI Cày cuốc (Dịch Batch 100-150 câu + Ẩn thẻ {0})",
                            style = MaterialTheme.typography.titleMedium,
                            color = BulkAmber
                        )
                    }
                    val tier2Options = listOf(
                        "gemini-2.5-flash-lite",
                        "gemini-3.5-flash-lite",
                        "google-translate-endpoint"
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tier2Options.forEach { model ->
                            FilterChip(
                                selected = uiState.tier2Model == model,
                                onClick = { onTier2ModelChange(model) },
                                label = { Text(model, fontFamily = JetBrainsMonoFontFamily) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BulkAmber.copy(alpha = 0.22f),
                                    selectedLabelColor = BulkAmber
                                ),
                                modifier = Modifier.testTag("tier2_model_$model")
                            )
                        }
                    }
                }

                // Batch size & 4-second Rate Limit Controls
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Kích thước Batch Tầng 2: ${uiState.batchSize} câu / request",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Tiết kiệm 99% RPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = PatchEmerald
                        )
                    }
                    Slider(
                        value = uiState.batchSize.toFloat(),
                        onValueChange = { onBatchSizeChange(it.toInt()) },
                        valueRange = 50f..150f,
                        steps = 3,
                        modifier = Modifier.testTag("batch_size_slider")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tự động nghỉ ${uiState.rateLimitDelaySec}s giữa các Batch (Chống lỗi 429 Free Quota)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Tự động chuyển sang Google Translate Endpoint nếu cạn RPD trong ngày",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.useGoogleFallback,
                        onCheckedChange = onGoogleFallbackChange,
                        modifier = Modifier.testTag("google_fallback_switch")
                    )
                }
            }
        }

        // 4. Live Engineering Console Log
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF06090F))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TELEMETRY & PIPELINE LOGS",
                        style = MaterialTheme.typography.labelSmall,
                        color = ScoutCyan
                    )
                    Text(
                        text = "${uiState.consoleLogs.size} events",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState.consoleLogs.take(10).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            line.contains("[TẦNG 1") -> ScoutCyan
                            line.contains("[TẦNG 2") -> BulkAmber
                            line.contains("[HOÀN TẤT]") || line.contains("Checkpoint") -> PatchEmerald
                            line.contains("[LỖI]") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineProgressCard(
    uiState: LocalizationUiState,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onResetCheckpoint: () -> Unit
) {
    val progressFraction = if (uiState.totalStrings > 0) {
        (uiState.translatedStrings.toFloat() / uiState.totalStrings.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ScoutCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TRẠNG THÁI VIỆT HÓA & ROOM CHECKPOINT",
                        style = MaterialTheme.typography.labelSmall,
                        color = ScoutCyan
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = uiState.statusHeadline,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                // Cooldown badge when sleeping 4s between batches
                AnimatedVisibility(visible = uiState.cooldownRemainingSec > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(BulkAmber.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Rate Limit Timer",
                            tint = BulkAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Nghỉ ${uiState.cooldownRemainingSec}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = BulkAmber
                        )
                    }
                }
            }

            Text(
                text = uiState.statusSubtext,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 4-Step Pipeline Visual Stepper:
            // Đang dò file -> Đang phân tích mã -> Đang dịch câu X/Y -> Đang đóng gói ISO
            val steps = listOf(
                1 to "1. Dò file ISO",
                2 to "2. Phân tích mã",
                3 to "3. Dịch ${uiState.translatedStrings}/${uiState.totalStrings}",
                4 to "4. Đóng gói ISO"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                steps.forEach { (stepNum, title) ->
                    val isDone = uiState.stage.stepIndex > stepNum || uiState.stage == PipelineStage.COMPLETED
                    val isCurrent = uiState.stage.stepIndex == stepNum && uiState.stage != PipelineStage.COMPLETED
                    val bgColor = when {
                        isDone -> PatchEmerald.copy(alpha = 0.2f)
                        isCurrent -> ScoutCyan.copy(alpha = 0.22f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                    val textColor = when {
                        isDone -> PatchEmerald
                        isCurrent -> ScoutCyan
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bgColor)
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor,
                            maxLines = 1
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = if (uiState.stage == PipelineStage.COMPLETED) PatchEmerald else ScoutCyan,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = "Room SQLite Checkpoint",
                        tint = PatchEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Room DB: ${uiState.translatedStrings}/${uiState.totalStrings} câu đã lưu",
                        style = MaterialTheme.typography.labelSmall,
                        color = PatchEmerald
                    )
                }
                Text(
                    text = "Đã đóng gói ISO: ${uiState.patchedStrings} câu",
                    style = MaterialTheme.typography.labelSmall,
                    color = BulkAmber
                )
            }

            // Primary Start / Resume / Pause Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (uiState.isRunning) {
                    Button(
                        onClick = onPause,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BulkAmber,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pause_pipeline_button")
                    ) {
                        Icon(Icons.Default.PauseCircle, contentDescription = "Tạm dừng")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tạm Dừng (Lưu Checkpoint Room)")
                    }
                } else {
                    val hasPartialCheckpoint =
                        uiState.translatedStrings in 1 until uiState.totalStrings
                    Button(
                        onClick = onStartOrResume,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ScoutCyan,
                            contentColor = Color(0xFF00201C)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_or_resume_pipeline_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.stage == PipelineStage.COMPLETED) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                            contentDescription = "Chạy hoặc tiếp tục dịch"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                hasPartialCheckpoint -> "Tiếp Tục Dịch Từ Câu #${uiState.translatedStrings + 1}"
                                uiState.stage == PipelineStage.COMPLETED -> "Đóng Gói Lại / Chạy Lại"
                                else -> "Chạy Việt Hóa AI 2 Tầng"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (uiState.totalStrings > 0) {
                        OutlinedButton(
                            onClick = onResetCheckpoint,
                            modifier = Modifier.testTag("reset_checkpoint_button")
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Xóa Checkpoint")
                        }
                    }
                }
            }
        }
    }
}

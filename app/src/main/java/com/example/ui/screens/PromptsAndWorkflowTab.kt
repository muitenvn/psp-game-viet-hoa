package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.domain.ai.TwoTierPrompts
import com.example.domain.model.TranslationRules
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BulkAmber
import com.example.ui.theme.PatchEmerald
import com.example.ui.theme.ScoutCyan

@Composable
fun PromptsAndWorkflowTab(
    currentRules: TranslationRules,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val tier2PromptSample = TwoTierPrompts.buildTier2BulkSystemPrompt(currentRules)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PromptSpecificationCard(
            title = "1A. System Prompt AI Tầng 1 (Nhiệm vụ 1: Dò tìm file ngôn ngữ ISO)",
            badge = "gemini-2.5-flash / gemini-3.8-flash",
            accentColor = ScoutCyan,
            codeContent = TwoTierPrompts.TIER1_SCOUT_FILE_DISCOVERY_SYSTEM_PROMPT.trim(),
            onCopy = { copyText(context, "Tier 1 Scout System Prompt", it) }
        )

        PromptSpecificationCard(
            title = "1B. System Prompt AI Tầng 1 (Nhiệm vụ 2: Lọc mã Regex & Lập quy tắc dịch)",
            badge = "Sinh translation_rules.json",
            accentColor = ScoutCyan,
            codeContent = TwoTierPrompts.TIER1_CONTEXT_AND_RULES_SYSTEM_PROMPT.trim(),
            onCopy = { copyText(context, "Tier 1 Rules System Prompt", it) }
        )

        PromptSpecificationCard(
            title = "2. System Prompt AI Tầng 2 (Dịch hàng loạt 100-150 câu + Bảo toàn {0}, {1})",
            badge = "gemini-2.5-flash-lite / gemini-3.5-flash-lite",
            accentColor = BulkAmber,
            codeContent = tier2PromptSample,
            onCopy = { copyText(context, "Tier 2 Bulk System Prompt", it) }
        )

        PromptSpecificationCard(
            title = "3. GitHub Actions Tự Động Xuất .APK Lên GitHub Releases",
            badge = ".github/workflows/build-apk.yml",
            accentColor = PatchEmerald,
            codeContent = GITHUB_RELEASE_YML_PREVIEW,
            onCopy = { copyText(context, "build-apk.yml", it) }
        )
    }
}

@Composable
private fun PromptSpecificationCard(
    title: String,
    badge: String,
    accentColor: Color,
    codeContent: String,
    onCopy: (String) -> Unit
) {
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                OutlinedButton(onClick = { onCopy(codeContent) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Sao chép")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF06090F))
                    .padding(12.dp)
            ) {
                Text(
                    text = codeContent,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE2E8F0)
                )
            }
        }
    }
}

private fun copyText(context: Context, label: String, content: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
    Toast.makeText(context, "Đã sao chép $label vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
}

private const val GITHUB_RELEASE_YML_PREVIEW = """
name: Build APK & Publish Release
on:
  push:
    branches: [ main, master, '**' ]
    tags: [ 'v*' ]
  workflow_dispatch:
permissions:
  contents: write
jobs:
  build-apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '9.3.1'
      - name: Build Debug APK
        run: gradle :app:assembleDebug --no-daemon
      - name: Publish APK to GitHub Releases
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1.0.${'$'}{{ github.run_number }}
          files: app/build/outputs/apk/debug/app-debug.apk
        env:
          GITHUB_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
"""

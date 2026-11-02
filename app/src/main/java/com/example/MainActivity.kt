package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.configureAppCheck
import com.example.ui.screens.PipelineDashboardTab
import com.example.ui.screens.PromptsAndWorkflowTab
import com.example.ui.screens.RoomCheckpointStringsTab
import com.example.ui.screens.Tier1RulesAndTreeTab
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ScoutCyan
import com.example.ui.viewmodel.LocalizationViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Mandatory Firebase App Check configuration with launch intent before UI/ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PspLocalizationStudioApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PspLocalizationStudioApp(
    viewModel: LocalizationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val romEntries by viewModel.romEntries.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // BackHandler returns to primary Pipeline tab when on secondary tabs
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    // SAF Document Picker for .iso files
    val safIsoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onSafIsoUriSelected(uri)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.hero_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = ScoutCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Luồng Dịch") },
                    label = { Text(stringResource(R.string.tab_pipeline)) },
                    modifier = Modifier.testTag("nav_tab_pipeline")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AccountTree, contentDescription = "Tầng 1 & Regex") },
                    label = { Text(stringResource(R.string.tab_rules)) },
                    modifier = Modifier.testTag("nav_tab_rules")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Storage, contentDescription = "Bộ Nhớ Room") },
                    label = { Text("${stringResource(R.string.tab_strings)} (${romEntries.size})") },
                    modifier = Modifier.testTag("nav_tab_strings")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Code, contentDescription = "Prompts & CI") },
                    label = { Text(stringResource(R.string.tab_prompts)) },
                    modifier = Modifier.testTag("nav_tab_prompts")
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> PipelineDashboardTab(
                uiState = uiState,
                onPickSafIso = { safIsoLauncher.launch(arrayOf("*/*")) },
                onGenerateSampleIso = viewModel::generateAndLoadSamplePspIso,
                onStartOrResumePipeline = viewModel::startOrResumeTwoTierPipeline,
                onPausePipeline = viewModel::pausePipeline,
                onResetCheckpoint = viewModel::resetCurrentRomCheckpoint,
                onApiKeyChange = viewModel::updateApiKey,
                onTier1ModelChange = viewModel::updateTier1Model,
                onTier2ModelChange = viewModel::updateTier2Model,
                onBatchSizeChange = viewModel::updateBatchSize,
                onRateLimitChange = viewModel::updateRateLimitDelay,
                onGoogleFallbackChange = viewModel::updateGoogleFallback,
                modifier = Modifier.padding(innerPadding)
            )
            1 -> Tier1RulesAndTreeTab(
                uiState = uiState,
                onUpdateRegexPattern = viewModel::updateCustomRegexAndRemask,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> RoomCheckpointStringsTab(
                entries = romEntries,
                onManualEditSave = viewModel::updateSingleStringTranslation,
                modifier = Modifier.padding(innerPadding)
            )
            3 -> PromptsAndWorkflowTab(
                currentRules = uiState.translationRules,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

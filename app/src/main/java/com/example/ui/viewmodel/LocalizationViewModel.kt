package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.RomSessionEntity
import com.example.data.local.TranslationDatabase
import com.example.data.local.TranslationEntryEntity
import com.example.data.repository.TranslationRepository
import com.example.domain.ai.SystemTagMasker
import com.example.domain.ai.TwoTierTranslationManager
import com.example.domain.iso.Iso9660RandomAccessEngine
import com.example.domain.model.IsoFileEntry
import com.example.domain.model.PipelineStage
import com.example.domain.model.ScoutFileAnalysisResult
import com.example.domain.model.TranslationRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "LocalizationVM"

data class LocalizationUiState(
    val activeRomId: String = "",
    val romTitle: String = "Chưa nạp ROM PSP (.iso)",
    val isoFileName: String = "",
    val isoUri: Uri? = null,
    val isoSizeBytes: Long = 0L,
    val isoEntries: List<IsoFileEntry> = emptyList(),
    val scoutResult: ScoutFileAnalysisResult? = null,
    val translationRules: TranslationRules = TranslationRules.defaultMatureRules(),
    val stage: PipelineStage = PipelineStage.IDLE,
    val statusHeadline: String = PipelineStage.IDLE.labelVi,
    val statusSubtext: String = "Chọn file .iso qua SAF hoặc tạo ROM PSP mẫu để kiểm thử luồng dịch AI 2 Tầng.",
    val totalStrings: Int = 0,
    val translatedStrings: Int = 0,
    val patchedStrings: Int = 0,
    val currentBatchIndex: Int = 0,
    val totalBatches: Int = 0,
    val cooldownRemainingSec: Int = 0,
    val customApiKey: String = "",
    val tier1Model: String = "gemini-2.5-flash",
    val tier2Model: String = "gemini-2.5-flash-lite",
    val batchSize: Int = 100,
    val rateLimitDelaySec: Int = 4,
    val useGoogleFallback: Boolean = true,
    val isRunning: Boolean = false,
    val errorMessage: String? = null,
    val consoleLogs: List<String> = listOf(
        "[SYS] Khởi tạo PSP Localization Studio - Kiến trúc AI 2 Tầng.",
        "[SYS] Tầng 1 (Scout & Rules): gemini-2.5-flash / gemini-3.8-flash.",
        "[SYS] Tầng 2 (Bulk 100-150 câu): gemini-2.5-flash-lite / gemini-3.5-flash-lite + Room SQLite Checkpoint."
    )
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalizationViewModel(application: Application) : AndroidViewModel(application) {

    private val database = TranslationDatabase.getInstance(application)
    private val repository = TranslationRepository(database)
    private val isoEngine = Iso9660RandomAccessEngine(application)
    private val twoTierManager = TwoTierTranslationManager(isoEngine, repository)

    private val _uiState = MutableStateFlow(LocalizationUiState())
    val uiState: StateFlow<LocalizationUiState> = _uiState.asStateFlow()

    private val _activeRomIdFlow = MutableStateFlow("")

    val romEntries: StateFlow<List<TranslationEntryEntity>> = _activeRomIdFlow
        .flatMapLatest { romId ->
            if (romId.isBlank()) flowOf(emptyList())
            else repository.observeEntries(romId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedSessions: StateFlow<List<RomSessionEntity>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pipelineJob: Job? = null

    init {
        // Restore most recent session from Room SQLite if available
        viewModelScope.launch {
            val sessions = database.translationDao().observeAllSessions()
            sessions.collect { list ->
                if (_uiState.value.activeRomId.isBlank() && list.isNotEmpty()) {
                    val latest = list.first()
                    restoreSessionFromRoom(latest)
                }
            }
        }
    }

    private suspend fun restoreSessionFromRoom(session: RomSessionEntity) {
        // Verify stored entries are valid human-readable strings (auto-purge any legacy \uFFFD garbage)
        val storedEntries = repository.getAllEntries(session.romId)
        val hasCorruptedGarbage = storedEntries.any { entry ->
            entry.originalText.indexOf('\uFFFD') >= 0 ||
                ! com.example.domain.iso.SmartTextHeuristicFilter.isValidGameSentence(entry.originalText)
        }
        if (hasCorruptedGarbage) {
            repository.resetSession(session.romId)
            appendLog("[DECOMPILER GUARD] Đã tự động dọn sạch dữ liệu nhị phân lỗi (ký tự \\uFFFD) từ phiên cũ.")
            return
        }

        _activeRomIdFlow.value = session.romId
        val rules = TranslationRules.fromJsonString(session.translationRulesJson)
        val parsedUri = runCatching { Uri.parse(session.isoUriString) }.getOrNull()
        val scanSummary = parsedUri?.let { uri ->
            runCatching { isoEngine.scanIsoDirectoryTree(uri) }.getOrNull()
        }
        val scout = scanSummary?.let { twoTierManager.heuristicFallbackScout(it.entries) }

        val stage = when {
            session.patchedStrings > 0 && session.patchedStrings >= session.totalStrings -> PipelineStage.COMPLETED
            session.translatedStrings > 0 && session.translatedStrings < session.totalStrings -> PipelineStage.PAUSED_QUOTA
            else -> PipelineStage.IDLE
        }

        _uiState.update { state ->
            state.copy(
                activeRomId = session.romId,
                romTitle = session.romTitle,
                isoFileName = session.isoFileName,
                isoUri = parsedUri,
                isoSizeBytes = session.isoSizeBytes,
                isoEntries = scanSummary?.entries ?: state.isoEntries,
                scoutResult = scout ?: state.scoutResult,
                translationRules = rules,
                stage = stage,
                statusHeadline = if (session.translatedStrings in 1 until session.totalStrings) {
                    "Đã khôi phục Checkpoint từ Room (${session.translatedStrings}/${session.totalStrings} câu)"
                } else {
                    stage.labelVi
                },
                statusSubtext = "File AI chọn: ${session.targetFilePath} • Bấm 'Tiếp tục dịch' để chạy từ câu #${session.translatedStrings + 1}.",
                totalStrings = session.totalStrings,
                translatedStrings = session.translatedStrings,
                patchedStrings = session.patchedStrings,
                tier1Model = session.tier1Model,
                tier2Model = session.tier2Model
            )
        }
        appendLog("Khôi phục phiên ROM '${session.romTitle}' từ Room DB: Đã dịch ${session.translatedStrings}/${session.totalStrings} câu.")
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(customApiKey = key) }
    }

    fun updateTier1Model(model: String) {
        _uiState.update { it.copy(tier1Model = model) }
    }

    fun updateTier2Model(model: String) {
        _uiState.update { it.copy(tier2Model = model) }
    }

    fun updateBatchSize(size: Int) {
        _uiState.update { it.copy(batchSize = size.coerceIn(50, 150)) }
    }

    fun updateRateLimitDelay(seconds: Int) {
        _uiState.update { it.copy(rateLimitDelaySec = seconds.coerceIn(1, 15)) }
    }

    fun updateGoogleFallback(enabled: Boolean) {
        _uiState.update { it.copy(useGoogleFallback = enabled) }
    }

    /**
     * Generates a valid ISO-9660 PSP ROM image (`CRIMSON_BLADE_ULUS10588.iso`) with 220 strings
     * in `PSP_GAME/USRDIR/script_en.bin` so the user can test the full 2-Tier pipeline immediately.
     */
    fun generateAndLoadSamplePspIso() {
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isRunning = true,
                        errorMessage = null,
                        stage = PipelineStage.SCANNING_ISO,
                        statusHeadline = "Đang khởi tạo ROM PSP mẫu (.iso) chuẩn ISO-9660...",
                        statusSubtext = "Tạo cấu trúc PSP_GAME/USRDIR/script_en.bin gồm 220 câu thoại kèm mã %s, <color>, SYS_BTN..."
                    )
                }
                val isoFile = File(getApplication<Application>().filesDir, "CRIMSON_BLADE_ULUS10588.iso")
                isoEngine.generateSamplePspIso(isoFile)
                appendLog("Đã tạo file ISO mẫu: ${isoFile.name} (${isoFile.length() / 1024} KB).")
                loadIsoFromUri(Uri.fromFile(isoFile), displayFileName = isoFile.name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate sample ISO", e)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        stage = PipelineStage.ERROR,
                        errorMessage = e.localizedMessage ?: "Lỗi tạo file ISO mẫu."
                    )
                }
            }
        }
    }

    fun onSafIsoUriSelected(uri: Uri) {
        if (_uiState.value.isRunning) return
        viewModelScope.launch {
            try {
                // Take persistable URI permission when possible
                val contentResolver = getApplication<Application>().contentResolver
                try {
                    val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: Exception) {
                    // Ignore if provider doesn't support persistable permissions
                }
                val fileName = resolveFileName(uri)
                loadIsoFromUri(uri, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading SAF ISO", e)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        stage = PipelineStage.ERROR,
                        errorMessage = e.localizedMessage ?: "Không thể đọc file .iso qua SAF."
                    )
                }
            }
        }
    }

    private suspend fun loadIsoFromUri(uri: Uri, displayFileName: String) {
        _uiState.update {
            it.copy(
                isRunning = true,
                errorMessage = null,
                stage = PipelineStage.SCANNING_ISO,
                statusHeadline = "Đang chạy bộ Dịch Mã Ngược (MIPS Allegrex Decompiler)...",
                statusSubtext = "Phân tích lệnh hợp ngữ MIPS LUI/ADDIU, bảng TOC/strg và Shannon Entropy trên từng file..."
            )
        }
        val summary = isoEngine.scanIsoDirectoryTree(uri)
        val romId = "${summary.volumeId}_${summary.isoSizeBytes}".replace(Regex("[^A-Za-z0-9_-]"), "_")
        _activeRomIdFlow.value = romId

        appendLog("Đọc thành công ISO '${summary.volumeId}' (${summary.isoSizeBytes / 1024} KB) - Đã dịch mã ngược ${summary.entries.size} mục (${summary.usrDirArchiveCount} file có chuỗi hội thoại hợp lệ).")

        // Check if Room DB already has a valid checkpoint for this ROM
        val existingSession = repository.getSession(romId)
        if (existingSession != null && existingSession.totalStrings > 0) {
            val storedEntries = repository.getAllEntries(romId)
            val hasCorrupted = storedEntries.any {
                it.originalText.indexOf('\uFFFD') >= 0 ||
                    !com.example.domain.iso.SmartTextHeuristicFilter.isValidGameSentence(it.originalText)
            }
            if (hasCorrupted) {
                repository.resetSession(romId)
                appendLog("[DECOMPILER GUARD] Đã xóa bộ nhớ đệm cũ chứa chuỗi rác và quét lại bằng bộ Dịch Mã Ngược.")
            } else {
                val rules = TranslationRules.fromJsonString(existingSession.translationRulesJson)
                val scout = twoTierManager.heuristicFallbackScout(summary.entries)
                _uiState.update {
                    it.copy(
                        activeRomId = romId,
                        romTitle = summary.volumeId,
                        isoFileName = displayFileName,
                        isoUri = uri,
                        isoSizeBytes = summary.isoSizeBytes,
                        isoEntries = summary.entries,
                        scoutResult = scout,
                        translationRules = rules,
                        totalStrings = existingSession.totalStrings,
                        translatedStrings = existingSession.translatedStrings,
                        patchedStrings = existingSession.patchedStrings,
                        stage = if (existingSession.translatedStrings < existingSession.totalStrings) PipelineStage.PAUSED_QUOTA else PipelineStage.COMPLETED,
                        statusHeadline = "Đã nạp ROM & khôi phục Checkpoint (${existingSession.translatedStrings}/${existingSession.totalStrings} câu)",
                        statusSubtext = "File AI chọn: ${existingSession.targetFilePath} • Sẵn sàng dịch tiếp hoặc đóng gói ISO.",
                        isRunning = false
                    )
                }
                appendLog("Phát hiện dữ liệu Checkpoint sạch trong Room SQLite: ${existingSession.translatedStrings}/${existingSession.totalStrings} câu đã dịch.")
                return
            }
        }

        // Fresh ROM scan: preview Decompiler report before AI Tier 1 runs
        val initialScout = twoTierManager.heuristicFallbackScout(summary.entries)
        _uiState.update {
            it.copy(
                activeRomId = romId,
                romTitle = summary.volumeId,
                isoFileName = displayFileName,
                isoUri = uri,
                isoSizeBytes = summary.isoSizeBytes,
                isoEntries = summary.entries,
                scoutResult = initialScout,
                totalStrings = 0,
                translatedStrings = 0,
                patchedStrings = 0,
                stage = PipelineStage.IDLE,
                statusHeadline = "Đã Dịch Mã Ngược ${summary.entries.size} mục trong ISO",
                statusSubtext = "Bấm 'Chạy Việt Hóa AI 2 Tầng' để AI Tầng 1 tự động chọn các file từ báo cáo dịch mã ngược.",
                isRunning = false
            )
        }
    }

    /**
     * Executes the full Two-Tier AI Localization Pipeline (or resumes from the exact Room checkpoint).
     */
    fun startOrResumeTwoTierPipeline() {
        if (_uiState.value.isRunning) return
        val currentUri = _uiState.value.isoUri
        if (currentUri == null) {
            appendLog("Chưa có file .iso! Đang tự động khởi tạo ROM PSP mẫu để chạy...")
            pipelineJob = viewModelScope.launch {
                val isoFile = File(getApplication<Application>().filesDir, "CRIMSON_BLADE_ULUS10588.iso")
                if (!isoFile.exists()) {
                    isoEngine.generateSamplePspIso(isoFile)
                }
                loadIsoFromUri(Uri.fromFile(isoFile), isoFile.name)
                executePipelineInternal()
            }
            return
        }

        pipelineJob = viewModelScope.launch {
            executePipelineInternal()
        }
    }

    private suspend fun executePipelineInternal() {
        val state = _uiState.value
        val uri = state.isoUri ?: return
        val romId = state.activeRomId.ifBlank { "PSP_ROM_DEFAULT" }

        try {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    errorMessage = null,
                    cooldownRemainingSec = 0
                )
            }

            var currentRules = state.translationRules
            val existingCount = repository.getAllEntries(romId).size

            if (existingCount == 0) {
                // =========================================================================
                // TIER 1 - TASK 1: Send Reverse Engineering Decompiler Report to AI Tier 1
                // so the AI chooses the exact files (`selectedFilePaths`)!
                // =========================================================================
                _uiState.update {
                    it.copy(
                        stage = PipelineStage.SCANNING_ISO,
                        statusHeadline = "AI Tầng 1 (${it.tier1Model}) đang chọn file từ Báo cáo Dịch Mã Ngược...",
                        statusSubtext = "Gửi kết quả dịch mã ngược MIPS Allegrex + TOC/strg cho AI Tầng 1 quyết định file cần dịch..."
                    )
                }
                appendLog("[TẦNG 1 - TASK 1] Gửi báo cáo Dịch Mã Ngược của ${state.isoEntries.size} file trong ISO cho AI (${state.tier1Model}) tự chọn file...")

                val scoutResult = twoTierManager.scoutLanguageFilesWithTier1(
                    volumeId = state.romTitle,
                    isoEntries = state.isoEntries,
                    tier1ModelName = state.tier1Model,
                    customApiKey = state.customApiKey
                )
                appendLog("[AI CHỌN FILE] AI Tầng 1 đã chọn ${scoutResult.selectedFilePaths.size} file (Chính: ${scoutResult.primaryTargetFile}) • ${scoutResult.analysisSummary}")

                val targetEntry = state.isoEntries.firstOrNull {
                    it.path.equals(scoutResult.primaryTargetFile, ignoreCase = true) && !it.isKirkEncrypted
                } ?: state.isoEntries.firstOrNull { !it.isDirectory && !it.isKirkEncrypted && it.decompiledSampleStrings.isNotEmpty() }
                ?: state.isoEntries.firstOrNull { !it.isDirectory && !it.isKirkEncrypted && it.sizeBytes > 64 }
                ?: throw IllegalStateException("Không tìm thấy file hợp lệ sau khi chạy bộ Dịch Mã Ngược.")

                // Decompile and extract clean strings across the exact files selected by AI Tier 1
                val extractedRecords = isoEngine.extractStringsAcrossUsrDirArchives(
                    uri = uri,
                    allIsoEntries = state.isoEntries,
                    primaryRecommendedPath = targetEntry.path,
                    aiSelectedFilePaths = scoutResult.selectedFilePaths,
                    maxTotalStrings = 600
                )
                if (extractedRecords.isEmpty()) {
                    throw IllegalStateException("Không tìm thấy chuỗi hội thoại văn bản hợp lệ nào (các file trong ROM có thể đã bị mã hóa phần cứng Kirk ~PSP).")
                }
                val distinctArchives = extractedRecords.map { it.sourceFilePath }.distinct()
                appendLog("[DECOMPILER] Đã dịch mã ngược & trích xuất ${extractedRecords.size} câu thoại sạch (0% ký tự lỗi) từ ${distinctArchives.size} file do AI chỉ định.")

                // =========================================================================
                // TIER 1 - TASK 2: Extract 30-50 Sample Strings -> Generate translation_rules.json
                // =========================================================================
                val sampleStrings = extractedRecords.take(40).map { it.rawText }
                _uiState.update {
                    it.copy(
                        scoutResult = scoutResult,
                        stage = PipelineStage.ANALYZING_RULES,
                        statusHeadline = "Đang phân tích mã & lập quy tắc dịch (AI Tầng 1)...",
                        statusSubtext = "Phân tích ${sampleStrings.size} câu mẫu từ ${targetEntry.name} để sinh Regex & Glossary..."
                    )
                }
                appendLog("[TẦNG 1 - TASK 2] Gửi ${sampleStrings.size} chuỗi mẫu đầu tiên cho AI Tầng 1 để sinh translation_rules.json...")

                currentRules = twoTierManager.analyzeSampleStringsAndBuildRules(
                    targetFilePath = targetEntry.path,
                    sampleStrings = sampleStrings,
                    tier1ModelName = state.tier1Model,
                    customApiKey = state.customApiKey
                )
                appendLog("[TẦNG 1 - RULES] Đã tạo Regex bảo vệ mã hệ thống: ${currentRules.systemTagRegex}")
                appendLog("[TẦNG 1 - TONE] Bối cảnh: ${currentRules.gameGenreAndContext}")

                // Mask system tags with Tier 1 Regex and save all initial records to Room SQLite
                val compiledRegex = SystemTagMasker.compileSafeRegex(currentRules.systemTagRegex)
                val roomEntities = extractedRecords.map { rec ->
                    val maskedPayload = SystemTagMasker.maskSystemTags(
                        stringIndex = rec.stringIndex,
                        originalText = rec.rawText,
                        tagRegex = compiledRegex
                    )
                    TranslationEntryEntity(
                        romId = romId,
                        targetFilePath = rec.sourceFilePath,
                        stringIndex = rec.stringIndex,
                        byteOffsetInIso = rec.byteOffsetInIso,
                        allocatedByteLength = rec.allocatedByteLength,
                        originalText = rec.rawText,
                        maskedText = maskedPayload.maskedText,
                        placeholderMapJson = maskedPayload.placeholderMapToJson(),
                        translatedText = null,
                        status = TranslationEntryEntity.STATUS_PENDING
                    )
                }

                val newSession = RomSessionEntity(
                    romId = romId,
                    romTitle = state.romTitle,
                    isoFileName = state.isoFileName,
                    isoUriString = uri.toString(),
                    isoSizeBytes = state.isoSizeBytes,
                    targetFilePath = targetEntry.path,
                    targetFileLba = targetEntry.lbaSector,
                    targetFileSize = targetEntry.sizeBytes,
                    translationRulesJson = currentRules.toCompactJsonString(),
                    totalStrings = roomEntities.size,
                    translatedStrings = 0,
                    patchedStrings = 0,
                    currentStage = PipelineStage.TRANSLATING_BULK.name,
                    tier1Model = state.tier1Model,
                    tier2Model = state.tier2Model
                )
                repository.saveInitialExtractedEntries(newSession, roomEntities)

                _uiState.update {
                    it.copy(
                        translationRules = currentRules,
                        totalStrings = roomEntities.size,
                        translatedStrings = 0
                    )
                }
            } else {
                appendLog("[ROOM CHECKPOINT] Tiếp tục phiên dịch từ câu #${_uiState.value.translatedStrings + 1}/${_uiState.value.totalStrings} (Bỏ qua Tầng 1 vì đã lưu translation_rules.json).")
            }

            // =========================================================================
            // TIER 2: Bulk Translator (Batches of 100-150 strings + 4s Rate-Limit Guard + Room Checkpoint)
            // =========================================================================
            _uiState.update {
                it.copy(
                    stage = PipelineStage.TRANSLATING_BULK,
                    statusHeadline = "Đang dịch câu ${it.translatedStrings}/${it.totalStrings} (AI Tầng 2: ${it.tier2Model})",
                    statusSubtext = "Chia batch ${it.batchSize} câu • Ẩn mã hệ thống thành {0}, {1} • Nghỉ ${it.rateLimitDelaySec}s chống lỗi 429..."
                )
            }

            val finalTranslatedCount = twoTierManager.executeTier2BulkTranslation(
                romId = romId,
                rules = currentRules,
                tier2ModelName = _uiState.value.tier2Model,
                customApiKey = _uiState.value.customApiKey,
                batchSize = _uiState.value.batchSize,
                rateLimitDelayMs = _uiState.value.rateLimitDelaySec * 1_000L,
                useGoogleTranslateFallback = _uiState.value.useGoogleFallback,
                onBatchProgress = { translatedSoFar, total, batchIdx, totalBatches, logLine, cooldownSec ->
                    _uiState.update { s ->
                        s.copy(
                            translatedStrings = translatedSoFar,
                            totalStrings = total,
                            currentBatchIndex = batchIdx,
                            totalBatches = totalBatches,
                            cooldownRemainingSec = cooldownSec,
                            statusHeadline = "Đang dịch câu $translatedSoFar/$total (Lô $batchIdx/$totalBatches)",
                            statusSubtext = logLine
                        )
                    }
                    if (cooldownSec == 0) {
                        appendLog("[TẦNG 2] $logLine")
                    }
                }
            )

            // =========================================================================
            // STEP 4: Repackage / Rebuild Pointer Table & Patch Directly Into .ISO
            // =========================================================================
            _uiState.update {
                it.copy(
                    stage = PipelineStage.REPACKING_ISO,
                    cooldownRemainingSec = 0,
                    statusHeadline = "Đang tính lại Pointer Table & Repack vào ISO...",
                    statusSubtext = "Cập nhật bảng địa chỉ Offset/Size (TOC) & ghi $finalTranslatedCount chuỗi UTF-8 vào Sector/LBA..."
                )
            }
            appendLog("[ISO REPACKER] Bắt đầu tính toán lại Pointer Table (Offset/Size) & ghi $finalTranslatedCount câu vào file .iso...")

            val allTranslatedEntries = repository.getAllEntries(romId)
            val patchedCount = isoEngine.patchTranslatedEntriesIntoIso(
                uri = uri,
                entries = allTranslatedEntries,
                isoEntries = _uiState.value.isoEntries,
                onProgress = { done, total ->
                    _uiState.update { s ->
                        s.copy(
                            patchedStrings = done,
                            statusHeadline = "Đang cập nhật Pointer & đóng gói ISO ($done/$total chuỗi)..."
                        )
                    }
                }
            )

            repository.markRomPatched(romId, PipelineStage.COMPLETED.name)
            appendLog("[HOÀN TẤT] Đã cập nhật Pointer Table & đóng gói $patchedCount câu tiếng Việt vào ROM '${_uiState.value.isoFileName}'.")

            _uiState.update {
                it.copy(
                    isRunning = false,
                    patchedStrings = patchedCount,
                    stage = PipelineStage.COMPLETED,
                    statusHeadline = "Hoàn tất Việt hóa & Repack Pointer ISO ($patchedCount/${it.totalStrings} câu)",
                    statusSubtext = "Bảng Pointer Table & LBA Sector đã được đồng bộ 100%, sẵn sàng chạy trên PPSSPP."
                )
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Pipeline paused by user, checkpoint preserved in Room DB.")
            val savedCount = repository.getAllEntries(romId).count {
                it.status == TranslationEntryEntity.STATUS_TRANSLATED || it.status == TranslationEntryEntity.STATUS_PATCHED
            }
            _uiState.update {
                it.copy(
                    isRunning = false,
                    cooldownRemainingSec = 0,
                    translatedStrings = savedCount,
                    stage = PipelineStage.PAUSED_QUOTA,
                    statusHeadline = "Đã tạm dừng • Bảo toàn $savedCount/${it.totalStrings} câu trong Room DB",
                    statusSubtext = "Bấm 'Tiếp tục dịch (Resume)' bất cứ lúc nào để chạy tiếp từ câu #${savedCount + 1}."
                )
            }
            appendLog("[CHECKPOINT] Đã tạm dừng luồng dịch. Tiến độ $savedCount câu đã được lưu an toàn trong SQLite/Room.")
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            val savedCount = repository.getAllEntries(romId).count {
                it.status == TranslationEntryEntity.STATUS_TRANSLATED || it.status == TranslationEntryEntity.STATUS_PATCHED
            }
            _uiState.update {
                it.copy(
                    isRunning = false,
                    cooldownRemainingSec = 0,
                    translatedStrings = savedCount,
                    stage = PipelineStage.ERROR,
                    errorMessage = e.localizedMessage ?: "Lỗi trong quá trình dịch.",
                    statusHeadline = "Gián đoạn tại câu $savedCount/${it.totalStrings} (Đã lưu Checkpoint Room)",
                    statusSubtext = "Dữ liệu đã dịch không bị mất. Bấm 'Tiếp tục dịch' để chạy tiếp."
                )
            }
            appendLog("[LỖI] ${e.localizedMessage}. Đã bảo toàn $savedCount câu trong Room DB.")
        }
    }

    fun pausePipeline() {
        pipelineJob?.cancel()
    }

    fun updateCustomRegexAndRemask(newRegexPattern: String) {
        val romId = _uiState.value.activeRomId
        val updatedRules = _uiState.value.translationRules.copy(
            systemTagRegex = newRegexPattern
        ).let { it.copy(rawJson = it.toCompactJsonString()) }

        _uiState.update { it.copy(translationRules = updatedRules) }

        if (romId.isNotBlank()) {
            viewModelScope.launch {
                val regex = SystemTagMasker.compileSafeRegex(newRegexPattern)
                val entries = repository.getAllEntries(romId)
                val remasked = entries.map { entry ->
                    val payload = SystemTagMasker.maskSystemTags(entry.stringIndex, entry.originalText, regex)
                    Triple(entry.stringIndex, payload.maskedText, payload.placeholderMapToJson())
                }
                repository.updateMaskingBatch(romId, remasked, updatedRules.toCompactJsonString())
                appendLog("[REGEX] Đã cập nhật Regex mới và маскирующее (mask) lại ${entries.size} chuỗi trong Room DB.")
            }
        }
    }

    fun updateSingleStringTranslation(entry: TranslationEntryEntity, newViText: String) {
        viewModelScope.launch {
            repository.updateManualTranslation(entry, newViText)
            val count = repository.getAllEntries(entry.romId).count {
                it.status == TranslationEntryEntity.STATUS_TRANSLATED || it.status == TranslationEntryEntity.STATUS_PATCHED
            }
            _uiState.update { it.copy(translatedStrings = count) }
            appendLog("[EDIT] Đã cập nhật bản dịch thủ công cho câu #${entry.stringIndex + 1} vào Room DB.")
        }
    }

    fun resetCurrentRomCheckpoint() {
        if (_uiState.value.isRunning) return
        val romId = _uiState.value.activeRomId
        if (romId.isBlank()) return
        viewModelScope.launch {
            repository.resetSession(romId)
            _uiState.update {
                it.copy(
                    translatedStrings = 0,
                    patchedStrings = 0,
                    totalStrings = 0,
                    stage = PipelineStage.IDLE,
                    statusHeadline = "Đã xóa Checkpoint Room DB cho ROM hiện tại",
                    statusSubtext = "Bấm 'Chạy Việt Hóa AI 2 Tầng' để phân tích và dịch lại từ đầu."
                )
            }
            appendLog("[RESET] Đã xóa bộ nhớ đệm Room DB của ROM '$romId'.")
        }
    }

    private fun resolveFileName(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment ?: "psp_rom.iso"
        }
        val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
        cursor?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && c.moveToFirst()) {
                return c.getString(nameIdx)
            }
        }
        return uri.lastPathSegment ?: "selected_rom.iso"
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _uiState.update { state ->
            val updated = (listOf("[$time] $message") + state.consoleLogs).take(80)
            state.copy(consoleLogs = updated)
        }
    }
}

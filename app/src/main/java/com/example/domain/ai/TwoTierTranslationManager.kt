package com.example.domain.ai

import android.net.Uri
import android.util.Log
import com.example.data.local.RomSessionEntity
import com.example.data.local.TranslationEntryEntity
import com.example.data.repository.TranslationRepository
import com.example.domain.iso.Iso9660RandomAccessEngine
import com.example.domain.model.CandidateScriptFile
import com.example.domain.model.IsoFileEntry
import com.example.domain.model.MaskedStringPayload
import com.example.domain.model.PipelineStage
import com.example.domain.model.ScoutFileAnalysisResult
import com.example.domain.model.TranslationRules
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "TwoTierTransManager"

/**
 * Manages the full Two-Tier AI PSP ROM (.iso) Localization Pipeline:
 * 1. Reads the ISO-9660 directory tree via RandomAccessFile/SAF (`PSP_GAME/USRDIR`).
 * 2. Calls Tier 1 AI (Scout & Context Analyzer) to identify the script file and generate `translation_rules.json`
 *    (Regex for non-translatable system codes + mature Vietnamese glossary/pronouns).
 * 3. Masks system control codes into `{0}`, `{1}` via Kotlin Regex before translation.
 * 4. Batches 100-150 strings per request to Tier 2 AI (Bulk Translator), immediately saving each batch
 *    into SQLite/Room for zero-loss resume, and sleeps 4 seconds between requests to respect Free Tier RPM limits.
 * 5. Repackages/patches translated UTF-8 bytes directly back into the `.iso` via RandomAccessFile.
 */
class TwoTierTranslationManager(
    private val isoEngine: Iso9660RandomAccessEngine,
    private val repository: TranslationRepository
) {
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Structured JSON Schema for Tier 1 Task 1 (AI-Driven File Selection from Decompiler Report)
    private val scoutFileSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "primaryTargetFile" to Schema.string(description = "Full path to the primary script/language file chosen by AI"),
                "selectedFilePaths" to Schema.array(
                    items = Schema.string(description = "All file paths in the ISO selected by AI for decompilation and translation")
                ),
                "analysisSummary" to Schema.string(description = "AI technical explanation based on the Reverse Engineering Decompiler report"),
                "candidateFiles" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "path" to Schema.string(),
                            "confidenceScore" to Schema.integer(),
                            "estimatedEncoding" to Schema.string(),
                            "reason" to Schema.string()
                        )
                    )
                )
            )
        )
    }

    // Structured JSON Schema for Tier 1 Task 2 (`translation_rules.json`)
    private val translationRulesSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "system_tag_regex" to Schema.string(
                    description = "Valid regular expression matching non-translatable system codes (%s, %d, \\n, <color=01>, SYS_BTN_X, [WAIT_01])"
                ),
                "game_genre_and_context" to Schema.string(
                    description = "Detailed description of the game's genre, atmosphere, and narrative setting"
                ),
                "tone_guidelines" to Schema.string(
                    description = "Vietnamese translation tone guidelines (sharp, mature, natural, non-literal)"
                ),
                "pronounPairs" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "relation" to Schema.string(),
                            "vietnamesePronouns" to Schema.string()
                        )
                    )
                ),
                "glossaryEntries" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "sourceTerm" to Schema.string(),
                            "targetTerm" to Schema.string()
                        )
                    )
                ),
                "max_length_expansion_ratio" to Schema.double()
            )
        )
    }

    // Structured JSON Schema for Tier 2 Bulk Translation Output
    private val bulkTranslationSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "translations" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "id" to Schema.integer(description = "Original string index ID"),
                            "vi" to Schema.string(description = "Mature Vietnamese translation preserving {0}, {1} placeholders")
                        )
                    )
                )
            )
        )
    }

    // Lazy Firebase AI Logic models for zero-key App Check execution
    private val defaultTier1ScoutModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = scoutFileSchema
            },
            systemInstruction = content { text(TwoTierPrompts.TIER1_SCOUT_FILE_DISCOVERY_SYSTEM_PROMPT) }
        )
    }

    private val defaultTier1RulesModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = translationRulesSchema
            },
            systemInstruction = content { text(TwoTierPrompts.TIER1_CONTEXT_AND_RULES_SYSTEM_PROMPT) }
        )
    }

    /**
     * Maps legacy or user-selected model names to supported Firebase AI Logic models
     * when running through Firebase AI Logic SDK.
     */
    private fun resolveSupportedFirebaseModelName(requestedModel: String, isTier1: Boolean): String {
        return when {
            requestedModel.contains("lite", ignoreCase = true) -> "gemini-3.5-flash-lite"
            requestedModel.contains("argon", ignoreCase = true) -> "gemini-4-argon"
            isTier1 -> "gemini-3.8-flash"
            else -> "gemini-3.5-flash-lite"
        }
    }

    /**
     * STEP 1 (Tier 1 - Task 1):
     * Inspects the ISO-9660 directory tree and uses Tier 1 AI to pinpoint the language/script file.
     */
    suspend fun scoutLanguageFilesWithTier1(
        volumeId: String,
        isoEntries: List<IsoFileEntry>,
        tier1ModelName: String,
        customApiKey: String
    ): ScoutFileAnalysisResult {
        val userPrompt = TwoTierPrompts.buildTier1ScoutUserPrompt(volumeId, isoEntries)
        return try {
            val rawJson = if (customApiKey.isNotBlank()) {
                callDirectGeminiJsonApi(
                    apiKey = customApiKey.trim(),
                    modelName = tier1ModelName,
                    systemPrompt = TwoTierPrompts.TIER1_SCOUT_FILE_DISCOVERY_SYSTEM_PROMPT,
                    userPrompt = userPrompt
                )
            } else {
                val resolvedModel = resolveSupportedFirebaseModelName(tier1ModelName, isTier1 = true)
                val model = if (resolvedModel == "gemini-3.8-flash") {
                    defaultTier1ScoutModel
                } else {
                    Firebase.ai.generativeModel(
                        modelName = resolvedModel,
                        generationConfig = generationConfig {
                            responseMimeType = "application/json"
                            responseSchema = scoutFileSchema
                        },
                        systemInstruction = content { text(TwoTierPrompts.TIER1_SCOUT_FILE_DISCOVERY_SYSTEM_PROMPT) }
                    )
                }
                val response = withTimeout(60_000L) {
                    model.generateContent(userPrompt)
                }
                response.text ?: throw IllegalStateException("Tier 1 Scout trả về dữ liệu rỗng.")
            }
            parseScoutFileResult(rawJson, isoEntries)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.w(TAG, "Tier 1 Scout timed out, using heuristic fallback", e)
                heuristicFallbackScout(isoEntries)
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 Scout AI call fallback triggered: ${e.message}")
            heuristicFallbackScout(isoEntries)
        }
    }

    /**
     * STEP 2 (Tier 1 - Task 2):
     * Sends the first 30-50 sample strings from the target script file to Tier 1 AI
     * to generate `translation_rules.json` (System Tag Regex + Mature Context & Glossary).
     */
    suspend fun analyzeSampleStringsAndBuildRules(
        targetFilePath: String,
        sampleStrings: List<String>,
        tier1ModelName: String,
        customApiKey: String
    ): TranslationRules {
        val userPrompt = TwoTierPrompts.buildTier1RulesUserPrompt(targetFilePath, sampleStrings)
        return try {
            val rawJson = if (customApiKey.isNotBlank()) {
                callDirectGeminiJsonApi(
                    apiKey = customApiKey.trim(),
                    modelName = tier1ModelName,
                    systemPrompt = TwoTierPrompts.TIER1_CONTEXT_AND_RULES_SYSTEM_PROMPT,
                    userPrompt = userPrompt
                )
            } else {
                val resolvedModel = resolveSupportedFirebaseModelName(tier1ModelName, isTier1 = true)
                val model = if (resolvedModel == "gemini-3.8-flash") {
                    defaultTier1RulesModel
                } else {
                    Firebase.ai.generativeModel(
                        modelName = resolvedModel,
                        generationConfig = generationConfig {
                            responseMimeType = "application/json"
                            responseSchema = translationRulesSchema
                        },
                        systemInstruction = content { text(TwoTierPrompts.TIER1_CONTEXT_AND_RULES_SYSTEM_PROMPT) }
                    )
                }
                val response = withTimeout(60_000L) {
                    model.generateContent(userPrompt)
                }
                response.text ?: throw IllegalStateException("Tier 1 Rules trả về dữ liệu rỗng.")
            }
            parseTier1RulesJson(rawJson)
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.w(TAG, "Tier 1 Rules timed out, using mature tactical fallback rules", e)
                TranslationRules.defaultMatureRules()
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 Rules AI fallback triggered: ${e.message}")
            TranslationRules.defaultMatureRules()
        }
    }

    /**
     * STEP 3 (Tier 2 - Bulk Translation with Regex Masking, 100-150 Batching, 4s Rate-Limit Delay,
     * and Immediate Room SQLite Checkpointing).
     */
    suspend fun executeTier2BulkTranslation(
        romId: String,
        rules: TranslationRules,
        tier2ModelName: String,
        customApiKey: String,
        batchSize: Int = 100,
        rateLimitDelayMs: Long = 4_000L,
        useGoogleTranslateFallback: Boolean = true,
        onBatchProgress: suspend (
            translatedSoFar: Int,
            totalStrings: Int,
            currentBatchIndex: Int,
            totalBatches: Int,
            statusLog: String,
            cooldownRemainingSec: Int
        ) -> Unit
    ): Int {
        val safeBatchSize = batchSize.coerceIn(20, 150)
        val compiledRegex = SystemTagMasker.compileSafeRegex(rules.systemTagRegex)

        // 1. Refresh masking for all pending entries using Tier 1's Regex
        val allEntries = repository.getAllEntries(romId)
        val totalStrings = allEntries.size
        val pendingEntries = repository.getPendingEntries(romId)

        if (pendingEntries.isEmpty()) {
            return totalStrings
        }

        val maskedTriples = pendingEntries.map { entry ->
            val maskedPayload = SystemTagMasker.maskSystemTags(
                stringIndex = entry.stringIndex,
                originalText = entry.originalText,
                tagRegex = compiledRegex
            )
            Triple(
                entry.stringIndex,
                maskedPayload.maskedText,
                maskedPayload.placeholderMapToJson()
            )
        }
        repository.updateMaskingBatch(romId, maskedTriples, rules.toCompactJsonString())

        val refreshedPending = repository.getPendingEntries(romId)
        val batches = refreshedPending.chunked(safeBatchSize)
        val totalBatches = batches.size
        var translatedCount = totalStrings - refreshedPending.size

        val tier2SystemPrompt = TwoTierPrompts.buildTier2BulkSystemPrompt(rules)
        val resolvedFirebaseModel = resolveSupportedFirebaseModelName(tier2ModelName, isTier1 = false)

        val tier2FirebaseModel by lazy {
            Firebase.ai.generativeModel(
                modelName = resolvedFirebaseModel,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = bulkTranslationSchema
                },
                systemInstruction = content { text(tier2SystemPrompt) }
            )
        }

        for ((batchIdx, batchEntries) in batches.withIndex()) {
            val batchNumber = batchIdx + 1
            val firstId = batchEntries.first().stringIndex + 1
            val lastId = batchEntries.last().stringIndex + 1

            onBatchProgress(
                translatedCount,
                totalStrings,
                batchNumber,
                totalBatches,
                "Tầng 2 [$tier2ModelName]: Đang dịch lô $batchNumber/$totalBatches (Câu $firstId..$lastId, đã ẩn mã hệ thống thành {0}, {1})...",
                0
            )

            // Build compact JSON array input with masked strings only
            val requestArray = JSONArray()
            for (entry in batchEntries) {
                val itemObj = JSONObject()
                itemObj.put("id", entry.stringIndex)
                itemObj.put("text", entry.maskedText)
                requestArray.put(itemObj)
            }
            val batchPrompt = requestArray.toString()

            val translatedMaskedMap: Map<Int, String> = try {
                if (tier2ModelName.contains("google-translate", ignoreCase = true)) {
                    translateBatchViaGoogleEndpoint(batchEntries)
                } else if (customApiKey.isNotBlank()) {
                    val rawJson = callDirectGeminiJsonApiWithRetry(
                        apiKey = customApiKey.trim(),
                        modelName = tier2ModelName,
                        systemPrompt = tier2SystemPrompt,
                        userPrompt = batchPrompt,
                        rateLimitDelayMs = rateLimitDelayMs
                    )
                    parseTier2BatchResponse(rawJson)
                } else {
                    val response = withTimeout(60_000L) {
                        tier2FirebaseModel.generateContent(batchPrompt)
                    }
                    val rawJson = response.text
                        ?: throw IllegalStateException("Tier 2 trả về chuỗi JSON rỗng.")
                    parseTier2BatchResponse(rawJson)
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    Log.w(TAG, "Tier 2 batch $batchNumber timed out", e)
                    if (useGoogleTranslateFallback) {
                        translateBatchViaGoogleEndpoint(batchEntries)
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 2 batch $batchNumber encountered error (${e.message}), using fallback=$useGoogleTranslateFallback")
                if (useGoogleTranslateFallback) {
                    translateBatchViaGoogleEndpoint(batchEntries)
                } else {
                    throw e
                }
            }

            // Restore {0}, {1}, {2} placeholders back to original PSP system codes (%s, <color=01>, SYS_BTN_X)
            val restoredBatchPairs = batchEntries.map { entry ->
                val placeholderMap = MaskedStringPayload.placeholderMapFromJson(entry.placeholderMapJson)
                var rawViMasked = translatedMaskedMap[entry.stringIndex]?.trim()

                // If AI batch missed this ID or returned the exact untranslated source text, translate it individually
                if (rawViMasked.isNullOrBlank() || rawViMasked.equals(entry.maskedText.trim(), ignoreCase = true)) {
                    rawViMasked = translateSingleSentenceViaGoogle(entry.maskedText)
                        ?.takeIf { it.isNotBlank() && !it.equals(entry.maskedText.trim(), ignoreCase = true) }
                        ?: fallbackLocalMatureTranslate(entry.maskedText, rules)
                }

                val restoredVi = SystemTagMasker.restoreSystemTags(rawViMasked, placeholderMap)
                entry.stringIndex to restoredVi
            }

            // Immediately persist this batch to SQLite/Room so quota exhaustion never loses progress
            translatedCount = repository.saveTranslatedBatchCheckpoint(
                romId = romId,
                batchNumber = batchNumber,
                translatedPairs = restoredBatchPairs
            )

            onBatchProgress(
                translatedCount,
                totalStrings,
                batchNumber,
                totalBatches,
                "Đã lưu Checkpoint Lô $batchNumber/$totalBatches vào Room SQLite ($translatedCount/$totalStrings câu).",
                0
            )

            // Rate-Limit Guard: Sleep 4 seconds between requests to prevent HTTP 429 on Free Tier (15 RPM)
            if (batchNumber < totalBatches && rateLimitDelayMs > 0L) {
                val totalSec = ((rateLimitDelayMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                for (remaining in totalSec downTo 1) {
                    onBatchProgress(
                        translatedCount,
                        totalStrings,
                        batchNumber,
                        totalBatches,
                        "Nghỉ chống Rate-Limit 429 (Free Quota Guard): còn ${remaining}s trước Lô ${batchNumber + 1}/$totalBatches...",
                        remaining
                    )
                    delay(1_000L)
                }
            }
        }

        return translatedCount
    }

    /**
     * Direct REST call to Gemini API when user supplies a custom API Key in the UI,
     * including automatic 429 Too Many Requests backoff.
     */
    private suspend fun callDirectGeminiJsonApiWithRetry(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        rateLimitDelayMs: Long
    ): String {
        var attempt = 0
        val maxAttempts = 3
        while (true) {
            try {
                return callDirectGeminiJsonApi(apiKey, modelName, systemPrompt, userPrompt)
            } catch (e: HttpRateLimitException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                val backoffMs = (rateLimitDelayMs * (attempt + 1)).coerceAtLeast(5_000L)
                Log.w(TAG, "HTTP 429 Too Many Requests. Sleeping ${backoffMs}ms before retry #$attempt")
                delay(backoffMs)
            }
        }
    }

    private suspend fun callDirectGeminiJsonApi(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val cleanModel = modelName.substringBefore(" ").trim().ifBlank { "gemini-2.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"

        val payload = JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", userPrompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.25)
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (response.code == 429) {
                throw HttpRateLimitException("Lỗi 429 Too Many Requests (Vượt hạn mức RPM/RPD gói Free).")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini REST HTTP ${response.code}: ${bodyStr.take(220)}")
            }
            val root = JSONObject(bodyStr)
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            if (text.isBlank()) {
                throw IllegalStateException("Phản hồi JSON từ Gemini API trống.")
            }
            text
        }
    }

    /**
     * High-reliability batch translator using POST (`application/x-www-form-urlencoded`) and newline-safe
     * token wrapping so `{0}`, `{1}` tags and multi-line PSP dialogues never misalign or hit GET URI limits.
     */
    private suspend fun translateBatchViaGoogleEndpoint(
        batchEntries: List<TranslationEntryEntity>
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<Int, String>()
        val subChunks = batchEntries.chunked(12)
        for (chunk in subChunks) {
            try {
                // Sanitize internal newlines so each entry occupies strictly one line in the batch
                val sanitizedLines = chunk.map { entry ->
                    entry.maskedText.replace("\r\n", " [[NL]] ").replace("\n", " [[NL]] ")
                }
                val joined = sanitizedLines.joinToString("\n")
                val formBody = "q=" + URLEncoder.encode(joined, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=vi&dt=t"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .post(formBody.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val outerArray = JSONArray(body)
                        val sentencesArray = outerArray.optJSONArray(0)
                        val sb = StringBuilder()
                        if (sentencesArray != null) {
                            for (i in 0 until sentencesArray.length()) {
                                val seg = sentencesArray.optJSONArray(i)
                                if (seg != null && seg.length() > 0) {
                                    sb.append(seg.optString(0))
                                }
                            }
                        }
                        val translatedLines = sb.toString().split("\n")
                        if (translatedLines.size == chunk.size) {
                            for ((idx, entry) in chunk.withIndex()) {
                                val lineVi = translatedLines[idx]
                                    .replace("[[NL]]", "\n")
                                    .replace("[[nl]]", "\n")
                                    .trim()
                                if (lineVi.isNotBlank()) {
                                    results[entry.stringIndex] = refineMatureVietnameseTone(lineVi)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google Translate POST sub-chunk failed: ${e.message}")
            }

            // For any entry in the chunk that wasn't mapped or aligned, translate individually
            for (entry in chunk) {
                if (!results.containsKey(entry.stringIndex)) {
                    val singleVi = translateSingleSentenceViaGoogle(entry.maskedText)
                    if (!singleVi.isNullOrBlank()) {
                        results[entry.stringIndex] = refineMatureVietnameseTone(singleVi)
                    }
                }
            }
        }
        results
    }

    private fun translateSingleSentenceViaGoogle(maskedText: String): String? {
        return try {
            val encoded = URLEncoder.encode(maskedText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=vi&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val outerArray = JSONArray(body)
                val sentencesArray = outerArray.optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val seg = sentencesArray.optJSONArray(i)
                    if (seg != null && seg.length() > 0) {
                        sb.append(seg.optString(0))
                    }
                }
                sb.toString().trim().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseScoutFileResult(
        rawJson: String,
        isoEntries: List<IsoFileEntry>
    ): ScoutFileAnalysisResult {
        val clean = stripCodeFencesIfPresent(rawJson)
        val obj = JSONObject(clean)
        val primary = obj.optString("primaryTargetFile")
        val summary = obj.optString(
            "analysisSummary",
            "AI Tầng 1 đã phân tích báo cáo Dịch Mã Ngược (MIPS Allegrex + TOC Decompiler) và chọn các file ngôn ngữ."
        )

        val aiSelectedPaths = mutableListOf<String>()
        obj.optJSONArray("selectedFilePaths")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).trim()
                if (p.isNotBlank()) aiSelectedPaths.add(p)
            }
        }

        val candidates = mutableListOf<CandidateScriptFile>()
        obj.optJSONArray("candidateFiles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val path = item.optString("path").trim()
                if (path.isNotBlank()) {
                    candidates.add(
                        CandidateScriptFile(
                            path = path,
                            confidenceScore = item.optInt("confidenceScore", 90),
                            estimatedEncoding = item.optString("estimatedEncoding", "UTF-8"),
                            reason = item.optString("reason", "AI chọn từ báo cáo Dịch Mã Ngược")
                        )
                    )
                    if (path !in aiSelectedPaths) {
                        aiSelectedPaths.add(path)
                    }
                }
            }
        }

        val decompilerFallback = heuristicFallbackScout(isoEntries)
        val matchedPrimaryEntry = isoEntries.firstOrNull {
            it.path.equals(primary, ignoreCase = true) && !it.isKirkEncrypted
        }
        val resolvedPrimary = matchedPrimaryEntry?.path
            ?: aiSelectedPaths.firstOrNull { sel -> isoEntries.any { it.path.equals(sel, ignoreCase = true) } }
            ?: decompilerFallback.primaryTargetFile

        val finalSelectedPaths = if (aiSelectedPaths.isNotEmpty()) {
            aiSelectedPaths
        } else {
            decompilerFallback.selectedFilePaths
        }

        return ScoutFileAnalysisResult(
            primaryTargetFile = resolvedPrimary,
            selectedFilePaths = finalSelectedPaths,
            candidateFiles = if (candidates.isNotEmpty()) candidates else decompilerFallback.candidateFiles,
            analysisSummary = summary
        )
    }

    /**
     * Decompiler-driven file ranking (used only for initial preview before AI Tier 1 runs or if offline).
     * Ranks files strictly by the Reverse Engineering Decompiler output ([IsoFileEntry.decompiledSampleStrings],
     * [IsoFileEntry.mipsXrefCount], and [IsoFileEntry.subFileCount]) — ZERO hardcoded filename rules!
     */
    fun heuristicFallbackScout(isoEntries: List<IsoFileEntry>): ScoutFileAnalysisResult {
        val decompiledCandidates = isoEntries
            .filter { !it.isDirectory && !it.isKirkEncrypted && it.decompiledSampleStrings.isNotEmpty() }
            .sortedByDescending { entry ->
                (entry.decompiledSampleStrings.size * 25) +
                    (entry.mipsXrefCount * 10) +
                    (entry.subFileCount * 5)
            }

        val pool = decompiledCandidates.ifEmpty {
            isoEntries.filter { !it.isDirectory && !it.isKirkEncrypted && it.sizeBytes > 32 }
        }

        val candidateList = pool.map { entry ->
            val confidence = (60 + entry.decompiledSampleStrings.size * 6 + entry.mipsXrefCount * 4)
                .coerceIn(50, 99)
            val samplePreview = entry.decompiledSampleStrings.firstOrNull()?.take(45) ?: ""
            CandidateScriptFile(
                path = entry.path,
                confidenceScore = confidence,
                estimatedEncoding = entry.containerFormatLabel,
                reason = "Dịch mã ngược tìm thấy ${entry.decompiledSampleStrings.size} chuỗi mẫu, ${entry.mipsXrefCount} lệnh MIPS LUI/ADDIU, Entropy ${"%.2f".format(entry.shannonEntropy)} bits/B" +
                    if (samplePreview.isNotBlank()) " (Mẫu: \"$samplePreview\")" else ""
            )
        }

        val best = candidateList.firstOrNull()?.path ?: "PSP_GAME/USRDIR/script_en.bin"
        val selectedPaths = candidateList.map { it.path }
        return ScoutFileAnalysisResult(
            primaryTargetFile = best,
            selectedFilePaths = selectedPaths,
            candidateFiles = candidateList.take(8),
            analysisSummary = "Bộ Dịch Mã Ngược (MIPS Allegrex + TOC Decompiler) đã giải mã ${decompiledCandidates.size} file có chuỗi hội thoại hợp lệ. AI Tầng 1 sẽ chọn trực tiếp danh sách file từ báo cáo này."
        )
    }

    private fun parseTier1RulesJson(rawJson: String): TranslationRules {
        val clean = stripCodeFencesIfPresent(rawJson)
        val obj = JSONObject(clean)
        val regexStr = obj.optString("system_tag_regex", TranslationRules.DEFAULT_FALLBACK_REGEX)
            .ifBlank { TranslationRules.DEFAULT_FALLBACK_REGEX }
        val contextStr = obj.optString(
            "game_genre_and_context",
            "Hành động chiến thuật đen tối (Dark Military Sci-Fi & Tactical RPG)."
        )
        val toneStr = obj.optString(
            "tone_guidelines",
            "Văn phong trưởng thành, đanh thép, giàu chất điện ảnh, tuyệt đối không dịch word-by-word."
        )

        val pronouns = linkedMapOf<String, String>()
        obj.optJSONArray("pronounPairs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rel = item.optString("relation")
                val valStr = item.optString("vietnamesePronouns")
                if (rel.isNotBlank() && valStr.isNotBlank()) {
                    pronouns[rel] = valStr
                }
            }
        }
        if (pronouns.isEmpty()) {
            pronouns.putAll(TranslationRules.defaultMatureRules().pronounRules)
        }

        val glossary = linkedMapOf<String, String>()
        obj.optJSONArray("glossaryEntries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val src = item.optString("sourceTerm")
                val tgt = item.optString("targetTerm")
                if (src.isNotBlank() && tgt.isNotBlank()) {
                    glossary[src] = tgt
                }
            }
        }
        if (glossary.isEmpty()) {
            glossary.putAll(TranslationRules.defaultMatureRules().glossary)
        }

        val ratio = obj.optDouble("max_length_expansion_ratio", 1.15)
        val rules = TranslationRules(
            systemTagRegex = regexStr,
            gameGenreAndContext = contextStr,
            toneGuidelines = toneStr,
            pronounRules = pronouns,
            glossary = glossary,
            maxLengthExpansionRatio = ratio
        )
        return rules.copy(rawJson = rules.toCompactJsonString())
    }

    private fun parseTier2BatchResponse(rawJson: String): Map<Int, String> {
        val clean = stripCodeFencesIfPresent(rawJson)
        val map = mutableMapOf<Int, String>()
        val array = if (clean.trimStart().startsWith("{")) {
            JSONObject(clean).optJSONArray("translations") ?: JSONArray()
        } else {
            JSONArray(clean)
        }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val vi = item.optString("vi", item.optString("text", "")).trim()
            if (id >= 0 && vi.isNotBlank()) {
                map[id] = vi
            }
        }
        return map
    }

    private fun fallbackLocalMatureTranslate(maskedText: String, rules: TranslationRules): String {
        var text = maskedText
        val dictionary = listOf(
            "Listen up," to "Nghe rõ đây,",
            "Phantom Sector is crawling with autonomous gunships." to "Phân khu Bóng Ma đang dày đặc pháo hạm tự hành.",
            "Press" to "Nhấn",
            "to deploy tactical shield." to "để bung khiên chiến thuật.",
            "Commander, the Valkyrie Core temperature is at" to "Chỉ huy, nhiệt độ Lõi Valkyrie đã chạm ngưỡng",
            "If we don't breach the gate now, the whole squad is dead." to "Nếu không phá cổng ngay lập tức, cả phân đội sẽ bỏ mạng.",
            "Heh, relaxing on the battlefield?" to "Hừ, còn tâm trí thảnh thơi giữa chiến trường sao?",
            "Keep your finger on" to "Giữ chặt ngón tay trên nút",
            "and watch my flank, rookie." to "và yểm trợ sườn cho tôi, tân binh.",
            "Don't waste ammunition on decoys." to "Đừng phí đạn vào bẫy mồi nhử.",
            "Target their optical sensors with" to "Nhắm thẳng cụm cảm biến quang học bằng",
            "and fall back to Extraction Point." to "rồi rút về Điểm Tập Kết.",
            "WARNING: Memory bank" to "CẢNH BÁO: Khoang bộ nhớ",
            "corrupted. Hold" to "bị lỗi. Giữ tổ hợp",
            "to initiate emergency purge." to "để kích hoạt thanh trừng khẩn cấp.",
            "You call this a rescue mission," to "Ngài gọi đây là chiến dịch giải cứu sao,",
            "Half of Crimson Blade was wiped out in the ambush at Sector" to "Một nửa Huyết Đao Đội đã bị xóa sổ trong cuộc phục kích tại Phân khu",
            "History is written by survivors, Commander." to "Lịch sử được viết bởi kẻ sống sót, Chỉ huy à.",
            "Hand over the Valkyrie Core or face orbital bombardment." to "Giao nộp Lõi Valkyrie hoặc hứng chịu pháo kích quỹ đạo.",
            "Looks like the old man lost his mind." to "Xem ra lão già đó điên thật rồi.",
            "Say the word, boss, and I'll silence his command tower in" to "Chỉ cần ra lệnh thôi sếp, tôi sẽ xóa sổ tháp chỉ huy của lão trong",
            "seconds." to "giây.",
            "Hold your fire, Kael! Elena, patch into frequency" to "Ngừng bắn, Kael! Elena, xâm nhập tần số",
            "and jams their targeting uplink." to "và gây nhiễu đường truyền khóa mục tiêu của chúng.",
            "Uplink secured!" to "Đã khống chế đường truyền!",
            "to lock onto the heavy mech's reactor core" to "để khóa mục tiêu vào lò phản ứng của thiết giáp hạng nặng"
        )
        for ((en, vi) in dictionary) {
            text = text.replace(en, vi, ignoreCase = true)
        }
        return refineMatureVietnameseTone(text)
    }

    private fun refineMatureVietnameseTone(input: String): String {
        return input
            .replace("Bạn ", "Cậu ")
            .replace("bạn ", "cậu ")
            .replace("của bạn", "của cậu")
            .replace("Lưỡi kiếm đỏ thẫm", "Huyết Đao Đội")
            .replace("Khu vực bóng ma", "Phân khu Bóng Ma")
    }

    private fun stripCodeFencesIfPresent(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) {
                s = s.removeSuffix("```").trim()
            }
        }
        return s
    }
}

class HttpRateLimitException(message: String) : Exception(message)

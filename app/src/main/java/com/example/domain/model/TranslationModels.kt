package com.example.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata of a file entry inside the PSP .iso image (ISO-9660 directory tree).
 */
data class IsoFileEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val lbaSector: Long,
    val isDirectory: Boolean = false,
    val headerPreviewHex: String = "",
    val asciiSamplePreview: String = ""
) {
    val byteOffsetInIso: Long
        get() = lbaSector * 2048L
}

/**
 * Output of Tier 1 Task 1: Candidate language/script files discovered by AI Scout.
 */
data class ScoutFileAnalysisResult(
    val primaryTargetFile: String,
    val candidateFiles: List<CandidateScriptFile>,
    val analysisSummary: String
)

data class CandidateScriptFile(
    val path: String,
    val confidenceScore: Int,
    val estimatedEncoding: String,
    val reason: String
)

/**
 * Output of Tier 1 Task 2: `translation_rules.json` generated after inspecting 30-50 sample strings.
 */
data class TranslationRules(
    val systemTagRegex: String,
    val gameGenreAndContext: String,
    val toneGuidelines: String,
    val pronounRules: Map<String, String>,
    val glossary: Map<String, String>,
    val maxLengthExpansionRatio: Double = 1.15,
    val rawJson: String = ""
) {
    fun toCompactJsonString(): String {
        if (rawJson.isNotBlank()) return rawJson
        val root = JSONObject()
        root.put("system_tag_regex", systemTagRegex)
        root.put("game_genre_and_context", gameGenreAndContext)
        root.put("tone_guidelines", toneGuidelines)
        val pronounsObj = JSONObject()
        pronounRules.forEach { (k, v) -> pronounsObj.put(k, v) }
        root.put("pronoun_rules", pronounsObj)
        val glossaryObj = JSONObject()
        glossary.forEach { (k, v) -> glossaryObj.put(k, v) }
        root.put("glossary", glossaryObj)
        root.put("max_length_expansion_ratio", maxLengthExpansionRatio)
        return root.toString(2)
    }

    companion object {
        const val DEFAULT_FALLBACK_REGEX =
            """(%[0-9]*[sdxfc]|\\[nrt]|<[a-zA-Z0-9_=/#]+>|\[[A-Z0-9_:]+\]|SYS_[A-Z0-9_]+|\{[0-9A-Fa-f_]+\}|@[0-9A-Fa-f]{2,4})"""

        fun fromJsonString(jsonStr: String): TranslationRules {
            return try {
                val obj = JSONObject(jsonStr)
                val regex = obj.optString("system_tag_regex", DEFAULT_FALLBACK_REGEX)
                    .ifBlank { DEFAULT_FALLBACK_REGEX }
                val context = obj.optString(
                    "game_genre_and_context",
                    "Hành động quân sự / Nhập vai chiến thuật (Mature Tactical RPG) - bối cảnh u tối, nghiêm túc."
                )
                val tone = obj.optString(
                    "tone_guidelines",
                    "Dịch sắc sảo, trưởng thành, câu văn gãy gọn giàu nhịp điệu điện ảnh. Tuyệt đối không dịch word-by-word ngây ngô."
                )
                val pronouns = mutableMapOf<String, String>()
                obj.optJSONObject("pronoun_rules")?.let { pObj ->
                    val keys = pObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        pronouns[k] = pObj.optString(k)
                    }
                }
                val glossary = mutableMapOf<String, String>()
                obj.optJSONObject("glossary")?.let { gObj ->
                    val keys = gObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        glossary[k] = gObj.optString(k)
                    }
                }
                val ratio = obj.optDouble("max_length_expansion_ratio", 1.15)
                TranslationRules(
                    systemTagRegex = regex,
                    gameGenreAndContext = context,
                    toneGuidelines = tone,
                    pronounRules = pronouns,
                    glossary = glossary,
                    maxLengthExpansionRatio = ratio,
                    rawJson = obj.toString(2)
                )
            } catch (e: Exception) {
                defaultMatureRules()
            }
        }

        fun defaultMatureRules(): TranslationRules {
            val defaultPronouns = linkedMapOf(
                "Commander / Leader -> Subordinate" to "Ta / Chỉ huy - Cậu / Các anh em",
                "Subordinate -> Commander" to "Tôi - Ngài / Chỉ huy",
                "Comrades / Mercenaries" to "Tôi - Ông / Anh / Cô",
                "Hostile / Antagonist" to "Ta / Tao - Ngươi / Mày (tùy mức độ căng thẳng)"
            )
            val defaultGlossary = linkedMapOf(
                "Crimson Blade" to "Huyết Đao Đội (giữ nguyên tên riêng tổ chức khi cần)",
                "Valkyrie Core" to "Lõi Valkyrie",
                "Extraction Point" to "Điểm rút quân",
                "Phantom Sector" to "Phân khu Bóng Ma",
                "UMD" to "Đĩa UMD"
            )
            val rules = TranslationRules(
                systemTagRegex = DEFAULT_FALLBACK_REGEX,
                gameGenreAndContext = "Hành động chiến thuật đen tối pha viễn tưởng quân sự (Dark Military Sci-Fi & Tactical RPG).",
                toneGuidelines = "Văn phong trưởng thành, đanh thép, giàu chất điện ảnh. Ưu tiên câu ngắn gọn vừa khung thoại PSP (480x272), giữ nguyên 100% thẻ {0}, {1}.",
                pronounRules = defaultPronouns,
                glossary = defaultGlossary,
                maxLengthExpansionRatio = 1.15
            )
            return rules.copy(rawJson = rules.toCompactJsonString())
        }
    }
}

/**
 * Stages of the 2-Tier PSP ISO Localization Pipeline.
 */
enum class PipelineStage(val stepIndex: Int, val labelVi: String) {
    IDLE(0, "Sẵn sàng nạp ROM (.iso)"),
    SCANNING_ISO(1, "Đang dò file ngôn ngữ (Tầng 1 - Task 1)"),
    ANALYZING_RULES(2, "Đang phân tích mã & lập quy tắc (Tầng 1 - Task 2)"),
    TRANSLATING_BULK(3, "Đang dịch hàng loạt & lưu Room (Tầng 2)"),
    REPACKING_ISO(4, "Đang đóng gói & vá nhị phân ISO"),
    PAUSED_QUOTA(3, "Tạm dừng (Đã lưu Checkpoint vào Room DB)"),
    COMPLETED(5, "Hoàn tất Việt hóa & Đóng gói ISO"),
    ERROR(0, "Gián đoạn (Đã bảo toàn tiến độ trong Room DB)")
}

/**
 * Result of masking system control codes in a single string before sending to Tier 2 AI.
 */
data class MaskedStringPayload(
    val stringIndex: Int,
    val originalText: String,
    val maskedText: String,
    val placeholderMap: Map<String, String>
) {
    fun placeholderMapToJson(): String {
        val obj = JSONObject()
        placeholderMap.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    companion object {
        fun placeholderMapFromJson(json: String): Map<String, String> {
            val map = linkedMapOf<String, String>()
            if (json.isBlank()) return map
            return try {
                val obj = JSONObject(json)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.optString(k)
                }
                map
            } catch (e: Exception) {
                map
            }
        }
    }
}

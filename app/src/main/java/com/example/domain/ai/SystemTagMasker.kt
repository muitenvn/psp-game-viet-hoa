package com.example.domain.ai

import com.example.domain.model.MaskedStringPayload
import com.example.domain.model.TranslationRules

/**
 * Protects PSP binary control codes, C format specifiers (%s, %d), button macros (SYS_BTN_X),
 * newline escapes (\n), and markup tags (<color=01>) by masking them into short placeholders
 * `{0}`, `{1}`, `{2}` prior to sending batches to Tier 2 AI, then restoring them post-translation.
 */
object SystemTagMasker {

    fun compileSafeRegex(patternString: String): Regex {
        return try {
            if (patternString.isBlank()) {
                Regex(TranslationRules.DEFAULT_FALLBACK_REGEX)
            } else {
                Regex(patternString)
            }
        } catch (e: Exception) {
            Regex(TranslationRules.DEFAULT_FALLBACK_REGEX)
        }
    }

    /**
     * Replaces all matches of [tagRegex] in [originalText] with `{0}`, `{1}`, `{2}`...
     */
    fun maskSystemTags(
        stringIndex: Int,
        originalText: String,
        tagRegex: Regex
    ): MaskedStringPayload {
        val placeholderMap = linkedMapOf<String, String>()
        var tagCounter = 0

        val masked = tagRegex.replace(originalText) { matchResult ->
            val tokenKey = "{$tagCounter}"
            placeholderMap[tokenKey] = matchResult.value
            tagCounter++
            tokenKey
        }

        return MaskedStringPayload(
            stringIndex = stringIndex,
            originalText = originalText,
            maskedText = masked,
            placeholderMap = placeholderMap
        )
    }

    /**
     * Restores `{0}`, `{1}`, `{2}` placeholders in [translatedMaskedText] back into the exact
     * original system control codes (`%s`, `<color=01>`, `SYS_BTN_CROSS`, `\n`).
     * Also guarantees that if a lightweight model accidentally omitted a required system tag,
     * the missing tag is safely re-attached so the PSP game engine never crashes on a missing `%s`.
     */
    fun restoreSystemTags(
        translatedMaskedText: String,
        placeholderMap: Map<String, String>
    ): String {
        if (placeholderMap.isEmpty()) return translatedMaskedText

        var restored = translatedMaskedText
        val missingCriticalTags = mutableListOf<String>()

        for ((placeholderKey, originalTag) in placeholderMap) {
            // Normalize accidental spacing like "{ 0 }" if a lite model introduced spaces
            val indexNum = placeholderKey.removePrefix("{").removeSuffix("}")
            val looseRegex = Regex("""\{\s*$indexNum\s*\}""")

            if (looseRegex.containsMatchIn(restored)) {
                restored = looseRegex.replaceFirst(restored, Regex.escapeReplacement(originalTag))
            } else {
                // Keep track of C printf specifiers or closing tags that must not be lost
                if (originalTag.startsWith("%") || originalTag.startsWith("\\n") || originalTag.contains("END")) {
                    missingCriticalTags.add(originalTag)
                }
            }
        }

        if (missingCriticalTags.isNotEmpty()) {
            restored = restored + " " + missingCriticalTags.joinToString("")
        }
        return restored.trim()
    }
}

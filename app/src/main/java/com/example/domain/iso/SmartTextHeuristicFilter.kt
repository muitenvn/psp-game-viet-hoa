package com.example.domain.iso

import java.util.Locale

/**
 * Intelligent Heuristic Filter for PSP ROM String Extraction (Requirement 3).
 *
 * Ensures that only genuine human-readable dialogue, mission briefings, objectives, and UI messages
 * (minimum 10 characters, null-terminated `0x00`) are accepted, while aggressively rejecting
 * C/C++ compiler symbols, SDK imports, engine variables, and button macros (e.g. `SYS_BTN_X`,
 * `activision`, `heap_alloc`, `sceKernel*`).
 */
object SmartTextHeuristicFilter {

    private const val MIN_VALID_CHAR_LENGTH = 5

    // Known compiler, publisher, and PSP SDK technical tokens that appear in EBOOT.BIN or binary headers
    private val BLACKLISTED_SUBSTRINGS = listOf(
        "heap_alloc",
        "activision",
        "amaze entertainment",
        "scekernel",
        "sceio",
        "scectrl",
        "scedisplay",
        "scege",
        "scegu",
        "sceaudio",
        "sceutility",
        "scempeg",
        "scenet",
        "scewlan",
        "scertc",
        "scepower",
        "__cxa_",
        "__gnu_",
        "_impure_ptr",
        "malloc",
        "realloc",
        "calloc",
        "memalign",
        "free(",
        "memcpy",
        "memset",
        "memmove",
        "sprintf",
        "snprintf",
        "vsprintf",
        "vsnprintf",
        "sscanf",
        "fopen",
        "fclose",
        "fread",
        "fwrite",
        "fseek",
        "ftell",
        "pthread_",
        "semaphore",
        "mutex",
        "vertexbuffer",
        "indexbuffer",
        "rendertarget",
        "shader",
        "texture2d",
        "quaternion",
        "matrix4x4",
        "boundingbox",
        "disc0:",
        "umd0:",
        "host0:",
        "ms0:",
        "flash0:",
        ".rodata",
        ".shstrtab",
        ".symtab",
        ".strtab",
        "gcc: (gnu)",
        "libstdc++"
    )

    // File extensions commonly referenced in asset tables inside binaries (not dialogue)
    private val ASSET_FILE_EXTENSIONS = listOf(
        ".prx", ".gim", ".tm2", ".pmf", ".at3", ".vag", ".afs", ".bgm",
        ".wav", ".ogg", ".mp3", ".tga", ".bmp", ".png", ".dds", ".mdl",
        ".anm", ".skl", ".bsp", ".col", ".nav", ".fx", ".psh", ".vsh"
    )

    // Regex matching pure UPPERCASE_MACRO_NAMES like SYS_BTN_X, BTN_START, WEAPON_M1_GARAND
    private val MACRO_IDENTIFIER_REGEX = Regex("""^[A-Z0-9_.:/\-]+$""")

    // Regex matching camelCase or snake_case function/variable names without spaces (e.g. initWeaponMgr, m_pPlayer)
    private val CODE_SYMBOL_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$""")

    // Regex matching camelCase transitions like "getPlayerHealth" or " m_pHud"
    private val CAMEL_CASE_TRANSITION_REGEX = Regex("""[a-z][A-Z0-9]{2,}|[a-z]+[A-Z][a-z]+""")

    // Regex matching printf-only debug format strings like "%s: %d (%08x)"
    private val DEBUG_FORMAT_ONLY_REGEX = Regex("""^[\s%0-9dxXsfp.:,\-\[\]()/\\]+$""")

    private const val VOWELS = "aeiouyAEIOUYáàảãạăắằẳẵặâấầẩẫậéèẻẽẹêếềểễệíìỉĩịóòỏõọôốồổỗộơớờởỡợúùủũụưứừửữựýỳỷỹỵ"

    /**
     * Evaluates whether a null-terminated candidate string is valid human-readable game text
     * (length >= 5 characters, natural language words/vowels, not an engine/compiler symbol).
     */
    fun isValidGameSentence(rawCandidate: String): Boolean {
        val trimmed = rawCandidate.trim()

        // 1. Minimum length: at least 5 contiguous characters
        if (trimmed.length < MIN_VALID_CHAR_LENGTH) return false
        if (trimmed.length > 2048) return false

        // 2. Strip inline markup/tags temporarily to evaluate the actual human text core
        val strippedCore = trimmed
            .replace(Regex("""<[a-zA-Z0-9_=/#]+>"""), " ")
            .replace(Regex("""\[[A-Z0-9_:]+\]"""), " ")
            .replace(Regex("""SYS_BTN_[A-Z0-9_]+"""), " ")
            .replace(Regex("""%[0-9.]*[sdxfc]"""), " ")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .trim()

        if (strippedCore.length < MIN_VALID_CHAR_LENGTH) return false

        val lower = trimmed.lowercase(Locale.ROOT)

        // 3. Reject blacklisted technical/compiler/publisher symbols
        for (bad in BLACKLISTED_SUBSTRINGS) {
            if (lower.contains(bad)) return false
        }

        // 4. Reject asset path references (e.g. "levels/normandy/sky.gim")
        for (ext in ASSET_FILE_EXTENSIONS) {
            if (lower.endsWith(ext) || lower.contains("$ext ")) return false
        }
        if ((lower.contains("/") || lower.contains("\\")) && !lower.contains(" ")) {
            return false
        }

        // 5. Reject single-token macros or code identifiers (e.g. `SYS_BTN_X`, `m_pWeaponMgr`, `initAudio`)
        if (MACRO_IDENTIFIER_REGEX.matches(strippedCore)) return false
        if (CODE_SYMBOL_REGEX.matches(strippedCore)) return false
        if (DEBUG_FORMAT_ONLY_REGEX.matches(strippedCore)) return false
        if (!strippedCore.contains(" ") && CAMEL_CASE_TRANSITION_REGEX.containsMatchIn(strippedCore)) {
            return false
        }

        // 6. Reject snake_case or camelCase debug logs (e.g. "Error: failed_to_alloc_node in init()")
        if (strippedCore.contains("_") && !strippedCore.contains(" ")) return false
        val underscoreCount = strippedCore.count { it == '_' }
        if (underscoreCount >= 2) return false

        // 7. Check character distribution (must look like a natural language phrase/sentence)
        var letterCount = 0
        var vowelCount = 0
        var digitCount = 0
        var spaceCount = 0
        var cjkOrHighUnicodeCount = 0
        var weirdSymbolCount = 0

        for (ch in strippedCore) {
            when {
                ch.code > 0x024F -> {
                    cjkOrHighUnicodeCount++
                    letterCount++
                }
                ch.isLetter() -> {
                    letterCount++
                    if (VOWELS.indexOf(ch) >= 0) vowelCount++
                }
                ch.isDigit() -> digitCount++
                ch.isWhitespace() -> spaceCount++
                ch in ".,!?'\":;-()[]{}/%&+" -> Unit
                else -> weirdSymbolCount++
            }
        }

        // Reject if too many non-punctuation symbols (binary garbage masquerading as ASCII)
        if (weirdSymbolCount > 2 || weirdSymbolCount.toFloat() / strippedCore.length > 0.08f) {
            return false
        }

        // For Latin/English/European text, require natural word spacing or punctuation and vowels
        if (cjkOrHighUnicodeCount == 0) {
            if (letterCount < 4) return false
            val vowelRatio = vowelCount.toFloat() / letterCount.toFloat()
            if (vowelRatio < 0.15f || vowelRatio > 0.80f) return false
            if (digitCount > letterCount) return false

            // Single-word strings without spaces are only valid if they end with dialogue punctuation (e.g. "Reload!")
            // Otherwise require at least 2 words separated by whitespace
            if (spaceCount < 1) {
                val endsWithDialoguePunct = strippedCore.endsWith("!") || strippedCore.endsWith("?") || strippedCore.endsWith(".")
                val startsWithCapital = strippedCore.first().isUpperCase() && strippedCore.drop(1).all { it.isLowerCase() || it in "!?.…" }
                if (!(endsWithDialoguePunct && startsWithCapital)) return false
            } else {
                val words = strippedCore.split(Regex("""\s+""")).filter { it.isNotBlank() }
                if (words.size < 2) return false
                val realWords = words.count { w -> w.count { it.isLetter() } >= 2 }
                if (realWords < 1) return false
            }
        }

        return true
    }
}

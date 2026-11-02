package com.example

import com.example.domain.ai.SystemTagMasker
import com.example.domain.model.TranslationRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun systemTagMasker_masksAndRestoresPspControlCodesIntact() {
        val regex = SystemTagMasker.compileSafeRegex(TranslationRules.DEFAULT_FALLBACK_REGEX)
        val rawLine = "<color=01>[Commander Vance]</color> Listen up, %s! Press SYS_BTN_CROSS to deploy shield.\\n"

        val maskedPayload = SystemTagMasker.maskSystemTags(
            stringIndex = 0,
            originalText = rawLine,
            tagRegex = regex
        )

        assertTrue(maskedPayload.maskedText.contains("{0}"))
        assertTrue(!maskedPayload.maskedText.contains("SYS_BTN_CROSS"))
        assertTrue(!maskedPayload.maskedText.contains("<color=01>"))

        val simulatedTier2Vi = "{0}[Commander Vance]{1} Nghe rõ đây, {2}! Nhấn {3} để bung khiên chiến thuật.{4}"
        val restored = SystemTagMasker.restoreSystemTags(simulatedTier2Vi, maskedPayload.placeholderMap)

        assertEquals(
            "<color=01>[Commander Vance]</color> Nghe rõ đây, %s! Nhấn SYS_BTN_CROSS để bung khiên chiến thuật.\\n",
            restored
        )
    }
}

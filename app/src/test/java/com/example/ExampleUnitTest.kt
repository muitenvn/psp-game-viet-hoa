package com.example

import com.example.domain.ai.SystemTagMasker
import com.example.domain.iso.PspArchiveUnpacker
import com.example.domain.iso.SmartTextHeuristicFilter
import com.example.domain.model.TranslationRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

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

    @Test
    fun smartTextHeuristicFilter_rejectsEbootSymbolsAndAcceptsValidGameText() {
        // EBOOT.BIN compiler/engine symbols must be rejected
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("SYS_BTN_X"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("activision"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("heap_alloc"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("sceKernelLoadModule"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("m_pWeaponManager"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("levels/normandy/sky.gim"))

        // Corrupted binary garbage from user screenshot must be strictly rejected
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("!\uFFFDqG\uFFFDC\uFFFD\uFFFD"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("ʙ₉X\uFFFD\uFFFD\uFFFD\uFFFD\uFFFDz\uFFFD\uFFFD\uFFFDIQ%\uFFFD\uFFFD"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("q\uFFFD\uFFFD\uFFFD%\uFFFDU"))
        assertFalse(SmartTextHeuristicFilter.isValidGameSentence("31C54Fz\uFFFD\uFFFD\uFFFD{(S4\uFFFD/ \"\uFFFD"))

        // Meaningful game text (>= 5 chars) must be accepted
        assertTrue(SmartTextHeuristicFilter.isValidGameSentence("Reload!"))
        assertTrue(SmartTextHeuristicFilter.isValidGameSentence("Follow Sgt. Randall to the bunker!"))
        assertTrue(SmartTextHeuristicFilter.isValidGameSentence("<color=01>[HQ]</color> Secure the artillery position, %s!"))
    }

    @Test
    fun pspArchiveUnpacker_parsesPakTocAndPointerTableHeaders() {
        val header = ByteArray(64)
        System.arraycopy("PACK".toByteArray(StandardCharsets.US_ASCII), 0, header, 0, 4)
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(2)
        // Entry 0: offset 64, size 120
        ByteBuffer.wrap(header, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(64)
        ByteBuffer.wrap(header, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(120)
        // Entry 1: offset 184, size 200
        ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(184)
        ByteBuffer.wrap(header, 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(200)

        val inspection = PspArchiveUnpacker.inspectHeader(header, totalFileSize = 1024L)
        assertEquals(PspArchiveUnpacker.ContainerFormat.PAK_ARCHIVE, inspection.format)
        assertTrue(inspection.hasPointerTable)
        assertEquals(2, inspection.subFiles.size)
        assertEquals(64L, inspection.subFiles[0].offsetInParent)
        assertEquals(184L, inspection.subFiles[1].offsetInParent)
    }
}

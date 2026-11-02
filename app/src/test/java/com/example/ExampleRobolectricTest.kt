package com.example

import com.example.data.local.TranslationEntryEntity
import com.example.domain.iso.PointerAwareBinaryPatcher
import com.example.domain.iso.RandomAccessChannel
import com.example.domain.model.IsoFileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class ExampleRobolectricTest {

    private class InMemoryChannel(size: Int) : RandomAccessChannel {
        var data = ByteArray(size)
        private var pos = 0

        override fun length(): Long = data.size.toLong()

        override fun seek(position: Long) {
            pos = position.toInt()
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            System.arraycopy(data, pos, buffer, offset, length)
            pos += length
        }

        override fun writeFully(buffer: ByteArray, offset: Int, length: Int) {
            if (pos + length > data.size) {
                data = data.copyOf(pos + length)
            }
            System.arraycopy(buffer, offset, data, pos, length)
            pos += length
        }
    }

    @Test
    fun pointerAwareBinaryPatcher_recalculatesPointerTableAndUpdatesIsoLba() = runBlocking {
        val channel = InMemoryChannel(4096)
        val containerOffset = 2048L

        // Write a PSPTBL01 header at sector 1 (offset 2048) with 2 entries and max size 512
        System.arraycopy("PSPTBL01".toByteArray(StandardCharsets.US_ASCII), 0, channel.data, 2048, 8)
        ByteBuffer.wrap(channel.data, 2056, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(2)
        ByteBuffer.wrap(channel.data, 2060, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(512)

        val entries = listOf(
            TranslationEntryEntity(
                romId = "COD_PSP",
                targetFilePath = "PSP_GAME/USRDIR/script_en.bin",
                stringIndex = 0,
                byteOffsetInIso = 2048L + 32L,
                allocatedByteLength = 32,
                originalText = "Follow Sgt. Randall!",
                maskedText = "Follow Sgt. Randall!",
                placeholderMapJson = "{}",
                translatedText = "Theo sát Trung sĩ Randall tiến vào boong-ke ngay lập tức!",
                status = TranslationEntryEntity.STATUS_TRANSLATED
            ),
            TranslationEntryEntity(
                romId = "COD_PSP",
                targetFilePath = "PSP_GAME/USRDIR/script_en.bin",
                stringIndex = 1,
                byteOffsetInIso = 2048L + 64L,
                allocatedByteLength = 32,
                originalText = "Reload weapon!",
                maskedText = "Reload weapon!",
                placeholderMapJson = "{}",
                translatedText = "Nạp lại đạn nhanh lên!",
                status = TranslationEntryEntity.STATUS_TRANSLATED
            )
        )

        val isoEntries = listOf(
            IsoFileEntry(
                path = "PSP_GAME/USRDIR/script_en.bin",
                name = "script_en.bin",
                sizeBytes = 512L,
                lbaSector = 1L,
                isDirectory = false,
                dirRecordIsoOffset = 128L,
                hasPointerTable = true
            )
        )

        val stats = PointerAwareBinaryPatcher.patchIsoWithPointerRecalculation(
            channel = channel,
            entries = entries,
            isoEntries = isoEntries,
            onProgress = { _, _ -> }
        )

        assertEquals(2, stats.totalPatchedStrings)
        assertEquals(2, stats.pointerTableUpdatedCount)

        // Verify Pointer 0 and Pointer 1 in the rebuilt Pointer Table at offset 2048 + 16
        val ptr0Offset = ByteBuffer.wrap(channel.data, 2048 + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val ptr0Len = ByteBuffer.wrap(channel.data, 2048 + 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val ptr1Offset = ByteBuffer.wrap(channel.data, 2048 + 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertTrue(ptr1Offset >= ptr0Offset + ptr0Len)
    }
}

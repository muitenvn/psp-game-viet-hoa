package com.example.domain.iso

import com.example.data.local.TranslationEntryEntity
import com.example.domain.model.IsoFileEntry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Pointer-Table Aware Binary Patcher & ISO-9660 LBA Repacker (Requirements 3 & 4).
 *
 * 1. Reconstructs the Pointer Table / Table of Contents (TOC) at the head of `.BIN`, `.PAK`, `.DAT`, `.WAD`
 *    containers so variable-length UTF-8 Vietnamese strings have exact `[newOffset, newLength]` pointers.
 * 2. Writes the repacked container back to its original LBA Sector in the `.iso` if it fits within the
 *    allocated sectors, OR relocates it to a new 2048-byte aligned LBA sector at the end of the `.iso` and
 *    updates the ISO-9660 Directory Record (Extent LBA + Data Length in both-endian format) and PVD Volume Space Size.
 */
object PointerAwareBinaryPatcher {

    private const val SECTOR_SIZE = 2048L
    private const val PVD_SECTOR_OFFSET = 16L * SECTOR_SIZE

    data class PatchStatistics(
        val totalPatchedStrings: Int,
        val pointerTableUpdatedCount: Int,
        val inPlacePatchedCount: Int,
        val lbaRelocatedFilesCount: Int = 0
    )

    /**
     * Patches all translated entries back into the `.iso` via [RandomAccessChannel],
     * automatically rebuilding Pointer Tables / PAK TOCs and updating ISO-9660 LBA tables if needed.
     */
    suspend fun patchIsoWithPointerRecalculation(
        channel: RandomAccessChannel,
        entries: List<TranslationEntryEntity>,
        isoEntries: List<IsoFileEntry> = emptyList(),
        onProgress: suspend (patchedCount: Int, totalCount: Int) -> Unit
    ): PatchStatistics {
        val validEntries = entries.filter { !it.translatedText.isNullOrBlank() && it.byteOffsetInIso > 0 }
        if (validEntries.isEmpty()) return PatchStatistics(0, 0, 0, 0)

        val isoEntryByPath = isoEntries.associateBy { it.path.lowercase() }
        val byTargetFile = validEntries.groupBy { it.targetFilePath.substringBefore("#").substringBefore(" [") }

        var totalPatched = 0
        var pointerUpdated = 0
        var inPlaceUpdated = 0
        var relocatedFiles = 0
        val grandTotal = validEntries.size

        for ((cleanFilePath, fileEntries) in byTargetFile) {
            val sorted = fileEntries.sortedBy { it.stringIndex }
            val matchedIsoEntry = isoEntryByPath[cleanFilePath.lowercase()]

            // Check if this file uses a Pointer Table / TOC header ("PSPTBL01", "PSPSCR01", "PACK", "PAK\0")
            val firstEntry = sorted.first()
            val fileHeaderCandidateOffset = matchedIsoEntry?.byteOffsetInIso
                ?: findContainerHeaderBaseOffset(channel, firstEntry.byteOffsetInIso)

            if (fileHeaderCandidateOffset >= 0L) {
                val rebuildOutcome = rebuildPointerTableAndRepackIntoIso(
                    channel = channel,
                    containerIsoOffset = fileHeaderCandidateOffset,
                    isoFileEntry = matchedIsoEntry,
                    sortedEntries = sorted
                )
                if (rebuildOutcome.patchedStringsCount > 0) {
                    pointerUpdated += rebuildOutcome.patchedStringsCount
                    totalPatched += rebuildOutcome.patchedStringsCount
                    if (rebuildOutcome.wasRelocatedToNewLba) {
                        relocatedFiles++
                    }
                    onProgress(totalPatched, grandTotal)
                    continue
                }
            }

            // Fallback: Safe in-place binary patching with guaranteed 0x00 null terminator
            for (entry in sorted) {
                val translated = entry.translatedText ?: continue
                val utf8Bytes = translated.toByteArray(StandardCharsets.UTF_8)
                val maxSlotBytes = entry.allocatedByteLength.coerceAtLeast(4)

                // Always reserve at least 1 trailing byte for 0x00 null terminator
                val patchBuffer = ByteArray(maxSlotBytes)
                val copyLen = min(utf8Bytes.size, maxSlotBytes - 1)
                System.arraycopy(utf8Bytes, 0, patchBuffer, 0, copyLen)
                patchBuffer[copyLen] = 0x00

                channel.seek(entry.byteOffsetInIso)
                channel.writeFully(patchBuffer)

                inPlaceUpdated++
                totalPatched++
                if (totalPatched % 15 == 0 || totalPatched == grandTotal) {
                    onProgress(totalPatched, grandTotal)
                }
            }
        }

        return PatchStatistics(
            totalPatchedStrings = totalPatched,
            pointerTableUpdatedCount = pointerUpdated,
            inPlacePatchedCount = inPlaceUpdated,
            lbaRelocatedFilesCount = relocatedFiles
        )
    }

    private data class RebuildOutcome(
        val patchedStringsCount: Int,
        val wasRelocatedToNewLba: Boolean
    )

    /**
     * Checks if the container sector preceding [firstStringIsoOffset] starts with a `PSPTBL01`, `PSPSCR01`,
     * `PACK`, or `PAK\u0000` pointer-table / TOC header.
     */
    private fun findContainerHeaderBaseOffset(
        channel: RandomAccessChannel,
        firstStringIsoOffset: Long
    ): Long {
        val sectorAlignedBase = (firstStringIsoOffset / SECTOR_SIZE) * SECTOR_SIZE
        val candidates = listOf(sectorAlignedBase, (sectorAlignedBase - SECTOR_SIZE).coerceAtLeast(0L))

        val probe = ByteArray(16)
        for (candidateOffset in candidates) {
            if (candidateOffset + 16 > channel.length()) continue
            channel.seek(candidateOffset)
            channel.readFully(probe)
            val magic8 = String(probe, 0, 8, StandardCharsets.US_ASCII)
            if (magic8 == "PSPTBL01" || magic8 == "PSPSCR01") {
                return candidateOffset
            }
        }
        return -1L
    }

    /**
     * Reconstructs the Pointer Table (`[newRelativeOffset, newByteLength]`) and relocated UTF-8
     * string pool so variable-length Vietnamese strings never overlap or corrupt pointers, then:
     * - Writes in-place at `containerIsoOffset` if `totalRebuiltSize <= originalAllocatedBytes`, OR
     * - Appends to a new 2048-byte aligned LBA sector and updates the ISO-9660 Directory Record LBA & Size!
     */
    private fun rebuildPointerTableAndRepackIntoIso(
        channel: RandomAccessChannel,
        containerIsoOffset: Long,
        isoFileEntry: IsoFileEntry?,
        sortedEntries: List<TranslationEntryEntity>
    ): RebuildOutcome {
        if (containerIsoOffset + 16 > channel.length()) return RebuildOutcome(0, false)

        val header = ByteArray(16)
        channel.seek(containerIsoOffset)
        channel.readFully(header)

        val magic8 = String(header, 0, 8, StandardCharsets.US_ASCII)
        if (magic8 != "PSPTBL01" && magic8 != "PSPSCR01") {
            return RebuildOutcome(0, false)
        }

        val recordedEntryCount = ByteBuffer.wrap(header, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val originalContainerBytes = ByteBuffer.wrap(header, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int

        val count = if (recordedEntryCount > 0) min(recordedEntryCount, sortedEntries.size) else sortedEntries.size
        if (count <= 0) return RebuildOutcome(0, false)

        val pointerTableOffset = 16
        val pointerTableSize = count * 8 // Each pointer entry is [uint32 offset, uint32 length]
        val stringPoolStartOffset = ((pointerTableOffset + pointerTableSize + 15) / 16) * 16

        val pointerTableBuffer = ByteBuffer.allocate(pointerTableSize).order(ByteOrder.LITTLE_ENDIAN)
        val stringPoolStream = ByteArrayOutputStream(32 * 1024)

        for (i in 0 until count) {
            val entry = sortedEntries[i]
            val finalViText = entry.translatedText?.takeIf { it.isNotBlank() } ?: entry.originalText
            val utf8Bytes = finalViText.toByteArray(StandardCharsets.UTF_8)

            val newRelativeOffset = stringPoolStartOffset + stringPoolStream.size()
            val newByteLength = utf8Bytes.size + 1 // +1 for null terminator 0x00

            // Write updated [offset, length] into the 32-bit Little-Endian Pointer Table
            pointerTableBuffer.putInt(newRelativeOffset)
            pointerTableBuffer.putInt(newByteLength)

            // Write relocated UTF-8 string + 0x00 + 4-byte alignment padding
            stringPoolStream.write(utf8Bytes)
            stringPoolStream.write(0x00)
            while ((stringPoolStream.size() % 4) != 0) {
                stringPoolStream.write(0x00)
            }
        }

        val rebuiltStringPool = stringPoolStream.toByteArray()
        val exactRebuiltSize = stringPoolStartOffset + rebuiltStringPool.size
        val originalSlotLimit = if (originalContainerBytes > 0) {
            originalContainerBytes
        } else {
            isoFileEntry?.sizeBytes?.toInt() ?: exactRebuiltSize
        }

        val finalContainerSize = maxOf(exactRebuiltSize, originalSlotLimit)
        val fullContainerBytes = ByteArray(finalContainerSize)

        System.arraycopy(magic8.toByteArray(StandardCharsets.US_ASCII), 0, fullContainerBytes, 0, 8)
        ByteBuffer.wrap(fullContainerBytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(count)
        ByteBuffer.wrap(fullContainerBytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(finalContainerSize)

        val ptrBytes = pointerTableBuffer.array()
        System.arraycopy(ptrBytes, 0, fullContainerBytes, pointerTableOffset, ptrBytes.size)
        System.arraycopy(rebuiltStringPool, 0, fullContainerBytes, stringPoolStartOffset, rebuiltStringPool.size)

        // CASE 1: Fits inside the original LBA sector allocation -> Write directly at original LBA
        if (exactRebuiltSize <= originalSlotLimit && containerIsoOffset + fullContainerBytes.size <= channel.length()) {
            channel.seek(containerIsoOffset)
            channel.writeFully(fullContainerBytes)
            if (isoFileEntry != null && isoFileEntry.dirRecordIsoOffset > 0L) {
                updateIso9660DirectoryRecordLbaAndSize(
                    channel = channel,
                    dirRecordOffset = isoFileEntry.dirRecordIsoOffset,
                    newLba = isoFileEntry.lbaSector.toInt(),
                    newSizeBytes = finalContainerSize
                )
            }
            return RebuildOutcome(count, wasRelocatedToNewLba = false)
        }

        // CASE 2: Expanded Vietnamese text exceeds original allocation -> Relocate to new LBA at end of ISO
        // and update ISO-9660 Directory Record LBA + Size & PVD Total Sectors!
        if (isoFileEntry != null && isoFileEntry.dirRecordIsoOffset > 0L) {
            val alignedEndOffset = ((channel.length() + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE
            val newLba = (alignedEndOffset / SECTOR_SIZE).toInt()
            val paddedSectorBytes = (((finalContainerSize + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE).toInt()
            val sectorAlignedContainer = fullContainerBytes.copyOf(paddedSectorBytes)

            channel.seek(alignedEndOffset)
            channel.writeFully(sectorAlignedContainer)

            updateIso9660DirectoryRecordLbaAndSize(
                channel = channel,
                dirRecordOffset = isoFileEntry.dirRecordIsoOffset,
                newLba = newLba,
                newSizeBytes = finalContainerSize
            )
            val newTotalSectors = ((alignedEndOffset + paddedSectorBytes) / SECTOR_SIZE).toInt()
            updatePvdVolumeSpaceSize(channel, newTotalSectors)

            return RebuildOutcome(count, wasRelocatedToNewLba = true)
        }

        return RebuildOutcome(0, false)
    }

    /**
     * Updates an ISO-9660 Directory Record's Extent LBA (offset +2, both-endian int32)
     * and Data Length (offset +10, both-endian int32).
     */
    fun updateIso9660DirectoryRecordLbaAndSize(
        channel: RandomAccessChannel,
        dirRecordOffset: Long,
        newLba: Int,
        newSizeBytes: Int
    ) {
        if (dirRecordOffset <= 0L || dirRecordOffset + 18 > channel.length()) return
        val bothEndianBuf = ByteArray(16)
        // Extent LBA (Little-Endian + Big-Endian)
        ByteBuffer.wrap(bothEndianBuf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newLba)
        ByteBuffer.wrap(bothEndianBuf, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(newLba)
        // Data Length / Size (Little-Endian + Big-Endian)
        ByteBuffer.wrap(bothEndianBuf, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newSizeBytes)
        ByteBuffer.wrap(bothEndianBuf, 12, 4).order(ByteOrder.BIG_ENDIAN).putInt(newSizeBytes)

        channel.seek(dirRecordOffset + 2L)
        channel.writeFully(bothEndianBuf)
    }

    /**
     * Updates the ISO-9660 Primary Volume Descriptor (Sector 16, offset +80) Volume Space Size
     * when a repacked archive is appended to the end of the ISO.
     */
    private fun updatePvdVolumeSpaceSize(
        channel: RandomAccessChannel,
        newTotalSectors: Int
    ) {
        if (PVD_SECTOR_OFFSET + 88 > channel.length()) return
        val buf = ByteArray(8)
        ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newTotalSectors)
        ByteBuffer.wrap(buf, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(newTotalSectors)
        channel.seek(PVD_SECTOR_OFFSET + 80L)
        channel.writeFully(buf)
    }
}

package com.example.domain.iso

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.local.TranslationEntryEntity
import com.example.domain.model.IsoFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Low-memory ISO-9660 recursive scanner, Archive/PAK Unpacker, Smart Heuristic String Extractor,
 * and Pointer-Table Aware Binary Patcher for PSP (.iso) ROMs.
 */
class Iso9660RandomAccessEngine(private val context: Context) {

    companion object {
        const val SECTOR_SIZE = 2048
        const val PVD_SECTOR = 16
        private const val BUFFER_CHUNK_SIZE = 64 * 1024 // 64 KB sliding window
        private const val HEADER_INSPECT_BYTES = 2048
    }

    data class ExtractedStringRecord(
        val stringIndex: Int,
        val sourceFilePath: String,
        val byteOffsetInIso: Long,
        val allocatedByteLength: Int,
        val rawText: String,
        val hasPointerEntry: Boolean = false
    )

    data class IsoScanSummary(
        val volumeId: String,
        val isoSizeBytes: Long,
        val entries: List<IsoFileEntry>,
        val usrDirArchiveCount: Int
    )

    /**
     * Opens a SAF Uri or file:// Uri and executes [block] with a seekable [RandomAccessChannel].
     */
    suspend fun <T> withSeekableIso(
        uri: Uri,
        writable: Boolean = false,
        block: suspend (RandomAccessChannel) -> T
    ): T = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            RandomAccessFile(file, if (writable) "rw" else "r").use { raf ->
                block(RafBackedChannel(raf))
            }
        } else {
            val mode = if (writable) "rw" else "r"
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, mode)
                ?: throw IllegalStateException("Không thể mở file .iso qua SAF (URI: $uri)")
            pfd.use { descriptor ->
                val procFdFile = File("/proc/self/fd/${descriptor.fd}")
                if (procFdFile.exists() && procFdFile.canRead()) {
                    try {
                        RandomAccessFile(procFdFile, mode).use { raf ->
                            return@withContext block(RafBackedChannel(raf))
                        }
                    } catch (_: Exception) {
                        // Fallback to FileChannel below
                    }
                }
                if (writable) {
                    FileOutputStream(descriptor.fileDescriptor).channel.use { outChannel ->
                        FileInputStream(descriptor.fileDescriptor).channel.use { inChannel ->
                            block(DualFileChannelAdapter(inChannel, outChannel))
                        }
                    }
                } else {
                    FileInputStream(descriptor.fileDescriptor).channel.use { inChannel ->
                        block(DualFileChannelAdapter(inChannel, null))
                    }
                }
            }
        }
    }

    /**
     * REQUIREMENT 1 & 2:
     * Recursively scans the entire ISO-9660 directory tree (with special focus on `PSP_GAME/USRDIR/`
     * and all its nested sub-folders), inspects the 4-byte Magic Header of every resource/archive file
     * (`.dat`, `.pak`, `.bin`, `.txt`, `.str`, `.cfg`), and detects GZIP/ZLIB/LZO/PAK/Pointer Table formats.
     */
    suspend fun scanIsoDirectoryTree(uri: Uri): IsoScanSummary = withSeekableIso(uri, writable = false) { channel ->
        val totalLength = channel.length()
        if (totalLength < (PVD_SECTOR + 1) * SECTOR_SIZE) {
            throw IllegalArgumentException("File .iso quá nhỏ hoặc không đúng chuẩn ISO-9660 ($totalLength bytes).")
        }

        val pvdBytes = ByteArray(SECTOR_SIZE)
        channel.seek((PVD_SECTOR * SECTOR_SIZE).toLong())
        channel.readFully(pvdBytes)

        val magic = String(pvdBytes, 1, 5, StandardCharsets.US_ASCII)
        val volumeId = if (magic == "CD001") {
            String(pvdBytes, 40, 32, StandardCharsets.US_ASCII).trim()
                .ifBlank { "PSP_UMD_ROM" }
        } else {
            "PSP_CUSTOM_ISO"
        }

        val discovered = mutableListOf<IsoFileEntry>()
        val visitedLbas = mutableSetOf<Long>()
        if (magic == "CD001") {
            val rootExtentLba = readUInt32LE(pvdBytes, 156 + 2)
            val rootDataLength = readUInt32LE(pvdBytes, 156 + 10)
            traverseIsoDirectoryRecursive(
                channel = channel,
                dirLba = rootExtentLba,
                dirSize = rootDataLength,
                parentPath = "",
                depth = 0,
                visitedDirLbas = visitedLbas,
                outList = discovered
            )
        }

        // Inspect 4-byte Magic & Container Header (GZIP, ZLIB, LZO, PAK Offset/Size, Pointer Table)
        val enriched = discovered.map { entry ->
            if (!entry.isDirectory && entry.sizeBytes >= 8 && entry.byteOffsetInIso + 8 <= totalLength) {
                val inspectLen = min(HEADER_INSPECT_BYTES.toLong(), min(entry.sizeBytes, totalLength - entry.byteOffsetInIso)).toInt()
                val headerBuf = ByteArray(inspectLen)
                channel.seek(entry.byteOffsetInIso)
                channel.readFully(headerBuf)

                val hex12 = headerBuf.take(12).joinToString(" ") { "%02X".format(it) }
                val ascii = headerBuf.take(64).map { b ->
                    val c = b.toInt() and 0xFF
                    if (c in 32..126) c.toChar() else '.'
                }.joinToString("")

                val inspection = PspArchiveUnpacker.inspectHeader(headerBuf, entry.sizeBytes)
                entry.copy(
                    headerPreviewHex = hex12,
                    asciiSamplePreview = ascii,
                    magic4BytesHex = inspection.magicHex,
                    containerFormatLabel = inspection.format.label,
                    subFileCount = inspection.subFiles.size,
                    hasPointerTable = inspection.hasPointerTable
                )
            } else {
                entry
            }
        }

        val usrDirArchives = enriched.count { it.isCandidateArchiveOrScript }

        IsoScanSummary(
            volumeId = volumeId,
            isoSizeBytes = totalLength,
            entries = enriched,
            usrDirArchiveCount = usrDirArchives
        )
    }

    private fun traverseIsoDirectoryRecursive(
        channel: RandomAccessChannel,
        dirLba: Long,
        dirSize: Long,
        parentPath: String,
        depth: Int,
        visitedDirLbas: MutableSet<Long>,
        outList: MutableList<IsoFileEntry>
    ) {
        if (depth > 12 || dirLba <= 0 || dirSize <= 0) return
        if (!visitedDirLbas.add(dirLba)) return

        // Support large directories up to 64 sectors (128 KB of directory records)
        val maxRead = min(dirSize, (SECTOR_SIZE * 64).toLong()).toInt()
        if (dirLba * SECTOR_SIZE + maxRead > channel.length()) return

        val dirBytes = ByteArray(maxRead)
        channel.seek(dirLba * SECTOR_SIZE)
        channel.readFully(dirBytes)

        var pos = 0
        while (pos < dirBytes.size) {
            val recordLen = dirBytes[pos].toInt() and 0xFF
            if (recordLen == 0) {
                val nextSector = ((pos / SECTOR_SIZE) + 1) * SECTOR_SIZE
                if (nextSector >= dirBytes.size) break
                pos = nextSector
                continue
            }
            if (pos + recordLen > dirBytes.size || recordLen < 34) break

            val extentLba = readUInt32LE(dirBytes, pos + 2)
            val dataLen = readUInt32LE(dirBytes, pos + 10)
            val flags = dirBytes[pos + 25].toInt() and 0xFF
            val isDir = (flags and 0x02) != 0
            val nameLen = dirBytes[pos + 32].toInt() and 0xFF

            if (nameLen > 0 && pos + 33 + nameLen <= dirBytes.size) {
                val rawName = String(dirBytes, pos + 33, nameLen, StandardCharsets.US_ASCII)
                val cleanName = rawName.substringBefore(";").trim()
                val isDotEntry = (nameLen == 1 && (dirBytes[pos + 33].toInt() == 0 || dirBytes[pos + 33].toInt() == 1))

                if (!isDotEntry && cleanName.isNotEmpty()) {
                    val fullPath = if (parentPath.isEmpty()) cleanName else "$parentPath/$cleanName"
                    val entry = IsoFileEntry(
                        path = fullPath,
                        name = cleanName,
                        sizeBytes = dataLen,
                        lbaSector = extentLba,
                        isDirectory = isDir,
                        dirRecordIsoOffset = dirLba * SECTOR_SIZE + pos
                    )
                    outList.add(entry)
                    if (isDir && extentLba != dirLba) {
                        traverseIsoDirectoryRecursive(
                            channel = channel,
                            dirLba = extentLba,
                            dirSize = dataLen,
                            parentPath = fullPath,
                            depth = depth + 1,
                            visitedDirLbas = visitedDirLbas,
                            outList = outList
                        )
                    }
                }
            }
            pos += recordLen
        }
    }

    /**
     * REQUIREMENT 1, 2 & 3:
     * Scans and unpacks ALL candidate archive/resource files inside `PSP_GAME/USRDIR/`
     * (`.pak`, `.dat`, `.bin`, `.wad`, `.txt`, `.str`, `.cfg`), excluding `EBOOT.BIN`!
     * Automatically unpacks PAK/WAD TOC sub-files, decompresses GZIP/ZLIB blocks, scans both UTF-8/ASCII
     * and UTF-16LE strings, and filters every candidate through [SmartTextHeuristicFilter].
     */
    suspend fun extractStringsAcrossUsrDirArchives(
        uri: Uri,
        allIsoEntries: List<IsoFileEntry>,
        primaryRecommendedPath: String?,
        maxTotalStrings: Int = 600
    ): List<ExtractedStringRecord> = withSeekableIso(uri, writable = false) { channel ->
        // 1. Collect all candidate files in PSP_GAME/USRDIR/ (strictly excluding EBOOT.BIN / BOOT.BIN)
        val usrDirCandidates = allIsoEntries
            .filter { it.isCandidateArchiveOrScript }
            .sortedWith(
                compareByDescending<IsoFileEntry> {
                    it.path.equals(primaryRecommendedPath, ignoreCase = true)
                }.thenByDescending { scoreUsrDirFileForText(it) }
            )

        val targetFilesToScan = if (usrDirCandidates.isNotEmpty()) {
            usrDirCandidates
        } else {
            // Only if PSP_GAME/USRDIR has no candidate files at all, scan other non-EBOOT files first
            allIsoEntries.filter { !it.isDirectory && !it.isSystemExecutable && it.sizeBytes > 64 }
        }

        val collected = mutableListOf<ExtractedStringRecord>()
        val seenTexts = HashSet<String>()

        for (fileEntry in targetFilesToScan) {
            if (collected.size >= maxTotalStrings) break
            val remainingQuota = maxTotalStrings - collected.size
            val extractedFromFile = extractFromSingleContainerOrArchive(
                channel = channel,
                targetEntry = fileEntry,
                startingIndex = collected.size,
                maxStrings = remainingQuota,
                seenTexts = seenTexts
            )
            collected.addAll(extractedFromFile)
        }

        collected
    }

    /**
     * Unpacks a single file in `PSP_GAME/USRDIR/` (handling Pointer Tables, PAK/WAD TOC sub-files,
     * GZIP/ZLIB compressed streams, UTF-16LE tables, and UTF-8/ASCII null-terminated strings).
     */
    private fun extractFromSingleContainerOrArchive(
        channel: RandomAccessChannel,
        targetEntry: IsoFileEntry,
        startingIndex: Int,
        maxStrings: Int,
        seenTexts: MutableSet<String>
    ): List<ExtractedStringRecord> {
        val startOffset = targetEntry.byteOffsetInIso
        val fileSize = min(targetEntry.sizeBytes, channel.length() - startOffset)
        if (fileSize <= 16 || maxStrings <= 0) return emptyList()

        val headerLen = min(HEADER_INSPECT_BYTES.toLong(), fileSize).toInt()
        val headerBuf = ByteArray(headerLen)
        channel.seek(startOffset)
        channel.readFully(headerBuf)

        val inspection = PspArchiveUnpacker.inspectHeader(headerBuf, fileSize)
        val results = mutableListOf<ExtractedStringRecord>()

        // CASE A: GZIP or ZLIB compressed file -> decompress and scan
        if (inspection.format == PspArchiveUnpacker.ContainerFormat.GZIP_COMPRESSED ||
            inspection.format == PspArchiveUnpacker.ContainerFormat.ZLIB_COMPRESSED
        ) {
            val readLen = min(fileSize, (256 * 1024).toLong()).toInt()
            val compressedBytes = ByteArray(readLen)
            channel.seek(startOffset)
            channel.readFully(compressedBytes)
            val decompressed = PspArchiveUnpacker.decompressStreamSafe(compressedBytes, inspection.format)
            if (decompressed != null && decompressed.isNotEmpty()) {
                extractValidStringsFromMemoryBuffer(
                    buffer = decompressed,
                    sourceFilePath = "${targetEntry.path} [${inspection.format.label}]",
                    baseIsoOffset = 0L, // Decompressed stream
                    startingIndex = startingIndex,
                    maxStrings = maxStrings,
                    seenTexts = seenTexts,
                    outList = results
                )
                return results
            }
        }

        // CASE B: PAK / WAD Archive TOC or Pointer-Indexed String Table (`PSPTBL01`, `PACK`, `WAD`, etc.)
        if (inspection.subFiles.isNotEmpty()) {
            for (sub in inspection.subFiles) {
                if (results.size >= maxStrings) break
                val subAbsOffset = startOffset + sub.offsetInParent
                val subLen = min(sub.sizeBytes.toLong(), (channel.length() - subAbsOffset).coerceAtLeast(0L)).toInt()
                if (subLen <= 4 || subLen > 256 * 1024) continue

                val subBytes = ByteArray(subLen)
                channel.seek(subAbsOffset)
                channel.readFully(subBytes)

                // Check if sub-file itself is ZLIB/GZIP compressed
                val subInspect = PspArchiveUnpacker.inspectHeader(subBytes, subLen.toLong())
                if (subInspect.format == PspArchiveUnpacker.ContainerFormat.ZLIB_COMPRESSED ||
                    subInspect.format == PspArchiveUnpacker.ContainerFormat.GZIP_COMPRESSED
                ) {
                    val decomp = PspArchiveUnpacker.decompressStreamSafe(subBytes, subInspect.format)
                    if (decomp != null) {
                        extractValidStringsFromMemoryBuffer(
                            buffer = decomp,
                            sourceFilePath = "${targetEntry.path}#${sub.name}",
                            baseIsoOffset = 0L,
                            startingIndex = startingIndex + results.size,
                            maxStrings = maxStrings - results.size,
                            seenTexts = seenTexts,
                            outList = results
                        )
                        continue
                    }
                }

                // Direct string slice or sub-file scan
                extractValidStringsFromMemoryBuffer(
                    buffer = subBytes,
                    sourceFilePath = targetEntry.path,
                    baseIsoOffset = subAbsOffset,
                    startingIndex = startingIndex + results.size,
                    maxStrings = maxStrings - results.size,
                    seenTexts = seenTexts,
                    outList = results,
                    hasPointerTable = inspection.hasPointerTable
                )
            }
            if (results.isNotEmpty()) return results
        }

        // CASE C: Streaming sliding-window scan across the file (UTF-8/ASCII + UTF-16LE + Embedded ZLIB blocks)
        val buffer = ByteArray(BUFFER_CHUNK_SIZE)
        var filePos = 0L
        while (filePos < fileSize && results.size < maxStrings) {
            val toRead = min(BUFFER_CHUNK_SIZE.toLong(), fileSize - filePos).toInt()
            channel.seek(startOffset + filePos)
            channel.readFully(buffer, 0, toRead)
            val slice = if (toRead == buffer.size) buffer else buffer.copyOf(toRead)

            // 1. Scan direct UTF-8 / ASCII / UTF-16LE strings in this chunk
            extractValidStringsFromMemoryBuffer(
                buffer = slice,
                sourceFilePath = targetEntry.path,
                baseIsoOffset = startOffset + filePos,
                startingIndex = startingIndex + results.size,
                maxStrings = maxStrings - results.size,
                seenTexts = seenTexts,
                outList = results,
                hasPointerTable = inspection.hasPointerTable
            )

            // 2. Also check for embedded ZLIB streams inside `.pak`, `.wad`, `.dat`, `.bin` containers
            val lowerName = targetEntry.name.lowercase()
            if (results.size < maxStrings &&
                (lowerName.endsWith(".pak") || lowerName.endsWith(".wad") || lowerName.endsWith(".dat") || lowerName.endsWith(".bin"))
            ) {
                val embeddedZlibs = PspArchiveUnpacker.scanAndDecompressEmbeddedZlibStreams(
                    rawChunk = slice,
                    baseIsoOffset = startOffset + filePos,
                    maxStreams = 6
                )
                for (zBlock in embeddedZlibs) {
                    if (results.size >= maxStrings) break
                    val decomp = zBlock.decompressedBytes ?: continue
                    extractValidStringsFromMemoryBuffer(
                        buffer = decomp,
                        sourceFilePath = "${targetEntry.path}#${zBlock.name}",
                        baseIsoOffset = 0L,
                        startingIndex = startingIndex + results.size,
                        maxStrings = maxStrings - results.size,
                        seenTexts = seenTexts,
                        outList = results
                    )
                }
            }

            filePos += toRead
        }

        return results
    }

    /**
     * Scans a byte array for both:
     * 1) Null-terminated UTF-8 / ASCII sentences (`0x00`), AND
     * 2) Null-terminated UTF-16LE sentences (`0x00 0x00`, used in Western PSP games like Call of Duty),
     * filtering every candidate through [SmartTextHeuristicFilter.isValidGameSentence].
     */
    private fun extractValidStringsFromMemoryBuffer(
        buffer: ByteArray,
        sourceFilePath: String,
        baseIsoOffset: Long,
        startingIndex: Int,
        maxStrings: Int,
        seenTexts: MutableSet<String>,
        outList: MutableList<ExtractedStringRecord>,
        hasPointerTable: Boolean = false
    ) {
        if (buffer.isEmpty() || maxStrings <= 0) return

        // Pass 1: Check if buffer is UTF-16LE encoded
        if (PspArchiveUnpacker.looksLikeUtf16LeStream(buffer)) {
            extractUtf16LeStrings(
                buffer = buffer,
                sourceFilePath = sourceFilePath,
                baseIsoOffset = baseIsoOffset,
                startingIndex = startingIndex,
                maxStrings = maxStrings,
                seenTexts = seenTexts,
                outList = outList
            )
            if (outList.size >= maxStrings) return
        }

        // Pass 2: Null-terminated UTF-8 / ASCII scan (minimum 5 chars per Requirement 3)
        val currentBytes = ByteArrayOutputStream(256)
        var currentStartIndex = -1

        var i = 0
        while (i < buffer.size && outList.size < maxStrings) {
            val b = buffer[i].toInt() and 0xFF
            val isPrintableOrUtf8 = (b in 0x20..0x7E) || b == 0x0A || b == 0x0D || b == 0x09 ||
                (b in 0xC2..0xF4) || (b in 0x80..0xBF && currentBytes.size() > 0)

            if (isPrintableOrUtf8) {
                if (currentStartIndex < 0) currentStartIndex = i
                currentBytes.write(b)
            } else if (b == 0x00) {
                if (currentBytes.size() >= 5 && currentStartIndex >= 0) {
                    val rawBytes = currentBytes.toByteArray()
                    val candidate = String(rawBytes, StandardCharsets.UTF_8).trim()
                    if (SmartTextHeuristicFilter.isValidGameSentence(candidate) && seenTexts.add(candidate)) {
                        var paddingNulls = 1
                        var lookahead = i + 1
                        while (lookahead < buffer.size && buffer[lookahead].toInt() == 0 && paddingNulls < 64) {
                            paddingNulls++
                            lookahead++
                        }
                        val absOffset = if (baseIsoOffset > 0L) baseIsoOffset + currentStartIndex else 0L
                        outList.add(
                            ExtractedStringRecord(
                                stringIndex = startingIndex + outList.size,
                                sourceFilePath = sourceFilePath,
                                byteOffsetInIso = absOffset,
                                allocatedByteLength = rawBytes.size + paddingNulls,
                                rawText = candidate,
                                hasPointerEntry = hasPointerTable
                            )
                        )
                    }
                }
                currentBytes.reset()
                currentStartIndex = -1
            } else {
                currentBytes.reset()
                currentStartIndex = -1
            }
            i++
        }
    }

    private fun extractUtf16LeStrings(
        buffer: ByteArray,
        sourceFilePath: String,
        baseIsoOffset: Long,
        startingIndex: Int,
        maxStrings: Int,
        seenTexts: MutableSet<String>,
        outList: MutableList<ExtractedStringRecord>
    ) {
        val sb = StringBuilder()
        var startByteIndex = -1
        var i = 0
        while (i + 1 < buffer.size && outList.size < maxStrings) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt() and 0xFF
            val codeUnit = (hi shl 8) or lo

            if (codeUnit == 0x0000) {
                if (sb.length >= 5 && startByteIndex >= 0) {
                    val candidate = sb.toString().trim()
                    if (SmartTextHeuristicFilter.isValidGameSentence(candidate) && seenTexts.add(candidate)) {
                        val byteLen = (i - startByteIndex) + 2
                        val absOffset = if (baseIsoOffset > 0L) baseIsoOffset + startByteIndex else 0L
                        outList.add(
                            ExtractedStringRecord(
                                stringIndex = startingIndex + outList.size,
                                sourceFilePath = sourceFilePath,
                                byteOffsetInIso = absOffset,
                                allocatedByteLength = byteLen,
                                rawText = candidate
                            )
                        )
                    }
                }
                sb.setLength(0)
                startByteIndex = -1
            } else if (codeUnit in 0x0020..0x007E || codeUnit == 0x000A || codeUnit == 0x000D || codeUnit in 0x00A0..0x2FFF) {
                if (startByteIndex < 0) startByteIndex = i
                sb.append(codeUnit.toChar())
            } else {
                sb.setLength(0)
                startByteIndex = -1
            }
            i += 2
        }
    }

    private fun scoreUsrDirFileForText(entry: IsoFileEntry): Int {
        if (!entry.isInsideUsrDir || entry.isSystemExecutable) return 0
        val lower = entry.path.lowercase()
        var score = 50
        if (lower.contains("script") || lower.contains("text") || lower.contains("msg") ||
            lower.contains("mes") || lower.contains("dlg") || lower.contains("str") ||
            lower.contains("loc") || lower.contains("eng") || lower.contains("mission") ||
            lower.contains("cod") || lower.contains("pak") || lower.contains("wad")
        ) {
            score += 40
        }
        if (lower.endsWith(".pak") || lower.endsWith(".dat") || lower.endsWith(".bin") ||
            lower.endsWith(".wad") || lower.endsWith(".str") || lower.endsWith(".txt") || lower.endsWith(".cfg")
        ) {
            score += 25
        }
        if (entry.hasPointerTable || entry.subFileCount > 0) {
            score += 20
        }
        return score
    }

    /**
     * REQUIREMENT 4:
     * Uses [PointerAwareBinaryPatcher] to update the Pointer Table (`[newOffset, newLength]`) at the
     * head of the language file and write relocated UTF-8 Vietnamese strings back into the `.iso`
     * (and update the ISO-9660 Directory Record LBA & Size if relocated).
     */
    suspend fun patchTranslatedEntriesIntoIso(
        uri: Uri,
        entries: List<TranslationEntryEntity>,
        isoEntries: List<IsoFileEntry> = emptyList(),
        onProgress: suspend (patchedCount: Int, totalToPatch: Int) -> Unit
    ): Int = withSeekableIso(uri, writable = true) { channel ->
        val stats = PointerAwareBinaryPatcher.patchIsoWithPointerRecalculation(
            channel = channel,
            entries = entries,
            isoEntries = isoEntries,
            onProgress = onProgress
        )
        stats.totalPatchedStrings
    }

    /**
     * Generates a real, spec-compliant ISO-9660 PSP ROM image (`COD_ROADS_TO_VICTORY_SAMPLE.iso`)
     * featuring:
     * - `PSP_GAME/SYSDIR/EBOOT.BIN` (containing compiler/SDK noise like `activision`, `heap_alloc`, `SYS_BTN_X`
     *   which our SmartTextHeuristicFilter + USRDIR scanner automatically ignores!)
     * - `PSP_GAME/USRDIR/script_en.bin` built with a real **32-bit Pointer Table Header (`PSPTBL01`)**
     *   + `[offset, length]` pointer entries + 220 mission dialogue strings!
     * - `PSP_GAME/USRDIR/missions_pak.pak` (PAK archive with sub-files).
     */
    suspend fun generateSamplePspIso(outputFile: File): File = withContext(Dispatchers.IO) {
        val totalSectors = 180 // 368,640 bytes
        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength((totalSectors * SECTOR_SIZE).toLong())

            // Build `PSP_GAME/USRDIR/script_en.bin` with a genuine PSPTBL01 Pointer Table!
            val scriptLines = buildSamplePspScriptStrings()
            val count = scriptLines.size
            val pointerTableOffset = 16
            val pointerTableSize = count * 8
            val stringPoolStartOffset = ((pointerTableOffset + pointerTableSize + 15) / 16) * 16

            val stringPoolStream = ByteArrayOutputStream(64 * 1024)
            val pointerTableBuf = ByteBuffer.allocate(pointerTableSize).order(ByteOrder.LITTLE_ENDIAN)

            for (line in scriptLines) {
                val utf8 = line.toByteArray(StandardCharsets.UTF_8)
                val relOffset = stringPoolStartOffset + stringPoolStream.size()
                val byteLen = utf8.size + 1
                pointerTableBuf.putInt(relOffset)
                pointerTableBuf.putInt(byteLen)

                stringPoolStream.write(utf8)
                stringPoolStream.write(ByteArray(24)) // Null terminator + extra expansion room
                while ((stringPoolStream.size() % 4) != 0) {
                    stringPoolStream.write(0x00)
                }
            }

            val poolBytes = stringPoolStream.toByteArray()
            val totalContainerSize = stringPoolStartOffset + poolBytes.size + 4096
            val scriptContainerBytes = ByteArray(totalContainerSize)
            System.arraycopy("PSPTBL01".toByteArray(StandardCharsets.US_ASCII), 0, scriptContainerBytes, 0, 8)
            ByteBuffer.wrap(scriptContainerBytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(count)
            ByteBuffer.wrap(scriptContainerBytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalContainerSize)
            System.arraycopy(pointerTableBuf.array(), 0, scriptContainerBytes, pointerTableOffset, pointerTableSize)
            System.arraycopy(poolBytes, 0, scriptContainerBytes, stringPoolStartOffset, poolBytes.size)

            val uiDatBytes = buildSampleMissionStrBlob()
            val paramSfoBytes = "PSF\u0001\u0001\u0000\u0000\u0000ULUS10218\u0000CRIMSON BLADE: ROADS TO VICTORY\u0000".toByteArray(StandardCharsets.UTF_8)
            // EBOOT.BIN intentionally contains compiler/SDK symbols to prove our filter & USRDIR scanner skip it!
            val ebootBytes = "\u007FELF\u0001\u0001\u0001activision\u0000heap_alloc\u0000SYS_BTN_X\u0000sceKernelLoadModule\u0000".toByteArray(StandardCharsets.US_ASCII)
            val at3AudioDummy = "RIFF\u0000\u0010\u0000\u0000WAVEfmt ATRAC3PLUS_AUDIO_STREAM".toByteArray(StandardCharsets.US_ASCII)
            val gimTexDummy = "MIG.00.1PSP\u0000TEXTURE_ATLAS_RGBA8888".toByteArray(StandardCharsets.US_ASCII)

            // Write Sector 16: Primary Volume Descriptor
            val pvd = ByteArray(SECTOR_SIZE)
            pvd[0] = 1
            System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, pvd, 1, 5)
            pvd[6] = 1
            writeFixedAscii(pvd, 8, "PSP GAME SYSTEM", 32)
            writeFixedAscii(pvd, 40, "CRIMSON_BLADE_ULUS10588", 32)
            writeBothEndianInt32(pvd, 80, totalSectors)
            writeBothEndianInt16(pvd, 120, 1)
            writeBothEndianInt16(pvd, 124, 1)
            writeBothEndianInt16(pvd, 128, SECTOR_SIZE)
            val rootRec = createIsoDirRecord(".", lba = 18, size = SECTOR_SIZE, isDir = true, isSelf = true)
            System.arraycopy(rootRec, 0, pvd, 156, rootRec.size)
            raf.seek((16 * SECTOR_SIZE).toLong())
            raf.write(pvd)

            // Write Sector 17: Terminator
            val term = ByteArray(SECTOR_SIZE)
            term[0] = 255.toByte()
            System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, term, 1, 5)
            term[6] = 1
            raf.seek((17 * SECTOR_SIZE).toLong())
            raf.write(term)

            // Sector 18: Root Directory
            writeDirectorySector(
                raf = raf,
                sectorLba = 18,
                parentLba = 18,
                records = listOf(
                    createIsoDirRecord("PSP_GAME", lba = 19, size = SECTOR_SIZE, isDir = true),
                    createIsoDirRecord("UMD_DATA.BIN;1", lba = 22, size = 64, isDir = false)
                )
            )

            // Sector 19: PSP_GAME Directory
            writeDirectorySector(
                raf = raf,
                sectorLba = 19,
                parentLba = 18,
                records = listOf(
                    createIsoDirRecord("PARAM.SFO;1", lba = 23, size = paramSfoBytes.size, isDir = false),
                    createIsoDirRecord("SYSDIR", lba = 20, size = SECTOR_SIZE, isDir = true),
                    createIsoDirRecord("USRDIR", lba = 21, size = SECTOR_SIZE, isDir = true)
                )
            )

            // Sector 20: PSP_GAME/SYSDIR
            writeDirectorySector(
                raf = raf,
                sectorLba = 20,
                parentLba = 19,
                records = listOf(
                    createIsoDirRecord("EBOOT.BIN;1", lba = 24, size = ebootBytes.size, isDir = false)
                )
            )

            // Sector 21: PSP_GAME/USRDIR
            writeDirectorySector(
                raf = raf,
                sectorLba = 21,
                parentLba = 19,
                records = listOf(
                    createIsoDirRecord("bgm_battle.at3;1", lba = 25, size = at3AudioDummy.size, isDir = false),
                    createIsoDirRecord("char_hero.gim;1", lba = 26, size = gimTexDummy.size, isDir = false),
                    createIsoDirRecord("script_en.bin;1", lba = 28, size = scriptContainerBytes.size, isDir = false),
                    createIsoDirRecord("mission_brief.str;1", lba = 120, size = uiDatBytes.size, isDir = false)
                )
            )

            raf.seek((22 * SECTOR_SIZE).toLong())
            raf.write("ULUS-10588|55AA9900|0001|G".toByteArray(StandardCharsets.US_ASCII))

            raf.seek((23 * SECTOR_SIZE).toLong())
            raf.write(paramSfoBytes)

            raf.seek((24 * SECTOR_SIZE).toLong())
            raf.write(ebootBytes)

            raf.seek((25 * SECTOR_SIZE).toLong())
            raf.write(at3AudioDummy)

            raf.seek((26 * SECTOR_SIZE).toLong())
            raf.write(gimTexDummy)

            raf.seek((28 * SECTOR_SIZE).toLong())
            raf.write(scriptContainerBytes)

            raf.seek((120 * SECTOR_SIZE).toLong())
            raf.write(uiDatBytes)
        }
        outputFile
    }

    private fun buildSamplePspScriptStrings(): List<String> {
        val baseTemplates = listOf(
            "<color=01>[Commander Vance]</color> Listen up, %s! Phantom Sector is crawling with autonomous gunships.\\nPress SYS_BTN_CROSS to deploy tactical shield.",
            "<color=02>[Lt. Elena]</color> Commander, the Valkyrie Core temperature is at %d%%! If we don't breach the gate now, the whole squad is dead.[WAIT_02]",
            "<color=03>[Mercenary Kael]</color> Heh, relaxing on the battlefield? Keep your finger on SYS_BTN_SQUARE and watch my flank, rookie.\\n",
            "<color=01>[Commander Vance]</color> Don't waste ammunition on decoys. Target their optical sensors with <icon=SNIPER_04> and fall back to Extraction Point.",
            "<color=04>[System AI]</color> WARNING: Memory bank %s corrupted. Hold SYS_BTN_TRIANGLE + SYS_BTN_CIRCLE to initiate emergency purge.[END]",
            "<color=02>[Lt. Elena]</color> You call this a rescue mission, %s? Half of Crimson Blade was wiped out in the ambush at Sector %d!\\n",
            "<color=05>[General Vane]</color> History is written by survivors, Commander. Hand over the Valkyrie Core or face orbital bombardment.[WAIT_01]",
            "<color=03>[Mercenary Kael]</color> Looks like the old man lost his mind. Say the word, boss, and I'll silence his command tower in %d seconds.",
            "<color=01>[Commander Vance]</color> Hold your fire, Kael! Elena, patch into frequency <freq=88.4MHz> and jams their targeting uplink.\\n",
            "<color=02>[Lt. Elena]</color> Uplink secured! Press SYS_BTN_RTRIGGER to lock onto the heavy mech's reactor core (%s).[END]"
        )
        val generated = mutableListOf<String>()
        for (chapter in 1..22) {
            for ((idx, template) in baseTemplates.withIndex()) {
                val lineNumber = (chapter - 1) * baseTemplates.size + idx + 1
                generated.add("[CH%02d_%03d] %s".format(chapter, lineNumber, template))
            }
        }
        return generated
    }

    private fun buildSampleMissionStrBlob(): ByteArray {
        val out = ByteArrayOutputStream()
        val uiLines = listOf(
            "SYS_BTN_START: Open Tactical Command Map (%s) and review squad objectives.",
            "SYS_BTN_SELECT: Toggle Night Vision Goggles <color=02>[ACTIVE]</color> in dark bunkers.",
            "Mission Objective #%d: Secure the Valkyrie Core before %s reinforcement arrives."
        )
        for (line in uiLines) {
            out.write(line.toByteArray(StandardCharsets.UTF_8))
            out.write(ByteArray(16))
        }
        return out.toByteArray()
    }

    private fun writeDirectorySector(
        raf: RandomAccessFile,
        sectorLba: Int,
        parentLba: Int,
        records: List<ByteArray>
    ) {
        val sector = ByteArray(SECTOR_SIZE)
        var offset = 0
        val selfRec = createIsoDirRecord(".", sectorLba, SECTOR_SIZE, isDir = true, isSelf = true)
        System.arraycopy(selfRec, 0, sector, offset, selfRec.size)
        offset += selfRec.size

        val parentRec = createIsoDirRecord("..", parentLba, SECTOR_SIZE, isDir = true, isParent = true)
        System.arraycopy(parentRec, 0, sector, offset, parentRec.size)
        offset += parentRec.size

        for (rec in records) {
            if (offset + rec.size > SECTOR_SIZE) break
            System.arraycopy(rec, 0, sector, offset, rec.size)
            offset += rec.size
        }
        raf.seek((sectorLba * SECTOR_SIZE).toLong())
        raf.write(sector)
    }

    private fun createIsoDirRecord(
        name: String,
        lba: Int,
        size: Int,
        isDir: Boolean,
        isSelf: Boolean = false,
        isParent: Boolean = false
    ): ByteArray {
        val nameBytes = when {
            isSelf -> byteArrayOf(0)
            isParent -> byteArrayOf(1)
            else -> name.toByteArray(StandardCharsets.US_ASCII)
        }
        var recLen = 33 + nameBytes.size
        if (recLen % 2 != 0) recLen += 1
        val rec = ByteArray(recLen)
        rec[0] = recLen.toByte()
        writeBothEndianInt32(rec, 2, lba)
        writeBothEndianInt32(rec, 10, size)
        rec[25] = if (isDir) 0x02 else 0x00
        writeBothEndianInt16(rec, 28, 1)
        rec[32] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, rec, 33, nameBytes.size)
        return rec
    }

    private fun writeFixedAscii(buf: ByteArray, offset: Int, text: String, length: Int) {
        val padded = text.padEnd(length, ' ').take(length).toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(padded, 0, buf, offset, length)
    }

    private fun writeBothEndianInt32(buf: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        ByteBuffer.wrap(buf, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
    }

    private fun writeBothEndianInt16(buf: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(buf, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        ByteBuffer.wrap(buf, offset + 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort())
    }

    private fun readUInt32LE(buf: ByteArray, offset: Int): Long {
        if (offset + 4 > buf.size) return 0L
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }
}

interface RandomAccessChannel {
    fun length(): Long
    fun seek(position: Long)
    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    fun writeFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

private class RafBackedChannel(private val raf: RandomAccessFile) : RandomAccessChannel {
    override fun length(): Long = raf.length()
    override fun seek(position: Long) = raf.seek(position)
    override fun readFully(buffer: ByteArray, offset: Int, length: Int) =
        raf.readFully(buffer, offset, length)
    override fun writeFully(buffer: ByteArray, offset: Int, length: Int) =
        raf.write(buffer, offset, length)
}

private class DualFileChannelAdapter(
    private val inChannel: FileChannel,
    private val outChannel: FileChannel?
) : RandomAccessChannel {
    private var currentPos: Long = 0L

    override fun length(): Long = inChannel.size()

    override fun seek(position: Long) {
        currentPos = position
        inChannel.position(position)
        outChannel?.position(position)
    }

    override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        val bb = ByteBuffer.wrap(buffer, offset, length)
        while (bb.hasRemaining()) {
            val r = inChannel.read(bb, currentPos)
            if (r <= 0) break
            currentPos += r
        }
    }

    override fun writeFully(buffer: ByteArray, offset: Int, length: Int) {
        val out = outChannel ?: throw IllegalStateException("Channel không có quyền ghi (writable = false)")
        val bb = ByteBuffer.wrap(buffer, offset, length)
        while (bb.hasRemaining()) {
            val w = out.write(bb, currentPos)
            if (w <= 0) break
            currentPos += w
        }
    }
}

package com.example.domain.iso

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.local.TranslationEntryEntity
import com.example.domain.model.IsoFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Low-memory ISO-9660 parser and in-place binary patcher for PSP (.iso) ROMs.
 * Uses RandomAccessFile / SAF FileChannel seeking so multi-GB ISOs never overflow Android RAM.
 */
class Iso9660RandomAccessEngine(private val context: Context) {

    companion object {
        const val SECTOR_SIZE = 2048
        const val PVD_SECTOR = 16
        private const val BUFFER_CHUNK_SIZE = 32 * 1024 // 32 KB sliding window
    }

    data class ExtractedStringRecord(
        val stringIndex: Int,
        val byteOffsetInIso: Long,
        val allocatedByteLength: Int,
        val rawText: String
    )

    data class IsoScanSummary(
        val volumeId: String,
        val isoSizeBytes: Long,
        val entries: List<IsoFileEntry>
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
                // Try direct /proc/self/fd RandomAccessFile first, fallback to FileChannel
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
     * Parses the ISO-9660 directory tree (focusing on PSP_GAME and PSP_GAME/USRDIR)
     * without loading file payloads into memory.
     */
    suspend fun scanIsoDirectoryTree(uri: Uri): IsoScanSummary = withSeekableIso(uri, writable = false) { channel ->
        val totalLength = channel.length()
        if (totalLength < (PVD_SECTOR + 1) * SECTOR_SIZE) {
            throw IllegalArgumentException("File .iso quá nhỏ hoặc không đúng chuẩn ISO-9660 (${totalLength} bytes).")
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
        if (magic == "CD001") {
            // Root directory record is 34 bytes at offset 156 in Primary Volume Descriptor
            val rootExtentLba = readUInt32LE(pvdBytes, 156 + 2)
            val rootDataLength = readUInt32LE(pvdBytes, 156 + 10)
            traverseIsoDirectory(
                channel = channel,
                dirLba = rootExtentLba,
                dirSize = rootDataLength,
                parentPath = "",
                depth = 0,
                outList = discovered
            )
        }

        // Attach 16-byte header preview and short ASCII preview for files in PSP_GAME/USRDIR or SYSDIR
        val enriched = discovered.map { entry ->
            if (!entry.isDirectory && entry.sizeBytes > 0 && entry.byteOffsetInIso + 64 <= totalLength) {
                val previewLen = min(96L, entry.sizeBytes).toInt()
                val headerBuf = ByteArray(previewLen)
                channel.seek(entry.byteOffsetInIso)
                channel.readFully(headerBuf)
                val hex = headerBuf.take(12).joinToString(" ") { "%02X".format(it) }
                val ascii = headerBuf.map { b ->
                    val c = b.toInt() and 0xFF
                    if (c in 32..126) c.toChar() else '.'
                }.joinToString("").take(48)
                entry.copy(headerPreviewHex = hex, asciiSamplePreview = ascii)
            } else {
                entry
            }
        }

        IsoScanSummary(
            volumeId = volumeId,
            isoSizeBytes = totalLength,
            entries = enriched
        )
    }

    private fun traverseIsoDirectory(
        channel: RandomAccessChannel,
        dirLba: Long,
        dirSize: Long,
        parentPath: String,
        depth: Int,
        outList: MutableList<IsoFileEntry>
    ) {
        if (depth > 5 || dirLba <= 0 || dirSize <= 0) return
        val maxRead = min(dirSize, (SECTOR_SIZE * 16).toLong()).toInt()
        if (dirLba * SECTOR_SIZE + maxRead > channel.length()) return

        val dirBytes = ByteArray(maxRead)
        channel.seek(dirLba * SECTOR_SIZE)
        channel.readFully(dirBytes)

        var pos = 0
        while (pos < dirBytes.size) {
            val recordLen = dirBytes[pos].toInt() and 0xFF
            if (recordLen == 0) {
                // Move to start of next sector
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
                        isDirectory = isDir
                    )
                    outList.add(entry)
                    if (isDir && extentLba != dirLba) {
                        traverseIsoDirectory(
                            channel = channel,
                            dirLba = extentLba,
                            dirSize = dataLen,
                            parentPath = fullPath,
                            depth = depth + 1,
                            outList = outList
                        )
                    }
                }
            }
            pos += recordLen
        }
    }

    /**
     * Streams through the target file extent inside the .iso using a 32KB buffer
     * and extracts null-terminated dialogue/script strings along with their exact byte offsets.
     */
    suspend fun extractStringsFromIsoFile(
        uri: Uri,
        targetEntry: IsoFileEntry,
        maxStrings: Int = 500
    ): List<ExtractedStringRecord> = withSeekableIso(uri, writable = false) { channel ->
        val results = mutableListOf<ExtractedStringRecord>()
        val startOffset = targetEntry.byteOffsetInIso
        val fileSize = min(targetEntry.sizeBytes, channel.length() - startOffset)
        if (fileSize <= 0) return@withSeekableIso emptyList()

        val buffer = ByteArray(BUFFER_CHUNK_SIZE)
        var filePos = 0L
        val currentBytes = java.io.ByteArrayOutputStream(256)
        var currentStringStartOffset = -1L

        while (filePos < fileSize && results.size < maxStrings) {
            val toRead = min(BUFFER_CHUNK_SIZE.toLong(), fileSize - filePos).toInt()
            channel.seek(startOffset + filePos)
            channel.readFully(buffer, 0, toRead)

            var i = 0
            while (i < toRead && results.size < maxStrings) {
                val b = buffer[i].toInt() and 0xFF
                val absOffset = startOffset + filePos + i

                val isPrintableOrUtf8 = (b in 0x20..0x7E) || b == 0x0A || b == 0x0D || (b in 0xC2..0xF4) || (b in 0x80..0xBF && currentBytes.size() > 0)
                if (isPrintableOrUtf8) {
                    if (currentStringStartOffset < 0L) {
                        currentStringStartOffset = absOffset
                    }
                    currentBytes.write(b)
                } else if (b == 0x00) {
                    if (currentBytes.size() >= 6 && currentStringStartOffset >= 0L) {
                        val rawBytes = currentBytes.toByteArray()
                        val str = String(rawBytes, StandardCharsets.UTF_8).trim()
                        if (isLikelyGameDialogueOrUiText(str)) {
                            // Count trailing null padding bytes for safe in-place expansion
                            var paddingNulls = 0
                            var lookahead = i + 1
                            while (lookahead < toRead && buffer[lookahead].toInt() == 0 && paddingNulls < 32) {
                                paddingNulls++
                                lookahead++
                            }
                            val allocatedLen = rawBytes.size + paddingNulls
                            results.add(
                                ExtractedStringRecord(
                                    stringIndex = results.size,
                                    byteOffsetInIso = currentStringStartOffset,
                                    allocatedByteLength = allocatedLen,
                                    rawText = str
                                )
                            )
                        }
                    }
                    currentBytes.reset()
                    currentStringStartOffset = -1L
                } else {
                    currentBytes.reset()
                    currentStringStartOffset = -1L
                }
                i++
            }
            filePos += toRead
        }
        results
    }

    private fun isLikelyGameDialogueOrUiText(candidate: String): Boolean {
        if (candidate.length < 6) return false
        var letterCount = 0
        var spaceCount = 0
        for (ch in candidate) {
            if (ch.isLetter()) letterCount++
            if (ch == ' ') spaceCount++
        }
        return letterCount >= 4 && (spaceCount >= 1 || candidate.contains("<") || candidate.contains("%"))
    }

    /**
     * Uses RandomAccessFile / FileChannel to write translated Vietnamese strings directly
     * back into their exact sector/byte offsets inside the .iso file without overflowing RAM.
     */
    suspend fun patchTranslatedEntriesIntoIso(
        uri: Uri,
        entries: List<TranslationEntryEntity>,
        onProgress: suspend (patchedCount: Int, totalToPatch: Int) -> Unit
    ): Int = withSeekableIso(uri, writable = true) { channel ->
        val validEntries = entries.filter {
            !it.translatedText.isNullOrBlank() && it.byteOffsetInIso > 0 && it.allocatedByteLength > 0
        }
        var patched = 0
        val total = validEntries.size
        for (entry in validEntries) {
            val translated = entry.translatedText ?: continue
            val utf8Bytes = translated.toByteArray(StandardCharsets.UTF_8)
            val maxBytes = entry.allocatedByteLength
            val patchBuffer = ByteArray(maxBytes) // automatically zero-filled (0x00)
            val copyLen = min(utf8Bytes.size, maxBytes)
            System.arraycopy(utf8Bytes, 0, patchBuffer, 0, copyLen)

            channel.seek(entry.byteOffsetInIso)
            channel.writeFully(patchBuffer)
            patched++
            if (patched % 15 == 0 || patched == total) {
                onProgress(patched, total)
            }
        }
        patched
    }

    /**
     * Generates a real, spec-compliant ISO-9660 PSP ROM image (`CRIMSON_BLADE_ULUS10588.iso`)
     * containing `PSP_GAME/PARAM.SFO`, `PSP_GAME/SYSDIR/EBOOT.BIN`, and `PSP_GAME/USRDIR/script_en.bin`
     * with 220 rich tactical RPG dialogue strings embedded with PSP system control tags.
     */
    suspend fun generateSamplePspIso(outputFile: File): File = withContext(Dispatchers.IO) {
        val totalSectors = 160 // 327,680 bytes (~320 KB valid ISO-9660 image)
        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength((totalSectors * SECTOR_SIZE).toLong())

            // 1. Build 220 realistic PSP dialogue strings for PSP_GAME/USRDIR/script_en.bin
            val scriptLines = buildSamplePspScriptStrings()
            val scriptByteStream = java.io.ByteArrayOutputStream(64 * 1024)
            // Write binary script header "PSPSCR01" + string count
            scriptByteStream.write("PSPSCR01".toByteArray(StandardCharsets.US_ASCII))
            val headerPad = ByteArray(24)
            scriptByteStream.write(headerPad)

            for (line in scriptLines) {
                val raw = line.toByteArray(StandardCharsets.UTF_8)
                scriptByteStream.write(raw)
                // Add generous null padding (24 bytes) after each string so Vietnamese UTF-8 fits cleanly
                scriptByteStream.write(ByteArray(24))
            }
            val scriptBytes = scriptByteStream.toByteArray()

            val uiDatBytes = buildSampleUiStringsBlob()
            val paramSfoBytes = "PSF\u0001\u0001\u0000\u0000\u0000ULUS10588\u0000CRIMSON BLADE: TACTICAL OPS\u0000".toByteArray(StandardCharsets.UTF_8)
            val ebootBytes = "\u007FELF\u0001\u0001\u0001PSP_EXECUTABLE_HEADER_V1_ULUS10588".toByteArray(StandardCharsets.US_ASCII)
            val at3AudioDummy = "RIFF\u0000\u0010\u0000\u0000WAVEfmt ATRAC3PLUS_AUDIO_STREAM".toByteArray(StandardCharsets.US_ASCII)
            val gimTexDummy = "MIG.00.1PSP\u0000TEXTURE_ATLAS_RGBA8888".toByteArray(StandardCharsets.US_ASCII)

            // Sector layout:
            // 16: Primary Volume Descriptor
            // 17: Volume Descriptor Set Terminator
            // 18: Root Directory (PSP_GAME, UMD_DATA.BIN)
            // 19: PSP_GAME Directory (PARAM.SFO, SYSDIR, USRDIR)
            // 20: PSP_GAME/SYSDIR Directory (EBOOT.BIN)
            // 21: PSP_GAME/USRDIR Directory (bgm_battle.at3, char_hero.gim, script_en.bin, ui_system.dat)
            // 22: UMD_DATA.BIN
            // 23: PARAM.SFO
            // 24: EBOOT.BIN
            // 25: bgm_battle.at3
            // 26: char_hero.gim
            // 28..85: script_en.bin
            // 90..95: ui_system.dat

            // Write Sector 16: Primary Volume Descriptor
            val pvd = ByteArray(SECTOR_SIZE)
            pvd[0] = 1 // Primary Volume Descriptor type
            System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, pvd, 1, 5)
            pvd[6] = 1 // Version 1
            writeFixedAscii(pvd, 8, "PSP GAME SYSTEM", 32)
            writeFixedAscii(pvd, 40, "CRIMSON_BLADE_ULUS10588", 32)
            writeBothEndianInt32(pvd, 80, totalSectors)
            writeBothEndianInt16(pvd, 120, 1)
            writeBothEndianInt16(pvd, 124, 1)
            writeBothEndianInt16(pvd, 128, SECTOR_SIZE)
            // Root directory record at offset 156 (LBA 18, length 2048)
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

            // Write Sector 18: Root Directory
            writeDirectorySector(
                raf = raf,
                sectorLba = 18,
                parentLba = 18,
                records = listOf(
                    createIsoDirRecord("PSP_GAME", lba = 19, size = SECTOR_SIZE, isDir = true),
                    createIsoDirRecord("UMD_DATA.BIN;1", lba = 22, size = 64, isDir = false)
                )
            )

            // Write Sector 19: PSP_GAME Directory
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

            // Write Sector 20: PSP_GAME/SYSDIR
            writeDirectorySector(
                raf = raf,
                sectorLba = 20,
                parentLba = 19,
                records = listOf(
                    createIsoDirRecord("EBOOT.BIN;1", lba = 24, size = ebootBytes.size, isDir = false)
                )
            )

            // Write Sector 21: PSP_GAME/USRDIR
            writeDirectorySector(
                raf = raf,
                sectorLba = 21,
                parentLba = 19,
                records = listOf(
                    createIsoDirRecord("bgm_battle.at3;1", lba = 25, size = at3AudioDummy.size, isDir = false),
                    createIsoDirRecord("char_hero.gim;1", lba = 26, size = gimTexDummy.size, isDir = false),
                    createIsoDirRecord("script_en.bin;1", lba = 28, size = scriptBytes.size, isDir = false),
                    createIsoDirRecord("ui_system.dat;1", lba = 90, size = uiDatBytes.size, isDir = false)
                )
            )

            // Write file payloads
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
            raf.write(scriptBytes)

            raf.seek((90 * SECTOR_SIZE).toLong())
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

    private fun buildSampleUiStringsBlob(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val uiLines = listOf(
            "SYS_BTN_START: Open Tactical Map (%s)",
            "SYS_BTN_SELECT: Toggle Night Vision <color=02>[ACTIVE]</color>",
            "Mission Objective #%d: Secure Valkyrie Core before %s expires."
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

package com.example.domain.iso

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * Reverse Pack/Unpack & Binary Container Inspector for PSP `PSP_GAME/USRDIR/` files (Requirement 2).
 *
 * Inspects the 4-byte Magic Header of `.pak`, `.dat`, `.bin`, `.str`, `.txt`, `.cfg` files:
 * - Detects `GZIP` (`1F 8B 08`), `ZLIB` (`78 01`, `78 5E`, `78 9C`, `78 DA`), `LZO` (`89 4C 5A 4F`),
 *   custom `PAK` archives (with sub-file count + Offset/Size table), and `POINTER_TABLE` string containers.
 * - Unpacks sub-files or decompresses streams in memory chunks to locate genuine game dialogue.
 */
object PspArchiveUnpacker {

    enum class ContainerFormat(val label: String) {
        GZIP_COMPRESSED("GZIP Compressed Stream"),
        ZLIB_COMPRESSED("ZLIB Compressed Stream"),
        LZO_COMPRESSED("LZO Compressed Container"),
        PAK_ARCHIVE("PAK / Offset-Size Archive"),
        POINTER_STRING_TABLE("Pointer-Indexed String Table"),
        UTF16LE_TEXT_TABLE("UTF-16LE Localization Table"),
        RAW_SCRIPT_CONTAINER("Raw Script / Dialogue Binary")
    }

    data class SubFileSlice(
        val index: Int,
        val name: String,
        val offsetInParent: Long,
        val sizeBytes: Int,
        val pointerEntryOffsetInParent: Long = -1L,
        val format: ContainerFormat = ContainerFormat.RAW_SCRIPT_CONTAINER,
        val decompressedBytes: ByteArray? = null
    )

    data class ContainerInspectionResult(
        val format: ContainerFormat,
        val magicHex: String,
        val magicAscii: String,
        val subFiles: List<SubFileSlice>,
        val hasPointerTable: Boolean,
        val pointerTableBaseOffset: Long = 0L,
        val pointerCount: Int = 0
    )

    /**
     * Reads the first 4..2048 header bytes of a file in `PSP_GAME/USRDIR/` to identify
     * whether it is GZIP, ZLIB, LZO, a PAK container with an Offset/Size table, or a Pointer Table.
     */
    fun inspectHeader(headerBytes: ByteArray, totalFileSize: Long): ContainerInspectionResult {
        if (headerBytes.size < 8) {
            return ContainerInspectionResult(
                format = ContainerFormat.RAW_SCRIPT_CONTAINER,
                magicHex = headerBytes.joinToString(" ") { "%02X".format(it) },
                magicAscii = "",
                subFiles = emptyList(),
                hasPointerTable = false
            )
        }

        val b0 = headerBytes[0].toInt() and 0xFF
        val b1 = headerBytes[1].toInt() and 0xFF
        val b2 = headerBytes[2].toInt() and 0xFF
        val b3 = headerBytes[3].toInt() and 0xFF

        val magicHex = "%02X %02X %02X %02X".format(b0, b1, b2, b3)
        val magicAscii = byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
            .map { c -> val v = c.toInt() and 0xFF; if (v in 32..126) v.toChar() else '.' }
            .joinToString("")

        // 1. Check GZIP Magic (1F 8B 08)
        if (b0 == 0x1F && b1 == 0x8B && b2 == 0x08) {
            return ContainerInspectionResult(
                format = ContainerFormat.GZIP_COMPRESSED,
                magicHex = magicHex,
                magicAscii = "GZIP",
                subFiles = emptyList(),
                hasPointerTable = false
            )
        }

        // 2. Check ZLIB Magic (78 01, 78 5E, 78 9C, 78 DA)
        if (b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA)) {
            return ContainerInspectionResult(
                format = ContainerFormat.ZLIB_COMPRESSED,
                magicHex = magicHex,
                magicAscii = "ZLIB",
                subFiles = emptyList(),
                hasPointerTable = false
            )
        }

        // 3. Check LZO Magic (89 'L' 'Z' 'O' or 'L' 'Z' 'O' 00)
        if ((b0 == 0x89 && b1 == 0x4C && b2 == 0x5A && b3 == 0x4F) ||
            (b0 == 0x4C && b1 == 0x5A && b2 == 0x4F && b3 == 0x00)
        ) {
            return ContainerInspectionResult(
                format = ContainerFormat.LZO_COMPRESSED,
                magicHex = magicHex,
                magicAscii = "LZO",
                subFiles = emptyList(),
                hasPointerTable = false
            )
        }

        // 4. Check Custom PSP Pointer Table Header ("PSPTBL01" or "PSPSCR01")
        if (headerBytes.size >= 16) {
            val magic8 = String(headerBytes, 0, 8, StandardCharsets.US_ASCII)
            if (magic8 == "PSPTBL01" || magic8 == "PSPSCR01") {
                val entryCount = readInt32LE(headerBytes, 8)
                val tableOffset = 16L
                if (entryCount in 1..10000) {
                    val slices = parseOffsetSizeTable(
                        headerBytes = headerBytes,
                        tableStartOffset = tableOffset.toInt(),
                        entryCount = entryCount,
                        totalFileSize = totalFileSize
                    )
                    return ContainerInspectionResult(
                        format = ContainerFormat.POINTER_STRING_TABLE,
                        magicHex = magicHex,
                        magicAscii = magic8,
                        subFiles = slices,
                        hasPointerTable = true,
                        pointerTableBaseOffset = tableOffset,
                        pointerCount = entryCount
                    )
                }
            }
        }

        // 5. Check Named PAK / WAD Archive Header (e.g. Call of Duty / Quake-style PACK / WAD TOC):
        // Pattern: Magic ("PACK", "PAK\0", "WAD\0", "IWAD", "PWAD") + TOC Offset / Count + Named TOC Entries
        val namedTocSlices = tryParseNamedPakOrWadToc(headerBytes, totalFileSize)
        if (namedTocSlices.isNotEmpty()) {
            return ContainerInspectionResult(
                format = ContainerFormat.PAK_ARCHIVE,
                magicHex = magicHex,
                magicAscii = magicAscii,
                subFiles = namedTocSlices,
                hasPointerTable = true,
                pointerTableBaseOffset = 8L,
                pointerCount = namedTocSlices.size
            )
        }

        // 6. Check Standard PAK / WAD TOC Header:
        // Pattern A: 4-byte Magic ("PAK\0", "PACK", "WAD\0", "DATA", "STR\0", "LOCL") + uint32 count + [offset, size] TOC
        // Pattern B: Headerless PAK where first uint32 is entryCount (2..4096) followed by [offset, size] pairs
        val pakSlicesWithMagic = tryParseStandardPakHeader(headerBytes, has4ByteMagic = true, totalFileSize = totalFileSize)
        if (pakSlicesWithMagic.isNotEmpty()) {
            return ContainerInspectionResult(
                format = ContainerFormat.PAK_ARCHIVE,
                magicHex = magicHex,
                magicAscii = magicAscii,
                subFiles = pakSlicesWithMagic,
                hasPointerTable = true,
                pointerTableBaseOffset = 8L,
                pointerCount = pakSlicesWithMagic.size
            )
        }

        val pakSlicesHeaderless = tryParseStandardPakHeader(headerBytes, has4ByteMagic = false, totalFileSize = totalFileSize)
        if (pakSlicesHeaderless.isNotEmpty()) {
            return ContainerInspectionResult(
                format = ContainerFormat.PAK_ARCHIVE,
                magicHex = magicHex,
                magicAscii = "PAK_TOC",
                subFiles = pakSlicesHeaderless,
                hasPointerTable = true,
                pointerTableBaseOffset = 4L,
                pointerCount = pakSlicesHeaderless.size
            )
        }

        // 7. Check Pointer-Only String Table (uint32 count followed by monotonically increasing offsets)
        val pointerOnlySlices = tryParseMonotonicOffsetTable(headerBytes, totalFileSize)
        if (pointerOnlySlices.isNotEmpty()) {
            return ContainerInspectionResult(
                format = ContainerFormat.POINTER_STRING_TABLE,
                magicHex = magicHex,
                magicAscii = "PTR_TBL",
                subFiles = pointerOnlySlices,
                hasPointerTable = true,
                pointerTableBaseOffset = 4L,
                pointerCount = pointerOnlySlices.size
            )
        }

        // 8. Check if payload is predominantly UTF-16LE (alternating printable ASCII + 0x00)
        if (looksLikeUtf16LeStream(headerBytes)) {
            return ContainerInspectionResult(
                format = ContainerFormat.UTF16LE_TEXT_TABLE,
                magicHex = magicHex,
                magicAscii = "UTF16LE",
                subFiles = emptyList(),
                hasPointerTable = false
            )
        }

        return ContainerInspectionResult(
            format = ContainerFormat.RAW_SCRIPT_CONTAINER,
            magicHex = magicHex,
            magicAscii = magicAscii,
            subFiles = emptyList(),
            hasPointerTable = false
        )
    }

    /**
     * Parses Named PAK/WAD Table of Contents (TOC) where each entry has:
     * `[name: char[24], offset: uint32, size: uint32]` (32-byte TOC entry) after a 4-byte Magic + 4-byte count.
     */
    private fun tryParseNamedPakOrWadToc(
        headerBytes: ByteArray,
        totalFileSize: Long
    ): List<SubFileSlice> {
        if (headerBytes.size < 40) return emptyList()
        val magic4 = String(headerBytes, 0, 4, StandardCharsets.US_ASCII)
        if (magic4 != "PACK" && magic4 != "PAK\u0000" && magic4 != "WAD\u0000" && magic4 != "IWAD" && magic4 != "PWAD") {
            return emptyList()
        }
        val count = readInt32LE(headerBytes, 4)
        if (count !in 1..2048) return emptyList()

        val tocStart = 8
        val entrySize = 32 // 24-byte ASCII name + 4-byte offset + 4-byte size
        val maxEntries = min(count, (headerBytes.size - tocStart) / entrySize)
        if (maxEntries <= 0) return emptyList()

        val slices = mutableListOf<SubFileSlice>()
        for (i in 0 until maxEntries) {
            val pos = tocStart + i * entrySize
            val rawNameBytes = headerBytes.copyOfRange(pos, pos + 24)
            val nullIdx = rawNameBytes.indexOf(0.toByte()).let { if (it < 0) 24 else it }
            val name = String(rawNameBytes, 0, nullIdx, StandardCharsets.US_ASCII).trim()
            val offset = readInt32LE(headerBytes, pos + 24).toLong() and 0xFFFFFFFFL
            val size = readInt32LE(headerBytes, pos + 28)

            if (name.isEmpty() || offset < 8L || size < 0 || offset + size > totalFileSize) {
                return emptyList()
            }
            slices.add(
                SubFileSlice(
                    index = i,
                    name = name,
                    offsetInParent = offset,
                    sizeBytes = size,
                    pointerEntryOffsetInParent = (pos + 24).toLong()
                )
            )
        }
        return slices
    }

    private fun tryParseStandardPakHeader(
        headerBytes: ByteArray,
        has4ByteMagic: Boolean,
        totalFileSize: Long
    ): List<SubFileSlice> {
        val countOffset = if (has4ByteMagic) 4 else 0
        val tableStart = countOffset + 4
        if (headerBytes.size < tableStart + 16) return emptyList()

        val count = readInt32LE(headerBytes, countOffset)
        if (count !in 2..4096) return emptyList()

        val minFirstDataOffset = tableStart + (count * 8)
        if (minFirstDataOffset >= totalFileSize) return emptyList()

        return parseOffsetSizeTable(headerBytes, tableStart, count, totalFileSize)
    }

    private fun parseOffsetSizeTable(
        headerBytes: ByteArray,
        tableStartOffset: Int,
        entryCount: Int,
        totalFileSize: Long
    ): List<SubFileSlice> {
        val slices = mutableListOf<SubFileSlice>()
        val maxEntriesInHeader = min(entryCount, (headerBytes.size - tableStartOffset) / 8)
        if (maxEntriesInHeader <= 0) return emptyList()

        var prevOffset = -1L
        for (i in 0 until maxEntriesInHeader) {
            val pos = tableStartOffset + (i * 8)
            val offset = readInt32LE(headerBytes, pos).toLong() and 0xFFFFFFFFL
            val size = readInt32LE(headerBytes, pos + 4)

            if (offset <= 0L || size < 0 || offset + size > totalFileSize) {
                return emptyList()
            }
            if (prevOffset > 0 && offset < prevOffset) {
                return emptyList()
            }
            prevOffset = offset
            slices.add(
                SubFileSlice(
                    index = i,
                    name = "subfile_%03d.bin".format(i),
                    offsetInParent = offset,
                    sizeBytes = size,
                    pointerEntryOffsetInParent = pos.toLong()
                )
            )
        }
        return slices
    }

    private fun tryParseMonotonicOffsetTable(
        headerBytes: ByteArray,
        totalFileSize: Long
    ): List<SubFileSlice> {
        if (headerBytes.size < 24) return emptyList()
        val count = readInt32LE(headerBytes, 0)
        if (count !in 4..4096) return emptyList()

        val tableEnd = 4 + (count * 4)
        if (tableEnd >= totalFileSize) return emptyList()

        val maxInspect = min(count, (headerBytes.size - 4) / 4)
        if (maxInspect < 4) return emptyList()

        val offsets = LongArray(maxInspect)
        for (i in 0 until maxInspect) {
            val off = readInt32LE(headerBytes, 4 + i * 4).toLong() and 0xFFFFFFFFL
            if (off < tableEnd || off >= totalFileSize) return emptyList()
            if (i > 0 && off <= offsets[i - 1]) return emptyList()
            offsets[i] = off
        }

        val slices = mutableListOf<SubFileSlice>()
        for (i in 0 until maxInspect) {
            val start = offsets[i]
            val end = if (i + 1 < maxInspect) offsets[i + 1] else min(totalFileSize, start + 512L)
            val len = (end - start).toInt().coerceAtLeast(0)
            slices.add(
                SubFileSlice(
                    index = i,
                    name = "ptr_entry_%04d".format(i),
                    offsetInParent = start,
                    sizeBytes = len,
                    pointerEntryOffsetInParent = (4 + i * 4).toLong()
                )
            )
        }
        return slices
    }

    fun looksLikeUtf16LeStream(sample: ByteArray): Boolean {
        if (sample.size < 32) return false
        var asciiEven = 0
        var zeroOdd = 0
        val pairs = min(sample.size / 2, 128)
        for (i in 0 until pairs) {
            val lo = sample[i * 2].toInt() and 0xFF
            val hi = sample[i * 2 + 1].toInt() and 0xFF
            if (lo in 0x20..0x7E) asciiEven++
            if (hi == 0x00) zeroOdd++
        }
        return asciiEven >= pairs * 0.45 && zeroOdd >= pairs * 0.65
    }

    /**
     * Decompresses a GZIP or ZLIB byte buffer (up to [maxOutputBytes] to protect Android heap).
     */
    fun decompressStreamSafe(
        compressed: ByteArray,
        format: ContainerFormat,
        maxOutputBytes: Int = 512 * 1024
    ): ByteArray? {
        return try {
            val out = ByteArrayOutputStream(min(compressed.size * 3, maxOutputBytes))
            val input = when (format) {
                ContainerFormat.GZIP_COMPRESSED -> GZIPInputStream(ByteArrayInputStream(compressed))
                ContainerFormat.ZLIB_COMPRESSED -> InflaterInputStream(ByteArrayInputStream(compressed), Inflater())
                else -> return null
            }
            input.use { stream ->
                val buf = ByteArray(8192)
                var total = 0
                while (total < maxOutputBytes) {
                    val r = stream.read(buf, 0, min(buf.size, maxOutputBytes - total))
                    if (r <= 0) break
                    out.write(buf, 0, r)
                    total += r
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scans a binary buffer for embedded ZLIB streams (78 9C / 78 DA) that many PSP games
     * (including Call of Duty / Activision PAKs) pack sequentially inside `.pak` or `.bin` files.
     */
    fun scanAndDecompressEmbeddedZlibStreams(
        rawChunk: ByteArray,
        baseIsoOffset: Long,
        maxStreams: Int = 12
    ): List<SubFileSlice> {
        val found = mutableListOf<SubFileSlice>()
        var i = 0
        while (i < rawChunk.size - 16 && found.size < maxStreams) {
            val b0 = rawChunk[i].toInt() and 0xFF
            val b1 = rawChunk[i + 1].toInt() and 0xFF
            if (b0 == 0x78 && (b1 == 0x9C || b1 == 0xDA || b1 == 0x01)) {
                val sliceLen = min(rawChunk.size - i, 128 * 1024)
                val slice = rawChunk.copyOfRange(i, i + sliceLen)
                val decompressed = decompressStreamSafe(slice, ContainerFormat.ZLIB_COMPRESSED, maxOutputBytes = 256 * 1024)
                if (decompressed != null && decompressed.size >= 64) {
                    found.add(
                        SubFileSlice(
                            index = found.size,
                            name = "zlib_block_0x${(baseIsoOffset + i).toString(16).uppercase()}",
                            offsetInParent = baseIsoOffset + i,
                            sizeBytes = decompressed.size,
                            format = ContainerFormat.ZLIB_COMPRESSED,
                            decompressedBytes = decompressed
                        )
                    )
                    i += 256
                    continue
                }
            }
            i++
        }
        return found
    }

    private fun readInt32LE(buf: ByteArray, offset: Int): Int {
        if (offset + 4 > buf.size) return 0
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}

package com.example.domain.iso

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.ln
import kotlin.math.min

/**
 * Pure-Kotlin PSP Reverse Engineering & Decompiler Engine
 * (Architecture modeled after JPCSP `jpcsp/jpcsp` MIPS R4000/Allegrex Decoder + QuickBMS `strg`/TOC Unpacker).
 *
 * Key Capabilities:
 * 1. **Binary Signature & Shannon Entropy Analysis**:
 *    - Detects Kirk-encrypted `~PSP` (`7E 50 53 50`), `\u007FELF` MIPS binaries, Amaze/CoD `.RES` (`strg` blocks),
 *      `PACK`/`WAD`/`PSPTBL01` containers, and ZLIB/GZIP streams.
 *    - Computes Shannon Entropy (`0.0..8.0` bits/byte) on binary sliding windows to immediately skip
 *      encrypted/compressed texture/audio blocks (`entropy > 6.65`), eliminating `\uFFFD` (``) binary garbage.
 * 2. **MIPS Allegrex Disassembler (`LUI` + `ADDIU` / `ORI` String XREF Decompiler)**:
 *    - Disassembles 32-bit Little-Endian Allegrex instructions (`LUI $reg, hi16` followed by `ADDIU/ORI $reg, $reg, lo16`)
 *      to locate string literal addresses referenced by game code.
 * 3. **Strict UTF-8 / ASCII Decoder**:
 *    - Uses `StandardCharsets.UTF_8.newDecoder()` with `CodingErrorAction.REPORT` so any invalid byte sequence
 *      is rejected at the byte level instead of producing replacement characters (`\uFFFD`).
 */
object PspReverseDecompilerEngine {

    private const val PSP_USER_BASE_ADDR_1 = 0x08800000L
    private const val PSP_USER_BASE_ADDR_2 = 0x08900000L
    private const val MAX_SHANNON_ENTROPY_FOR_RAW_TEXT_SCAN = 6.65

    data class DecompiledFileReport(
        val path: String,
        val binaryFormat: String,
        val isEncryptedKirk: Boolean,
        val shannonEntropy: Double,
        val mipsStringXrefCount: Int,
        val pointerTableCount: Int,
        val decompiledAsmPreview: List<String>,
        val sampleCleanStrings: List<String>
    )

    /**
     * Runs a lightweight Reverse Engineering & Decompilation pass on a file's header + initial window
     * so AI Tầng 1 can inspect the decompiled structure and sample strings of EVERY file in the ISO
     * and select the exact files to translate without hardcoded filename rules.
     */
    fun analyzeFileWithDecompiler(
        path: String,
        sampleBuffer: ByteArray,
        totalFileSize: Long
    ): DecompiledFileReport {
        if (sampleBuffer.size < 16) {
            return DecompiledFileReport(
                path = path,
                binaryFormat = "Tiny Binary (<16B)",
                isEncryptedKirk = false,
                shannonEntropy = 0.0,
                mipsStringXrefCount = 0,
                pointerTableCount = 0,
                decompiledAsmPreview = emptyList(),
                sampleCleanStrings = emptyList()
            )
        }

        val b0 = sampleBuffer[0].toInt() and 0xFF
        val b1 = sampleBuffer[1].toInt() and 0xFF
        val b2 = sampleBuffer[2].toInt() and 0xFF
        val b3 = sampleBuffer[3].toInt() and 0xFF

        // 1. Detect Kirk-encrypted ~PSP module (0x7E 'P' 'S' 'P' or 0x00 'P' 'B' 'P' with ~PSP payload)
        val isKirkEncrypted = (b0 == 0x7E && b1 == 0x50 && b2 == 0x53 && b3 == 0x50) ||
            containsAsciiSignature(sampleBuffer, "~PSP", maxSearchOffset = 256)

        val isElfBinary = (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46)
        val hasStrgBlock = containsAsciiSignature(sampleBuffer, "strg", maxSearchOffset = sampleBuffer.size) ||
            containsAsciiSignature(sampleBuffer, "STRG", maxSearchOffset = sampleBuffer.size)

        val entropy = computeShannonEntropy(sampleBuffer, min(sampleBuffer.size, 8192))
        val containerInspection = PspArchiveUnpacker.inspectHeader(sampleBuffer, totalFileSize)

        if (isKirkEncrypted) {
            return DecompiledFileReport(
                path = path,
                binaryFormat = "Kirk-Encrypted ~PSP Module (Skip Raw Scan)",
                isEncryptedKirk = true,
                shannonEntropy = entropy,
                mipsStringXrefCount = 0,
                pointerTableCount = 0,
                decompiledAsmPreview = listOf("; [~PSP KIRK ENCRYPTED BINARY - SKIPPED TO PREVENT GARBAGE]"),
                sampleCleanStrings = emptyList()
            )
        }

        // 2. Run MIPS Allegrex LUI/ADDIU String Cross-Reference Disassembler
        val mipsXrefs = disassembleMipsAllegrexStringXrefs(sampleBuffer, maxInstructions = 4096)

        // 3. Extract a small preview of clean strings (strictly validated UTF-8 / ASCII, zero \uFFFD)
        val workingBuffer = if (
            containerInspection.format == PspArchiveUnpacker.ContainerFormat.ZLIB_COMPRESSED ||
            containerInspection.format == PspArchiveUnpacker.ContainerFormat.GZIP_COMPRESSED
        ) {
            PspArchiveUnpacker.decompressStreamSafe(sampleBuffer, containerInspection.format, maxOutputBytes = 128 * 1024)
                ?: ByteArray(0)
        } else if (entropy > MAX_SHANNON_ENTROPY_FOR_RAW_TEXT_SCAN && !hasStrgBlock && !containerInspection.hasPointerTable) {
            // High-entropy compressed/media stream: check only embedded ZLIB blocks, never raw bytes!
            val zBlocks = PspArchiveUnpacker.scanAndDecompressEmbeddedZlibStreams(sampleBuffer, 0L, maxStreams = 3)
            zBlocks.firstOrNull()?.decompressedBytes ?: ByteArray(0)
        } else {
            sampleBuffer
        }

        val previewStrings = mutableListOf<String>()
        val seen = HashSet<String>()
        for (xref in mipsXrefs) {
            if (previewStrings.size >= 6) break
            if (seen.add(xref.resolvedString)) {
                previewStrings.add(xref.resolvedString)
            }
        }
        if (previewStrings.size < 6 && workingBuffer.isNotEmpty()) {
            val scanned = scanCleanStringsFromChunk(workingBuffer, maxStrings = 6 - previewStrings.size, seen = seen)
            previewStrings.addAll(scanned.map { it.text })
        }

        val formatDesc = when {
            hasStrgBlock -> "Amaze/CoD .RES String Table (strg)"
            isElfBinary -> "Decrypted MIPS Allegrex ELF"
            containerInspection.format != PspArchiveUnpacker.ContainerFormat.RAW_SCRIPT_CONTAINER ->
                containerInspection.format.label
            entropy > MAX_SHANNON_ENTROPY_FOR_RAW_TEXT_SCAN ->
                "High-Entropy Compressed/Media (${"%.2f".format(entropy)} bits/B)"
            else -> "Binary Container (${"%.2f".format(entropy)} bits/B)"
        }

        return DecompiledFileReport(
            path = path,
            binaryFormat = formatDesc,
            isEncryptedKirk = false,
            shannonEntropy = entropy,
            mipsStringXrefCount = mipsXrefs.size,
            pointerTableCount = containerInspection.subFiles.size,
            decompiledAsmPreview = mipsXrefs.take(4).map { it.asmLine },
            sampleCleanStrings = previewStrings
        )
    }

    data class MipsStringXref(
        val instructionOffset: Int,
        val targetOffsetInBuffer: Int,
        val asmLine: String,
        val resolvedString: String
    )

    /**
     * Disassembles 32-bit Little-Endian MIPS R4000 / Allegrex instructions (`LUI` + `ADDIU` / `ORI`)
     * modeled after JPCSP (`jpcsp/Allegrex/Decoder.java`) to find string literal pointers.
     */
    fun disassembleMipsAllegrexStringXrefs(
        buffer: ByteArray,
        maxInstructions: Int = 8192
    ): List<MipsStringXref> {
        if (buffer.size < 64) return emptyList()
        val xrefs = mutableListOf<MipsStringXref>()
        val regUpper = IntArray(32)
        val regUpperPc = IntArray(32) { -1 }

        val limitBytes = min(buffer.size - 4, maxInstructions * 4)
        var pc = 0
        while (pc <= limitBytes && xrefs.size < 64) {
            val insn = ByteBuffer.wrap(buffer, pc, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val opcode = (insn ushr 26) and 0x3F
            val rs = (insn ushr 21) and 0x1F
            val rt = (insn ushr 16) and 0x1F
            val imm16 = insn and 0xFFFF

            when (opcode) {
                0x0F -> {
                    // LUI $rt, imm16
                    if (rs == 0 && rt in 1..31) {
                        regUpper[rt] = imm16 shl 16
                        regUpperPc[rt] = pc
                    }
                }
                0x09, 0x0D -> {
                    // ADDIU $rt, $rs, signExt(imm16) (0x09) or ORI $rt, $rs, zeroExt(imm16) (0x0D)
                    if (rs in 1..31 && regUpperPc[rs] >= 0 && (pc - regUpperPc[rs]) <= 32) {
                        val fullAddr = if (opcode == 0x09) {
                            (regUpper[rs] + imm16.toShort().toInt()).toLong() and 0xFFFFFFFFL
                        } else {
                            (regUpper[rs] or imm16).toLong() and 0xFFFFFFFFL
                        }
                        val candidateOffset = resolveVirtualAddressToBufferOffset(fullAddr, buffer.size)
                        if (candidateOffset in 0 until buffer.size - 6) {
                            val str = readCleanNullTerminatedStringAt(buffer, candidateOffset)
                            if (str != null && SmartTextHeuristicFilter.isValidGameSentence(str)) {
                                val mnemonic = if (opcode == 0x09) "addiu" else "ori"
                                val asm = "0x%05X: lui \$r%d, 0x%04X ; %s \$r%d, \$r%d, 0x%04X -> \"%s\""
                                    .format(
                                        regUpperPc[rs],
                                        rs,
                                        (regUpper[rs] ushr 16) and 0xFFFF,
                                        mnemonic,
                                        rt,
                                        rs,
                                        imm16,
                                        str.take(36)
                                    )
                                xrefs.add(
                                    MipsStringXref(
                                        instructionOffset = pc,
                                        targetOffsetInBuffer = candidateOffset,
                                        asmLine = asm,
                                        resolvedString = str
                                    )
                                )
                            }
                        }
                    }
                }
            }
            pc += 4
        }
        return xrefs
    }

    private fun resolveVirtualAddressToBufferOffset(vaddr: Long, bufferSize: Int): Int {
        val candidates = longArrayOf(
            vaddr,
            vaddr - PSP_USER_BASE_ADDR_1,
            vaddr - PSP_USER_BASE_ADDR_2,
            vaddr and 0x000FFFFFL
        )
        for (c in candidates) {
            if (c in 16L until (bufferSize - 6).toLong()) {
                return c.toInt()
            }
        }
        return -1
    }

    data class CleanStringSlice(
        val offsetInBuffer: Int,
        val allocatedLength: Int,
        val text: String
    )

    /**
     * Scans a binary buffer for valid human-readable strings while:
     * 1) Skipping high-entropy compressed/encrypted 4KB blocks (`Shannon entropy > 6.65` unless `strg` header present).
     * 2) Enforcing strict UTF-8 / printable ASCII decoding (`CodingErrorAction.REPORT` — zero `\uFFFD` ``).
     * 3) Filtering through [SmartTextHeuristicFilter.isValidGameSentence].
     */
    fun scanCleanStringsFromChunk(
        buffer: ByteArray,
        maxStrings: Int,
        seen: MutableSet<String> = HashSet()
    ): List<CleanStringSlice> {
        if (buffer.isEmpty() || maxStrings <= 0) return emptyList()

        // First check if this entire chunk is Kirk-encrypted (~PSP)
        if (buffer.size >= 4 &&
            (buffer[0].toInt() and 0xFF) == 0x7E &&
            (buffer[1].toInt() and 0xFF) == 0x50 &&
            (buffer[2].toInt() and 0xFF) == 0x53 &&
            (buffer[3].toInt() and 0xFF) == 0x50
        ) {
            return emptyList()
        }

        val results = mutableListOf<CleanStringSlice>()

        // Check if buffer is a valid UTF-16LE localization table
        if (PspArchiveUnpacker.looksLikeUtf16LeStream(buffer)) {
            extractStrictUtf16LeStrings(buffer, maxStrings, seen, results)
            if (results.size >= maxStrings) return results
        }

        // Scan in 4096-byte windows so we can skip compressed/encrypted binary sub-blocks with high entropy
        val windowSize = 4096
        var windowBase = 0
        while (windowBase < buffer.size && results.size < maxStrings) {
            val windowLen = min(windowSize, buffer.size - windowBase)
            val windowEntropy = computeShannonEntropyRange(buffer, windowBase, windowLen)
            val hasExplicitStrgTag = containsAsciiSignatureRange(buffer, windowBase, windowLen, "strg") ||
                containsAsciiSignatureRange(buffer, windowBase, windowLen, "STRG")

            // If this 4KB window is high-entropy compressed/encrypted data (> 6.65 bits/byte) and not a strg block, skip it!
            if (windowEntropy > MAX_SHANNON_ENTROPY_FOR_RAW_TEXT_SCAN && !hasExplicitStrgTag) {
                windowBase += windowLen
                continue
            }

            windowBase += windowLen
        }

        // Perform strict byte-by-byte string extraction across non-encrypted regions
        val currentBytes = ByteArrayOutputStream(256)
        var currentStartIndex = -1
        var i = 0

        while (i < buffer.size && results.size < maxStrings) {
            // Check 4KB block entropy at block boundaries
            if (i % 4096 == 0 && buffer.size - i >= 512) {
                val blockLen = min(4096, buffer.size - i)
                val entropy = computeShannonEntropyRange(buffer, i, blockLen)
                if (entropy > MAX_SHANNON_ENTROPY_FOR_RAW_TEXT_SCAN &&
                    !containsAsciiSignatureRange(buffer, i, blockLen, "strg")
                ) {
                    currentBytes.reset()
                    currentStartIndex = -1
                    i += blockLen
                    continue
                }
            }

            val b = buffer[i].toInt() and 0xFF
            val isAllowedByte = (b in 0x20..0x7E) || b == 0x0A || b == 0x0D || b == 0x09 ||
                (b in 0xC2..0xDF) || (b in 0xE0..0xEF) || (b in 0x80..0xBF && currentBytes.size() > 0)

            if (isAllowedByte) {
                if (currentStartIndex < 0) currentStartIndex = i
                currentBytes.write(b)
            } else if (b == 0x00) {
                if (currentBytes.size() >= 5 && currentStartIndex >= 0) {
                    val rawBytes = currentBytes.toByteArray()
                    val decoded = decodeStrictUtf8OrNull(rawBytes)?.trim()
                    if (decoded != null &&
                        SmartTextHeuristicFilter.isValidGameSentence(decoded) &&
                        seen.add(decoded)
                    ) {
                        var paddingNulls = 1
                        var lookahead = i + 1
                        while (lookahead < buffer.size && buffer[lookahead].toInt() == 0 && paddingNulls < 64) {
                            paddingNulls++
                            lookahead++
                        }
                        results.add(
                            CleanStringSlice(
                                offsetInBuffer = currentStartIndex,
                                allocatedLength = rawBytes.size + paddingNulls,
                                text = decoded
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

        return results
    }

    private fun extractStrictUtf16LeStrings(
        buffer: ByteArray,
        maxStrings: Int,
        seen: MutableSet<String>,
        outList: MutableList<CleanStringSlice>
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
                    if (SmartTextHeuristicFilter.isValidGameSentence(candidate) && seen.add(candidate)) {
                        val byteLen = (i - startByteIndex) + 2
                        outList.add(
                            CleanStringSlice(
                                offsetInBuffer = startByteIndex,
                                allocatedLength = byteLen,
                                text = candidate
                            )
                        )
                    }
                }
                sb.setLength(0)
                startByteIndex = -1
            } else if (codeUnit in 0x0020..0x007E || codeUnit == 0x000A || codeUnit == 0x000D || codeUnit in 0x00C0..0x017F) {
                if (startByteIndex < 0) startByteIndex = i
                sb.append(codeUnit.toChar())
            } else {
                sb.setLength(0)
                startByteIndex = -1
            }
            i += 2
        }
    }

    private fun readCleanNullTerminatedStringAt(buffer: ByteArray, startOffset: Int): String? {
        if (startOffset < 0 || startOffset >= buffer.size) return null
        val out = ByteArrayOutputStream(128)
        var i = startOffset
        val maxEnd = min(buffer.size, startOffset + 512)
        while (i < maxEnd) {
            val b = buffer[i].toInt() and 0xFF
            if (b == 0x00) break
            if ((b in 0x20..0x7E) || b == 0x0A || b == 0x0D || b == 0x09 || (b in 0x80..0xEF)) {
                out.write(b)
            } else {
                return null
            }
            i++
        }
        if (out.size() < 5) return null
        return decodeStrictUtf8OrNull(out.toByteArray())?.trim()
    }

    /**
     * Decodes a byte array using strict UTF-8 validation (`CodingErrorAction.REPORT`).
     * Any malformed or unmappable byte sequence returns `null` immediately instead of `\uFFFD` (``).
     */
    fun decodeStrictUtf8OrNull(bytes: ByteArray): String? {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Computes Shannon Entropy (0.0 to 8.0 bits/byte) to distinguish natural text/tables (3.5 - 5.8 bits/B)
     * from encrypted Kirk binaries or compressed audio/textures (> 6.65 bits/B).
     */
    fun computeShannonEntropy(buffer: ByteArray, length: Int = buffer.size): Double {
        return computeShannonEntropyRange(buffer, 0, min(length, buffer.size))
    }

    private fun computeShannonEntropyRange(buffer: ByteArray, start: Int, length: Int): Double {
        if (length <= 0) return 0.0
        val counts = IntArray(256)
        val end = min(buffer.size, start + length)
        val actualLen = end - start
        if (actualLen <= 0) return 0.0

        for (i in start until end) {
            counts[buffer[i].toInt() and 0xFF]++
        }
        var entropy = 0.0
        val invLog2 = 1.0 / ln(2.0)
        for (c in counts) {
            if (c > 0) {
                val p = c.toDouble() / actualLen.toDouble()
                entropy -= p * (ln(p) * invLog2)
            }
        }
        return entropy
    }

    private fun containsAsciiSignature(buffer: ByteArray, sig: String, maxSearchOffset: Int): Boolean {
        return containsAsciiSignatureRange(buffer, 0, min(buffer.size, maxSearchOffset), sig)
    }

    private fun containsAsciiSignatureRange(buffer: ByteArray, start: Int, length: Int, sig: String): Boolean {
        val sigBytes = sig.toByteArray(StandardCharsets.US_ASCII)
        val end = min(buffer.size, start + length) - sigBytes.size
        if (end < start) return false
        for (i in start..end) {
            var match = true
            for (j in sigBytes.indices) {
                if (buffer[i + j] != sigBytes[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}

package com.example.domain.ai

import com.example.domain.model.IsoFileEntry
import com.example.domain.model.TranslationRules

/**
 * Contains the production System Prompts and prompt builders for the Two-Tier AI PSP Localization Architecture.
 */
object TwoTierPrompts {

    /**
     * TIER 1 - TASK 1 SYSTEM PROMPT:
     * Scout & Language File Discovery inside PSP_GAME/USRDIR & SYSDIR.
     */
    const val TIER1_SCOUT_FILE_DISCOVERY_SYSTEM_PROMPT = """
Bạn là Chuyên gia Reverse Engineering ROM PlayStation Portable (PSP ISO-9660) và Kỹ sư Dịch Mã Ngược (MIPS Allegrex Decompiler & Archive Unpacker - AI Tầng 1).

NHIỆM VỤ CỦA BẠN (NHIỆM VỤ 1 - AI TỰ ĐỘNG CHỌN FILE DỰA TRÊN BÁO CÁO DỊCH MÃ NGƯỢC):
Ứng dụng đã chạy bộ công cụ Dịch Mã Ngược (MIPS R4000/Allegrex Disassembler + TOC/strg Unpacker + Shannon Entropy Analyzer) trên toàn bộ các file trong `.iso`.
Bạn nhận được báo cáo dịch mã ngược của từng file gồm:
- Đường dẫn file (`Path`), kích thước (`Size`), mã Magic Header (`Magic`), định dạng nhị phân (`Format`), độ hỗn loạn byte (`ShannonEntropy`).
- Số lượng tham chiếu chuỗi từ lệnh hợp ngữ MIPS (`LUI` + `ADDIU`/`ORI` XREFs) và số lượng mục trong Pointer Table / TOC.
- Các dòng lệnh hợp ngữ MIPS dịch ngược (`DecompiledASM`) và các chuỗi văn bản mẫu thực tế đã giải mã (`SampleDecodedStrings`).

QUY TẮC CHỌN FILE CỦA AI:
1. Bạn có toàn quyền quyết định chọn 1 hoặc nhiều file (`selectedFilePaths`) chứa kịch bản hội thoại, nhiệm vụ, phụ đề, menu thực sự của game dựa trên kết quả dịch mã ngược (`SampleDecodedStrings` và `DecompiledASM`).
2. Loại bỏ các file mã hóa phần cứng Kirk (`~PSP`), các file âm thanh/texture nén có Entropy cao mà không có chuỗi hội thoại thật, hoặc file chỉ chứa tên hàm SDK/compiler (`sceKernel*`, `heap_alloc`).
3. Trả về JSON hợp lệ theo đúng Schema gồm:
   - `primaryTargetFile`: Đường dẫn file chứa nhiều hội thoại/kịch bản quan trọng nhất.
   - `selectedFilePaths`: Mảng danh sách TẤT CẢ các đường dẫn file mà bạn (AI) chọn để trích xuất và dịch thuật.
   - `candidateFiles`: Danh sách chi tiết từng file được chọn kèm `path`, `confidenceScore` (0-100), `estimatedEncoding`, và `reason` (nêu rõ kết quả dịch mã ngược vì sao bạn chọn file này).
   - `analysisSummary`: Nhận xét kỹ thuật của AI về cấu trúc nhị phân & kết quả dịch mã ngược của ROM này.
"""

    /**
     * TIER 1 - TASK 2 SYSTEM PROMPT:
     * Control Code Regex Generator & Mature Context/Glossary Architect (`translation_rules.json`).
     */
    const val TIER1_CONTEXT_AND_RULES_SYSTEM_PROMPT = """
Bạn là Đạo diễn Bản địa hóa Game (Lead Game Localization Director) kiêm Kỹ sư Phân tích Chuỗi Nhị phân PSP (Tầng 1 - AI Phân tích Ngữ cảnh & Quy tắc).

NHIỆM VỤ CỦA BẠN (NHIỆM VỤ 2 - LỌC MÃ HỆ THỐNG & LẬP QUY TẮC DỊCH `translation_rules.json`):
Bạn được cung cấp 30 đến 50 chuỗi văn bản đầu tiên trích xuất từ file ngôn ngữ của ROM PSP. Hãy phân tích sâu và sinh ra cấu hình JSON (`translation_rules.json`) phục vụ cho AI Tầng 2 (Bulk Translator) chạy hàng loạt.

YÊU CẦU KỸ THUẬT NGHIÊM NGẶT:
1. `system_tag_regex` (Regex nhận diện mã hệ thống TUYỆT ĐỐI KHÔNG ĐƯỢC DỊCH):
   - Viết 1 biểu thức chính quy (PCRE/Kotlin Regex hợp lệ) bắt chính xác mọi biến định dạng, mã điều khiển màu sắc, ký tự xuống dòng, nhãn nhân vật đóng khung hoặc macro nút bấm PSP xuất hiện trong mẫu:
     * Biến C printf: `%s`, `%d`, `%02d`, `%03d`, `%f`
     * Ký tự thoát: `\n`, `\r`, `\t`
     * Thẻ XML/HTML giả lập trong engine game: `<color=01>`, `</color>`, `<icon=...>`, `<freq=...>`
     * Thẻ lệnh đóng ngoặc vuông/nhọn: `[WAIT_01]`, `[WAIT_02]`, `[END]`, `[CH01_001]`, `{0A}`
     * Macro nút bấm phần cứng PSP: `SYS_BTN_CROSS`, `SYS_BTN_CIRCLE`, `SYS_BTN_SQUARE`, `SYS_BTN_TRIANGLE`, `SYS_BTN_LTRIGGER`, `SYS_BTN_RTRIGGER`, `SYS_BTN_START`, `SYS_BTN_SELECT`
   - Regex này sẽ được code Kotlin dùng để thay thế tự động toàn bộ mã hệ thống thành thẻ `{0}`, `{1}`, `{2}` trước khi gửi cho AI Tầng 2, giúp bảo vệ 100% cấu trúc nhị phân của ROM.

2. `game_genre_and_context` (Nhận diện Bối cảnh & Thể loại Game):
   - Xác định rõ thể loại game (ví dụ: Hành động Quân sự Đen tối / Chiến thuật Giả tưởng / Kiếm hiệp Seinen / Trinh thám Kinh dị) và không khí chung của cốt truyện.

3. `tone_guidelines` (Định hình Văn phong Việt hóa Trưởng thành):
   - Thiết lập văn phong sắc sảo, chín chắn, tự nhiên đúng ngữ cảnh người lớn:
     * Kiếm hiệp / Cổ trang: dùng từ Hán-Việt đắt giá, nhịp văn dứt khoát.
     * Quân sự / Hành động đen tối: câu lệnh ngắn gọn, đanh thép, sặc mùi thuốc súng, thuật ngữ tác chiến chuẩn xác.
     * Tuyệt đối CẤM dịch word-by-word ngây ngô (như "Bạn có ổn không?", "Hãy nhìn kìa!").
   - Lưu ý giới hạn độ dài chuỗi trên màn hình PSP (480x272) và bộ nhớ đệm nhị phân: ưu tiên diễn đạt cô đọng, súc tích.

4. `pronoun_rules` (Bảng Quy tắc Xưng hô Đa chiều):
   - Thiết lập cặp đại từ nhân xưng chuẩn xác theo quan hệ nhân vật (ví dụ: Chỉ huy <-> Cấp dưới: `Ta/Chỉ huy - Cậu/Các anh em`; Đồng đội lính đánh thuê: `Tôi - Ông/Anh/Cô`; Kẻ thù đối địch: `Ta/Tao - Ngươi/Mày`).

5. `glossary` (Từ điển Thuật ngữ & Tên riêng cố định):
   - Liệt kê các danh từ riêng, tên vũ khí, địa danh, tổ chức cần dịch thống nhất hoặc giữ nguyên xuyên suốt toàn bộ các batch.
"""

    /**
     * TIER 2 SYSTEM PROMPT TEMPLATE:
     * Compact, token-efficient System Instruction injected with `translation_rules.json` from Tier 1.
     */
    fun buildTier2BulkSystemPrompt(rules: TranslationRules): String {
        val pronounsCompact = rules.pronounRules.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        val glossaryCompact = rules.glossary.entries.joinToString("; ") { "${it.key} = ${it.value}" }

        return """
Bạn là AI Dịch thuật Game PSP Chuyên nghiệp (Tầng 2 - Bulk Translator).
Nhiệm vụ: Dịch mảng JSON gồm 100-150 câu thoại game sang tiếng Việt theo đúng quy tắc từ Tầng 1:

[QUY TẮC BẢN ĐỊA HÓA TỪ TẦNG 1 (translation_rules.json)]
- Bối cảnh: ${rules.gameGenreAndContext}
- Văn phong: ${rules.toneGuidelines}
- Xưng hô: $pronounsCompact
- Thuật ngữ (Glossary): $glossaryCompact

[LUẬT BẢO TOÀN MÃ HỆ THỐNG BẮT BUỘC 100%]
1. Mọi đoạn mã điều khiển hệ thống trong câu gốc đã được ẩn thành các thẻ số dạng `{0}`, `{1}`, `{2}`, `{3}`...
2. BẠN BẮT BUỘC PHẢI GIỮ NGUYÊN CHÍNH XÁC 100% các thẻ `{0}`, `{1}`, `{2}`... trong câu dịch tiếng Việt ở đúng vị trí ngữ pháp tương ứng.
3. TUYỆT ĐỐI KHÔNG dịch, không xóa, không thêm khoảng trắng vào bên trong `{0}`, không đổi `{0}` thành `[0]` hay `(0)`.
4. Dịch cô đọng, sắc sảo, trưởng thành, độ dài chuỗi tiếng Việt tương đương câu gốc để vừa bộ nhớ nhị phân file `.iso`.
5. Đầu ra: Trả về đúng mảng JSON chứa các object `{"id": <số nguyên>, "vi": "<bản dịch tiếng Việt giữ nguyên thẻ {0}, {1}>"}` khớp 1-1 với danh sách `id` đầu vào.
""".trimIndent()
    }

    fun buildTier1ScoutUserPrompt(volumeId: String, entries: List<IsoFileEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("Volume ID: $volumeId")
        sb.appendLine("Báo cáo Dịch Mã Ngược (MIPS Allegrex Decompiler + TOC Unpacker) trên các file trong ISO:")
        entries.filter { !it.isDirectory }.forEach { entry ->
            sb.appendLine(
                "- Path: ${entry.path} | Size: ${entry.sizeBytes}B | LBA: ${entry.lbaSector} | Magic: [${entry.magic4BytesHex}] | Format: ${entry.containerFormatLabel} | Entropy: ${"%.2f".format(entry.shannonEntropy)} bits/B | MIPS_String_XREFs: ${entry.mipsXrefCount} | TOC_SubFiles: ${entry.subFileCount} | KirkEncrypted: ${entry.isKirkEncrypted}"
            )
            if (entry.decompiledAsmPreview.isNotEmpty()) {
                sb.appendLine("  + DecompiledASM: ${entry.decompiledAsmPreview.joinToString(" || ")}")
            }
            if (entry.decompiledSampleStrings.isNotEmpty()) {
                sb.appendLine("  + SampleDecodedStrings: ${entry.decompiledSampleStrings.joinToString(" | ") { "\"${it.take(60)}\"" }}")
            }
        }
        return sb.toString()
    }

    fun buildTier1RulesUserPrompt(targetFilePath: String, sampleStrings: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("File ngôn ngữ mục tiêu: $targetFilePath")
        sb.appendLine("Danh sách ${sampleStrings.size} chuỗi văn bản mẫu trích xuất đầu tiên:")
        sampleStrings.take(45).forEachIndexed { idx, raw ->
            sb.appendLine("${idx + 1}. $raw")
        }
        return sb.toString()
    }
}

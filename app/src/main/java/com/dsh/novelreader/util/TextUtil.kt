package com.dsh.novelreader.util

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text clean-up, OCR line merging and TXT chunking helpers. */
object TextUtil {

    private val CJK = 0x4E00..0x9FFF

    /** Normalise line endings and collapse PDF extraction artefacts. */
    fun normalizeText(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        s = s.replace(Regex("[ \\t]+\\n"), "\n")
        s = s.replace(Regex("\u00A0"), " ")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    /**
     * A page counts as having a usable text layer when it carries enough real
     * characters. Sparse producer headers ("Page 3") must not stop OCR.
     */
    fun looksLikeTextLayer(text: String): Boolean {
        val meaningful = text.count { it.isLetterOrDigit() || it.code in CJK }
        return meaningful >= 20
    }

    /**
     * Rebuild paragraphs from a hard-wrapped text layer.
     *
     * PDFBox emits one line per visual line, which would make the reader break
     * exactly where the original PDF broke. A line that ends mid-sentence is a
     * wrap artefact and is joined to the next one; a blank line, sentence-ending
     * punctuation or a short line (heading / dialogue) starts a new paragraph.
     */
    fun reflowLines(raw: String): String {
        val normalized = normalizeText(raw)
        if (normalized.isEmpty()) return ""
        val out = StringBuilder()
        var previous = ""
        for (rawLine in normalized.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n\n")
                previous = ""
                continue
            }
            when {
                previous.isEmpty() -> out.append(line)
                endsParagraph(previous) -> out.append("\n\n").append(line)
                else -> {
                    // Latin text needs a space where CJK does not.
                    val left = out.lastOrNull()
                    if (left != null && left.code < 128 && line.first().code < 128) out.append(' ')
                    out.append(line)
                }
            }
            previous = line
        }
        return normalizeText(out.toString())
    }

    private fun endsParagraph(line: String): Boolean {
        val last = line.last()
        return last in "。！？；：…!?.”」』》" || line.length < 12
    }

    /**
     * Merge OCR lines into paragraphs: a line break inside a sentence is a
     * layout artefact, a line ending in sentence punctuation (or a short line)
     * is a real paragraph boundary.
     */
    fun mergeOcrLines(raw: String): String {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val paragraphEnd = setOf('。', '！', '？', '；', '：', '…', '.', '!', '?', '”', '」', '』', '》')
        val out = StringBuilder()
        for (line in lines) {
            out.append(line)
            val last = line.last()
            val breakHere = last in paragraphEnd || line.length < 12 || out.length > 600
            out.append(if (breakHere) "\n\n" else "")
        }
        return normalizeText(out.toString())
    }

    /** Split a long TXT into ~[target] character sections on paragraph bounds. */
    fun splitIntoSections(text: String, target: Int = 3000): List<String> {
        val body = text.trim()
        if (body.isEmpty()) return emptyList()
        if (body.length <= target) return listOf(body)
        val result = ArrayList<String>()
        val buffer = StringBuilder()
        for (paragraph in body.split('\n')) {
            var rest = paragraph
            while (rest.length > target) {
                if (buffer.isNotEmpty()) {
                    result.add(buffer.toString().trim())
                    buffer.setLength(0)
                }
                result.add(rest.substring(0, target).trim())
                rest = rest.substring(target)
            }
            if (buffer.isNotEmpty() && buffer.length + rest.length + 1 > target) {
                result.add(buffer.toString().trim())
                buffer.setLength(0)
            }
            buffer.append(rest).append('\n')
        }
        if (buffer.isNotBlank()) result.add(buffer.toString().trim())
        return result.filter { it.isNotBlank() }
    }

    /** Decode TXT bytes: strict UTF-8 first, then GB18030 (common for Chinese). */
    fun decode(bytes: ByteArray): String {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        val text = utf8 ?: runCatching { String(bytes, Charset.forName("GB18030")) }
            .getOrDefault(String(bytes, Charsets.UTF_8))
        return text.removePrefix("\uFEFF").replace("\u0000", "")
    }

    fun displayTitle(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        return base.substringBeforeLast('.').ifBlank { base }.ifBlank { "未命名" }
    }

    /** First readable line of a section, used for the table of contents. */
    fun sectionPreview(text: String, limit: Int = 24): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "（空白页）"
        return if (line.length <= limit) line else line.take(limit) + "…"
    }
}

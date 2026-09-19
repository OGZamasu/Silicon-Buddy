package dev.siliconoptimizer.buddy.chat

/**
 * Enough Markdown for a model's answer, and no more.
 *
 * Compose has no Markdown renderer, and pulling one in for bold text and a code block
 * would be a dependency with a whole parser inside it. This splits an answer into
 * blocks; the composable side styles the inline markup. Small, and it means a streaming
 * answer can be re-rendered on every token.
 */
object Markdown {

    sealed interface Block {
        data class Paragraph(val text: String) : Block
        data class Heading(val level: Int, val text: String) : Block
        data class Bullets(val items: List<String>) : Block
        data class Numbered(val items: List<String>) : Block
        data class Code(val language: String?, val text: String) : Block
        data class Quote(val text: String) : Block
        data object Rule : Block
    }

    /**
     * Splits an answer into blocks. An unterminated fence is treated as code to the
     * end, which is what a half-streamed answer looks like.
     */
    fun blocks(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        val bullets = mutableListOf<String>()
        val numbers = mutableListOf<String>()
        val quote = mutableListOf<String>()
        val code = mutableListOf<String>()
        var codeLanguage: String? = null
        var inCode = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(Block.Paragraph(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }
        fun flushBullets() {
            if (bullets.isNotEmpty()) {
                blocks.add(Block.Bullets(bullets.toList()))
                bullets.clear()
            }
        }
        fun flushNumbers() {
            if (numbers.isNotEmpty()) {
                blocks.add(Block.Numbered(numbers.toList()))
                numbers.clear()
            }
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) {
                blocks.add(Block.Quote(quote.joinToString("\n")))
                quote.clear()
            }
        }
        fun flushAll() {
            flushParagraph(); flushBullets(); flushNumbers(); flushQuote()
        }

        for (rawLine in markdown.split("\n")) {
            val trimmed = rawLine.trim()

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (inCode) {
                    blocks.add(Block.Code(codeLanguage, code.joinToString("\n")))
                    code.clear()
                    codeLanguage = null
                    inCode = false
                } else {
                    flushAll()
                    val fence = trimmed.drop(3).trim()
                    codeLanguage = fence.ifEmpty { null }
                    inCode = true
                }
                continue
            }
            if (inCode) {
                code.add(rawLine)
                continue
            }

            if (trimmed.isEmpty()) {
                flushAll()
                continue
            }
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                flushAll()
                blocks.add(Block.Rule)
                continue
            }
            if (trimmed.startsWith("#")) {
                val hashes = trimmed.takeWhile { it == '#' }.length
                if (hashes <= 6 && trimmed.drop(hashes).startsWith(" ")) {
                    flushAll()
                    blocks.add(Block.Heading(hashes, trimmed.drop(hashes).trim()))
                    continue
                }
            }
            if (trimmed.startsWith("> ") || trimmed == ">") {
                flushParagraph(); flushBullets(); flushNumbers()
                quote.add(trimmed.drop(if (trimmed.startsWith("> ")) 2 else 1))
                continue
            }
            val bullet = bulletBody(trimmed)
            if (bullet != null) {
                flushParagraph(); flushNumbers(); flushQuote()
                bullets.add(bullet)
                continue
            }
            val numbered = numberedBody(trimmed)
            if (numbered != null) {
                flushParagraph(); flushBullets(); flushQuote()
                numbers.add(numbered)
                continue
            }
            flushBullets(); flushNumbers(); flushQuote()
            paragraph.add(rawLine)
        }

        if (inCode && code.isNotEmpty()) {
            blocks.add(Block.Code(codeLanguage, code.joinToString("\n")))
        }
        flushAll()
        return blocks
    }

    private fun bulletBody(line: String): String? {
        for (marker in listOf("- ", "* ", "• ", "+ ")) {
            if (line.startsWith(marker)) return line.removePrefix(marker)
        }
        return null
    }

    private fun numberedBody(line: String): String? {
        val digits = line.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        val rest = line.drop(digits.length)
        if (!rest.startsWith(". ") && !rest.startsWith(") ")) return null
        return rest.drop(2)
    }

    /** Inline spans, for the composable side to style. */
    sealed interface Span {
        data class Plain(val text: String) : Span
        data class Bold(val text: String) : Span
        data class Italic(val text: String) : Span
        data class Code(val text: String) : Span
    }

    /** Splits one line into bold, italic, code and plain runs. */
    fun spans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var index = 0
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                spans.add(Span.Plain(plain.toString()))
                plain.clear()
            }
        }

        while (index < text.length) {
            val rest = text.substring(index)
            val bold = matchDelimited(rest, "**")
            val code = matchDelimited(rest, "`")
            val italic = matchDelimited(rest, "*")
            when {
                bold != null -> {
                    flushPlain(); spans.add(Span.Bold(bold.first)); index += bold.second
                }
                code != null -> {
                    flushPlain(); spans.add(Span.Code(code.first)); index += code.second
                }
                italic != null -> {
                    flushPlain(); spans.add(Span.Italic(italic.first)); index += italic.second
                }
                else -> {
                    plain.append(text[index]); index++
                }
            }
        }
        flushPlain()
        return spans
    }

    /** Returns the content and the total consumed length when [rest] opens with [marker]. */
    private fun matchDelimited(rest: String, marker: String): Pair<String, Int>? {
        if (!rest.startsWith(marker)) return null
        val closing = rest.indexOf(marker, startIndex = marker.length)
        if (closing < 0) return null
        val content = rest.substring(marker.length, closing)
        if (content.isEmpty()) return null
        return content to (closing + marker.length)
    }
}

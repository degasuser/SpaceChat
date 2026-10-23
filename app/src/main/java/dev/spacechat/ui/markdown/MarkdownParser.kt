package dev.spacechat.ui.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.CustomNode
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline

/** Math is parsed before Markdown escapes/emphasis, but never inside code or link destinations. */
class MathNode(val latex: String, val display: Boolean, val source: String) : CustomNode()

object MarkdownParser {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
        .customInlineContentParserFactory(MathInlineFactory())
        .customBlockParserFactory(MathBlockFactory())
        .build()

    fun parse(text: String) = parser.parse(text)
}

private class MathInlineFactory : InlineContentParserFactory {
    override fun getTriggerCharacters() = setOf('$', '\\')
    override fun create() = InlineContentParser { state -> parseMath(state) }
}

private fun parseMath(state: InlineParserState): ParsedInline? {
    val scanner = state.scanner()
    val start = scanner.position()
    val dollar = scanner.peek() == '$'
    val opening: String
    val closing: String
    if (dollar) {
        scanner.next()
        val display = scanner.next('$')
        opening = if (display) "\$\$" else "\$"
        closing = opening
        // Avoid interpreting ordinary prices such as "$5 and $10" as math.
        if (!display && (scanner.peek().isWhitespace() || scanner.peek() == '\u0000')) return null
    } else {
        scanner.next()
        opening = when {
            scanner.next('(') -> "\\("
            scanner.next('[') -> "\\["
            else -> return null
        }
        closing = if (opening == "\\(") "\\)" else "\\]"
    }
    val contentStart = scanner.position()
    var scanned = 0
    while (scanner.hasNext() && scanned++ <= 8192) {
        val end = scanner.position()
        if (scanner.next(closing)) {
            val latex = scanner.getSource(contentStart, end).content
            if (latex.isNotBlank() && (!dollar || opening.length == 2 ||
                        (!latex.last().isWhitespace() && !scanner.peek().isDigit()))) {
                val source = scanner.getSource(start, scanner.position()).content
                return ParsedInline.of(MathNode(latex, opening == "\$\$" || opening == "\\[", source), scanner.position())
            }
            break
        }
        if (scanner.peek() == '\n' && opening == "\$") break
        if (scanner.next('\\') && scanner.hasNext()) scanner.next() else scanner.next()
    }
    // Leave unmatched delimiters visible, including both dollars of an incomplete block.
    scanner.setPosition(start)
    scanner.next(opening)
    return ParsedInline.of(Text(opening), scanner.position())
}

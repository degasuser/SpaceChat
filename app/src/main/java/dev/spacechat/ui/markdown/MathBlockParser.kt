package dev.spacechat.ui.markdown

import org.commonmark.node.CustomBlock
import org.commonmark.parser.SourceLine
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

class MathBlockNode(val opening: String) : CustomBlock() {
    var latex = ""
    var closed = false
    val source: String get() = "$opening\n$latex" + if (closed) "\n" + if (opening == "\$\$") "\$\$" else "\\]" else ""
}

/** Standalone display delimiters also allow blank lines and Markdown-looking LaTeX. */
class MathBlockFactory : AbstractBlockParserFactory() {
    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        if (state.indent >= 4) return null
        val content = state.line.content
        val marker = content.subSequence(state.nextNonSpaceIndex, content.length).toString().trimEnd()
        if (marker != "\$\$" && marker != "\\[") return null
        return BlockStart.of(DisplayParser(marker)).atIndex(content.length)
    }
}

private class DisplayParser(opening: String) : AbstractBlockParser() {
    private val node = MathBlockNode(opening)
    private val lines = mutableListOf<String>()
    private var first = true
    override fun getBlock() = node
    override fun tryContinue(state: ParserState): BlockContinue {
        val closing = if (node.opening == "\$\$") "\$\$" else "\\]"
        if (state.line.content.subSequence(state.index, state.line.content.length).toString().trim() == closing) {
            node.closed = true
            return BlockContinue.finished()
        }
        return BlockContinue.atIndex(state.index)
    }
    override fun addLine(line: SourceLine) {
        if (first) first = false else lines += line.content.toString()
    }
    override fun closeBlock() { node.latex = lines.joinToString("\n").trim() }
}

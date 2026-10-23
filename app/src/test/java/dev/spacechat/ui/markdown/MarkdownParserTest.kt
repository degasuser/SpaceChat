package dev.spacechat.ui.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    private fun nodes(source: String): List<Node> {
        fun descend(node: Node): List<Node> = buildList {
            add(node)
            var child = node.firstChild
            while (child != null) { addAll(descend(child)); child = child.next }
        }
        return descend(MarkdownParser.parse(source))
    }

    @Test fun recognizesAllDelimitersWithoutUnescapingLatex() {
        val math = nodes("""Inline ${'$'}x_1^2${'$'} and \(a+b\). Display ${'$'}${'$'}\frac{1}{2}${'$'}${'$'} and \[Fe_3O_4\rightarrow Fe\].""").filterIsInstance<MathNode>()
        assertEquals(listOf("x_1^2", "a+b", "\\frac{1}{2}", "Fe_3O_4\\rightarrow Fe"), math.map { it.latex })
        assertEquals(listOf(false, false, true, true), math.map { it.display })
    }

    @Test fun keepsEscapedDollarsCurrencyAndUnclosedMathAsText() {
        val tree = nodes("""Prices: \${'$'}5 and ${'$'}10. Missing ${'$'}${'$'}\frac{1}{2}.""")
        assertTrue(tree.none { it is MathNode })
        assertTrue(tree.filterIsInstance<Text>().joinToString("") { it.literal }.contains("$$"))
    }

    @Test fun doesNotRenderMathInsideInlineOrFencedOrIndentedCode() {
        val tree = nodes("`\$x\$`\n\n```python\nprint('\\[x\\]')\n```\n\n    \$\$y\$\$")
        assertTrue(tree.none { it is MathNode || it is MathBlockNode })
        assertEquals("\$x\$", tree.filterIsInstance<Code>().single().literal)
        assertTrue(tree.filterIsInstance<FencedCodeBlock>().single().literal.contains("\\[x\\]"))
    }

    @Test fun displayBlockPreservesBlankLinesAndArraySyntax() {
        val tree = nodes("Before\n\n\\[\n\\begin{aligned}\na &= b \\\\\n\nc &= d\n\\end{aligned}\n\\]\n\nAfter")
        val block = tree.filterIsInstance<MathBlockNode>().single()
        assertTrue(block.closed)
        assertTrue(block.latex.contains("\n\nc &= d"))
        assertTrue(tree.filterIsInstance<Text>().any { it.literal == "After" })
    }

    @Test fun displayBlocksWorkInsideQuotesAndNestedLists() {
        val block = nodes("> \\[\n> x_1^2\n> \\]").filterIsInstance<MathBlockNode>().single()
        assertTrue(block.closed)
        assertEquals("x_1^2", block.latex)
    }

    @Test fun unfinishedDisplayBlockKeepsOriginalSource() {
        val block = nodes("\$\$\n\\frac{1}{2}").filterIsInstance<MathBlockNode>().single()
        assertFalse(block.closed)
        assertEquals("\$\$\n\\frac{1}{2}", block.source)
    }

    @Test fun parsesGfmAndMathTogetherIncludingEmphasisAndTables() {
        val tree = nodes("**Bold \$x_i\$** ~~old~~\n\n- [x] Done\n- [ ] Later\n\n| Value | Formula |\n| --- | --- |\n| Half | \$\\frac{1}{2}\$ |")
        assertEquals(2, tree.filterIsInstance<MathNode>().size)
        assertEquals(1, tree.filterIsInstance<TableBlock>().size)
        assertEquals(1, tree.filterIsInstance<Strikethrough>().size)
        assertEquals(listOf(true, false), tree.filterIsInstance<TaskListItemMarker>().map { it.isChecked })
        assertTrue(tree.filterIsInstance<StrongEmphasis>().single().lastChild is MathNode)
    }

    @Test fun chemistryExampleKeepsCyrillicAndSubscripts() {
        val math = nodes("""Количество: ${'$'}${'$'}n(Fe_3O_4) = \frac{110000}{232} = 474{,}1 \text{ моль}${'$'}${'$'}""").filterIsInstance<MathNode>().single()
        assertTrue(math.display)
        assertTrue(math.latex.contains("\\text{ моль}"))
        assertTrue(math.latex.contains("Fe_3O_4"))
    }
}

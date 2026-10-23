package dev.spacechat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spacechat.ui.markdown.MarkdownParser
import dev.spacechat.ui.markdown.MathBlockNode
import dev.spacechat.ui.markdown.MathNode
import dev.spacechat.ui.markdown.MathRenderer
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownLiteral
import org.commonmark.node.ThematicBreak
import ru.noties.jlatexmath.JLatexMathDrawable

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val root by produceState<Node?>(null, text) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { MarkdownParser.parse(text) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val document = root
        if (document == null) Text(text) else document.children().forEach { MarkdownBlock(it) }
    }
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

@Composable
private fun MarkdownBlock(node: Node) {
    when (node) {
        is FencedCodeBlock -> if (node.info.trim().lowercase() in setOf("math", "latex", "tex")) {
            MathBlock(node.literal.trim(), node.literal.trim())
        } else CodeBlock(node.literal.trimEnd(), node.info.orEmpty().substringBefore(" "))
        is MathBlockNode -> if (node.closed) MathBlock(node.latex, node.source) else SelectionContainer { Text(node.source) }
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd(), "text")
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> MarkdownTable(node)
        is TaskListItemMarker -> Unit
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.children().forEachIndexed { index, item ->
                val task = item.firstChild as? TaskListItemMarker
                Row {
                    Text(when {
                        task != null -> if (task.isChecked) "☑ " else "☐ "
                        node is OrderedList -> "${node.markerStartNumber + index}. "
                        else -> "• "
                    })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.children().forEach { MarkdownBlock(it) }
                    }
                }
            }
        }
        is BlockQuote -> Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
            Text("│ ", color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                node.children().forEach { MarkdownBlock(it) }
            }
        }
        else -> RichText(node, when (node) {
            is Heading -> when (node.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }
            else -> MaterialTheme.typography.bodyLarge
        })
    }
}

private data class MathSpan(val start: Int, val end: Int, val id: String, val math: MathNode)
private data class RichContent(val text: AnnotatedString, val formulas: List<MathSpan>)

@Composable
private fun RichText(node: Node, style: TextStyle, align: TextAlign = TextAlign.Start) {
    val primary = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current
    val content = remember(node, primary, uriHandler) {
        val formulas = mutableListOf<MathSpan>()
        val text = buildAnnotatedString {
            fun children(n: Node, visit: (Node) -> Unit) { n.children().forEach(visit) }
            fun appendNode(n: Node) {
                when (n) {
                    is MathNode -> {
                        val id = "formula-${formulas.size}"
                        val start = length
                        appendInlineContent(id, n.source)
                        formulas += MathSpan(start, length, id, n)
                    }
                    is MarkdownLiteral -> append(n.literal)
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x223B91A8))) { append(n.literal) }
                    is SoftLineBreak, is HardLineBreak -> append("\n")
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(n, ::appendNode) }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(n, ::appendNode) }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children(n, ::appendNode) }
                    is Link -> {
                        if (n.destination.startsWith("https://", true) || n.destination.startsWith("http://", true) || n.destination.startsWith("mailto:", true)) {
                            withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))) {
                                runCatching { uriHandler.openUri(n.destination) }
                            }) { children(n, ::appendNode) }
                        } else children(n, ::appendNode)
                    }
                    is Image -> append("[Изображение: ${n.destination}]")
                    is HtmlInline -> append(n.literal)
                    is HtmlBlock -> append(n.literal)
                    else -> children(n, ::appendNode)
                }
            }
            appendNode(node)
        }
        RichContent(text, formulas)
    }
    val density = LocalDensity.current
    val color = LocalContentColor.current.toArgb()
    val pixels = with(density) { style.fontSize.toPx() }
    val drawables by produceState<Map<String, JLatexMathDrawable>>(emptyMap(), content, pixels, color) {
        value = buildMap {
            content.formulas.forEach { span -> MathRenderer.render(span.math.latex, pixels, color)?.let { put(span.id, it) } }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        val inlines = content.formulas.filter { !it.math.display }.mapNotNull { span ->
            drawables[span.id]?.let { drawable ->
                val width = with(density) { drawable.intrinsicWidth.toDp() }
                val scale = if (width > availableWidth) availableWidth.value / width.value else 1f
                span.id to InlineTextContent(Placeholder(
                    with(density) { (drawable.intrinsicWidth * scale).toSp() },
                    with(density) { (drawable.intrinsicHeight * scale).toSp() },
                    PlaceholderVerticalAlign.TextCenter
                )) { FormulaCanvas(drawable, span.math.source, Modifier.fillMaxSize()) }
            }
        }.toMap()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var start = 0
            content.formulas.filter { it.math.display }.forEach { span ->
                val before = content.text.subSequence(start, span.start).trimLineBreaks()
                if (before.isNotEmpty()) SelectionContainer { Text(before, style = style, textAlign = align, inlineContent = inlines) }
                FormulaBlockContent(span.math.latex, span.math.source, drawables[span.id])
                start = span.end
            }
            val remaining = content.text.subSequence(start, content.text.length).trimLineBreaks()
            if (remaining.isNotEmpty()) SelectionContainer { Text(remaining, style = style, textAlign = align, inlineContent = inlines) }
        }
    }
}

private fun AnnotatedString.trimLineBreaks(): AnnotatedString {
    val start = text.indexOfFirst { it != '\n' }.let { if (it < 0) length else it }
    val end = text.indexOfLast { it != '\n' } + 1
    return subSequence(start.coerceAtMost(end), end)
}

@Composable
private fun MathBlock(latex: String, source: String) {
    val color = LocalContentColor.current.toArgb()
    val pixels = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toPx() }
    val drawable by produceState<JLatexMathDrawable?>(null, latex, pixels, color) {
        value = MathRenderer.render(latex, pixels, color)
    }
    FormulaBlockContent(latex, source, drawable)
}

@Composable
private fun FormulaBlockContent(latex: String, source: String, drawable: JLatexMathDrawable?) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (drawable == null) {
            SelectionContainer { Text(source, fontFamily = FontFamily.Monospace) }
        } else {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                FormulaCanvas(drawable, source, Modifier.size(
                    with(density) { drawable.intrinsicWidth.toDp() },
                    with(density) { drawable.intrinsicHeight.toDp() }
                ))
            }
        }
        val clipboard = LocalClipboardManager.current
        TextButton(onClick = { clipboard.setText(AnnotatedString(latex)) }, contentPadding = PaddingValues(0.dp)) {
            Text("Копировать LaTeX", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FormulaCanvas(drawable: JLatexMathDrawable, source: String, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Формула: $source" }) {
        drawable.setBounds(0, 0, size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        table.children().flatMap { it.children() }.forEach { row ->
            Row {
                row.children().filterIsInstance<TableCell>().forEach { cell ->
                    Box(Modifier.width(180.dp)
                        .background(if (cell.isHeader) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(10.dp)) {
                        RichText(cell, if (cell.isHeader) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            when (cell.alignment) { TableCell.Alignment.CENTER -> TextAlign.Center; TableCell.Alignment.RIGHT -> TextAlign.End; else -> TextAlign.Start })
                    }
                }
            }
        }
    }
}
@Composable
private fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    val color = MaterialTheme.colorScheme.primary
    val highlighted = remember(code, color) {
        buildAnnotatedString {
            append(code)
            // Small lexical highlighter; never executes or renders remote HTML.
            val pattern = Regex(""""(?:[^"\\]|\\.)*"|'[^'\n]*'|//[^\n]*|#[^\n]*|\b(?:fun|val|var|class|def|return|if|else|for|while|import|package|const|let|function|true|false|null|None|public|private|suspend|async|await)\b|\b[0-9]+\b""")
            pattern.findAll(code).forEach { addStyle(SpanStyle(color = if(it.value.startsWith("\"") || it.value.startsWith("'")) Color(0xFF77BC88) else color), it.range.first, it.range.last + 1) }
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(language.ifBlank { "code" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }, contentPadding = PaddingValues(0.dp)) { Text("Копировать код") }
            }
            SelectionContainer { Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.horizontalScroll(rememberScrollState())) }
        }
    }
}

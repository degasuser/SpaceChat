package dev.spacechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val markdownParser = Parser.builder().build()

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val root = remember(text) { markdownParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var child = root.firstChild
        while (child != null) { MarkdownBlock(child); child = child.next }
    }
}

@Composable
private fun MarkdownBlock(node: Node) {
    when (node) {
        is FencedCodeBlock -> CodeBlock(node.literal.trimEnd(), node.info.orEmpty().substringBefore(" "))
        is IndentedCodeBlock -> CodeBlock(node.literal.trimEnd(), "text")
        is ThematicBreak -> HorizontalDivider()
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var item = node.firstChild; var number = if(node is OrderedList) node.markerStartNumber else 1
            while (item != null) {
                Row { Text(if(node is OrderedList) "${number++}. " else "• "); Column(Modifier.weight(1f)) { var c = item.firstChild; while(c != null) { MarkdownBlock(c); c = c.next } } }
                item = item.next
            }
        }
        is BlockQuote -> Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
            Text("│ ", color = MaterialTheme.colorScheme.primary)
            Column { var c = node.firstChild; while(c != null) { MarkdownBlock(c); c = c.next } }
        }
        else -> SelectionContainer {
            Text(inlineText(node), style = when(node) {
                is Heading -> if(node.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyLarge
            })
        }
    }
}

private fun inlineText(node: Node): AnnotatedString = buildAnnotatedString {
    fun appendNode(n: Node) {
        when(n) {
            is Text -> append(n.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x223B91A8))) { append(n.literal) }
            is SoftLineBreak, is HardLineBreak -> append("\n")
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { var c=n.firstChild; while(c!=null){appendNode(c);c=c.next} }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { var c=n.firstChild; while(c!=null){appendNode(c);c=c.next} }
            is Link -> { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { var c=n.firstChild; while(c!=null){appendNode(c);c=c.next} }; append(" (${n.destination})") }
            is Image -> append("[Изображение: ${n.destination}]")
            is HtmlInline -> append(n.literal)
            is HtmlBlock -> append(n.literal)
            else -> { var c=n.firstChild; while(c!=null){appendNode(c);c=c.next} }
        }
    }
    appendNode(node)
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

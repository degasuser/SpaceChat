package dev.spacechat.ui.markdown

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable

/** JLaTeXMath has shared font/parser state: serialize work, off the UI thread. */
object MathRenderer {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private data class Key(val latex: String, val pixels: Float, val color: Int)
    private val cache = LruCache<Key, JLatexMathDrawable>(64)
    private val blocked = Regex("""\\(?:newcommand|renewcommand|providecommand|def|gdef|edef|xdef|newenvironment|renewenvironment|includegraphics|input|include|usepackage|documentclass|loop|repeat|csname)\b""")

    fun canRender(latex: String): Boolean {
        if (latex.length > 4096 || blocked.containsMatchIn(latex)) return false
        var depth = 0
        var escaped = false
        for (char in latex) {
            if (!escaped) {
                if (char == '{' && ++depth > 64) return false
                if (char == '}') depth--
            }
            escaped = !escaped && char == '\\'
        }
        return true
    }

    suspend fun render(latex: String, pixels: Float, color: Int): JLatexMathDrawable? = withContext(dispatcher) {
        if (!canRender(latex)) return@withContext null
        val key = Key(latex, pixels, color)
        cache.get(key)?.let { return@withContext it }
        try {
            // Models commonly emit a bare numeric percent (e.g. \boxed{81{,}97%}).
            // Accept that notation; preserve ordinary TeX comments and escaped percentages.
            val normalized = Regex("""(?<!\\)(?<=\d)%(?=[}\s]|$)""").replace(latex) { "\\%" }
            JLatexMathDrawable.builder(normalized).textSize(pixels).color(color).padding(2).build()
                .takeIf { it.intrinsicWidth in 1..16000 && it.intrinsicHeight in 1..6000 }
                ?.also { cache.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A malformed or unsupported formula must not prevent reading the message.
            null
        }
    }
}

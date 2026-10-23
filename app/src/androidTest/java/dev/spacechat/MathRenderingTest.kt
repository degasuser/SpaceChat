package dev.spacechat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spacechat.ui.markdown.MathRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MathRenderingTest {
    @Test fun drawsChemistryCyrillicFractionsMatricesAndGreekWithoutNetwork() = runBlocking {
        val formulas = listOf(
            "Fe_3O_4 + 4CO \\rightarrow 3Fe + 4CO_2",
            "n(Fe_3O_4) = \\frac{110000}{232} = 474{,}1 \\text{ моль}",
            "m(Fe)_{\\text{теор}} = 1422{,}4 \\cdot 56 \\approx 79{,}66 \\text{ кг}",
            "\\eta = \\frac{65{,}3}{79{,}66} = \\boxed{81{,}97\\%}",
            "\\boxed{81{,}97%}",
            "\\frac{1}{2} + \\sqrt{x_1^2 + \\alpha}",
            "\\begin{pmatrix}1 & 2 \\\\ 3 & 4\\end{pmatrix}",
            "\\begin{aligned}a &= b+c \\\\ d &= e\\end{aligned}",
            "\\int_0^\\infty e^{-x}\\,dx=1"
        )
        for (latex in formulas) {
            val drawable = MathRenderer.render(latex, 24f, Color.BLACK)
            assertNotNull("Could not render: $latex", drawable)
            val bitmap = Bitmap.createBitmap(drawable!!.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Empty formula: $latex", pixels.any { Color.alpha(it) > 0 })
            bitmap.recycle()
        }
    }

    @Test fun malformedAndUnsupportedInputFallsBackWithoutCrashing() = runBlocking {
        assertNull(MathRenderer.render("\\unknownSpaceChatCommand{x}", 24f, Color.BLACK))
        assertNull(MathRenderer.render("\\includegraphics{/data/private}", 24f, Color.BLACK))
        assertNull(MathRenderer.render("\\newcommand{\\x}{\\x}\\x", 24f, Color.BLACK))
        assertNull(MathRenderer.render("x".repeat(4097), 24f, Color.BLACK))
    }
}

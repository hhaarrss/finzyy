package com.smartspend.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs material-icons-core doesn't ship. Pulling in material-icons-extended
 * for six icons would add thousands of unused vectors to an unminified APK, so these are drawn
 * here on the same 24dp grid and 2dp round stroke as the core set they sit beside.
 */
object FinzyyIcons {

    val Filter: ImageVector by lazy {
        stroked("Filter") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(14f, 12.5f)
            lineTo(14f, 18.5f); lineTo(10f, 20f); lineTo(10f, 12.5f); close()
        }
    }

    val Trends: ImageVector by lazy {
        stroked("Trends", width = 2.4f) {
            moveTo(5f, 20f); lineTo(5f, 12f)
            moveTo(10f, 20f); lineTo(10f, 7f)
            moveTo(15f, 20f); lineTo(15f, 14f)
            moveTo(20f, 20f); lineTo(20f, 4f)
        }
    }

    val Categories: ImageVector by lazy {
        stroked("Categories") {
            circle(12f, 12f, 8.5f)
            moveTo(12f, 3.5f); lineTo(12f, 12f); lineTo(19.4f, 16.2f)
        }
    }

    val Insights: ImageVector by lazy {
        stroked("Insights") {
            moveTo(3f, 17f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(21f, 7f)
            moveTo(15f, 7f); lineTo(21f, 7f); lineTo(21f, 13f)
        }
    }

    val Article: ImageVector by lazy {
        stroked("Article") {
            moveTo(6f, 3.5f); lineTo(18f, 3.5f); lineTo(18f, 20.5f); lineTo(6f, 20.5f); close()
            moveTo(9f, 8f); lineTo(15f, 8f)
            moveTo(9f, 12f); lineTo(15f, 12f)
            moveTo(9f, 16f); lineTo(12.5f, 16f)
        }
    }

    val Help: ImageVector by lazy {
        stroked("Help") {
            circle(12f, 12f, 9f)
            moveTo(9.6f, 9.4f)
            curveTo(9.6f, 8f, 10.7f, 7f, 12f, 7f)
            curveTo(13.4f, 7f, 14.4f, 8f, 14.4f, 9.3f)
            curveTo(14.4f, 11.2f, 12f, 11.4f, 12f, 13.4f)
            moveTo(12f, 16.6f); lineTo(12f, 16.8f)
        }
    }

    val Theme: ImageVector by lazy {
        ImageVector.Builder("Theme", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) { circle(12f, 12f, 8.5f) }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 3.5f)
                arcTo(8.5f, 8.5f, 0f, false, true, 12f, 20.5f)
                close()
            }
        }.build()
    }

    val Sms: ImageVector by lazy {
        stroked("Sms") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(9f, 16f)
            lineTo(5f, 19.5f); lineTo(5f, 16f); lineTo(4f, 16f); close()
            moveTo(8f, 9.5f); lineTo(16f, 9.5f)
            moveTo(8f, 12.5f); lineTo(13f, 12.5f)
        }
    }

    private fun stroked(
        name: String,
        width: Float = 2f,
        block: PathBuilder.() -> Unit
    ): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }
}

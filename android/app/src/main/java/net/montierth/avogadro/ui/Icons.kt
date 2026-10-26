package net.montierth.avogadro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The web page's icon set: 1.6px round-capped strokes in a 24-unit box (tinted when drawn). */
object Icons {
    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    val Search = stroke("search") {
        moveTo(17f, 10.5f)
        arcTo(6.5f, 6.5f, 0f, true, true, 4f, 10.5f)
        arcTo(6.5f, 6.5f, 0f, true, true, 17f, 10.5f)
        moveTo(15.5f, 15.5f)
        lineTo(20f, 20f)
    }

    val Close = stroke("close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val ChevronDown = stroke("chevron-down") {
        moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
    }
}

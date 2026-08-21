package com.simoscal.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A small jerry-can glyph for the Lambda destination. */
internal val GasCanIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "GasCan",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Can body.
            moveTo(4f, 7f)
            horizontalLineTo(17f)
            curveTo(18.1f, 7f, 19f, 7.9f, 19f, 9f)
            verticalLineTo(20f)
            curveTo(19f, 21.1f, 18.1f, 22f, 17f, 22f)
            horizontalLineTo(4f)
            curveTo(2.9f, 22f, 2f, 21.1f, 2f, 20f)
            verticalLineTo(9f)
            curveTo(2f, 7.9f, 2.9f, 7f, 4f, 7f)
            close()

            // Top handle and short pouring spout.
            moveTo(7f, 7f)
            verticalLineTo(3f)
            horizontalLineTo(14f)
            lineTo(17f, 7f)
            moveTo(9.5f, 7f)
            verticalLineTo(5f)
            horizontalLineTo(13f)
            lineTo(14.5f, 7f)
            moveTo(17f, 7f)
            lineTo(20f, 4f)
            lineTo(22f, 6f)
            lineTo(19f, 9f)

            // The stamped X makes the silhouette read as a fuel can at nav size.
            moveTo(6.5f, 11f)
            lineTo(14.5f, 18f)
            moveTo(14.5f, 11f)
            lineTo(6.5f, 18f)
        }
    }.build()
}

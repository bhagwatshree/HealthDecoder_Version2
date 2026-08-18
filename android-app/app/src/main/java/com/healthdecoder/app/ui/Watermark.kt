package com.healthdecoder.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.healthdecoder.app.R

/**
 * The small logo badge shown next to the back arrow in every screen's top bar. Uses `ic_health_decoder_logo`
 * vector drawable which natively renders cleanly on both Light and Dark theme surfaces.
 */
@Composable
fun TopBarLogo(size: androidx.compose.ui.unit.Dp = 28.dp) {
    Image(
        painter = painterResource(id = R.drawable.ic_health_decoder_logo),
        contentDescription = tr("Health Decoder Logo"),
        modifier = Modifier
            .height(size)
            .width(size)
            .clip(RoundedCornerShape(6.dp))
    )
}

/**
 * Faint, centered Health Decoder logo watermark drawn behind a screen's own content.
 */
@Composable
fun Modifier.appWatermark(alpha: Float = 0.06f): Modifier {
    val context = LocalContext.current
    // ic_health_decoder_logo is a VectorDrawable (XML), not a raster image — BitmapFactory can
    // only decode PNG/JPG/WebP and returns null for it, which used to crash on .asImageBitmap().
    // A vector has to be drawn into a Bitmap explicitly via its own intrinsic size.
    val bitmap = remember {
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_health_decoder_logo)!!
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bmp))
        bmp.asImageBitmap()
    }
    return this.drawBehind {
        val target = size.minDimension * 0.85f
        if (target <= 0f || bitmap.width <= 0) return@drawBehind
        val scale = target / bitmap.width.toFloat()
        val dstSize = IntSize(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1)
        )
        val dstOffset = IntOffset(
            ((size.width - dstSize.width) / 2f).toInt(),
            ((size.height - dstSize.height) / 2f).toInt()
        )
        drawImage(
            image = bitmap,
            dstOffset = dstOffset,
            dstSize = dstSize,
            alpha = alpha
        )
    }
}

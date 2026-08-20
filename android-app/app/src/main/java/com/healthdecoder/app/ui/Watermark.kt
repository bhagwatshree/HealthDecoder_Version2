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

// The faint centred logo watermark that used to sit behind every screen's content was removed:
// it competed with the content for contrast — worst on the Records list, where it showed through
// the gaps between grouped report cards — and carried no information. The top-bar badge above is
// the only branding a screen needs.

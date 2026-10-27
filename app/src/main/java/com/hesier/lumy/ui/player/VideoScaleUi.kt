@file:OptIn(UnstableApi::class)

package com.hesier.lumy.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.hesier.lumy.R
import com.hesier.lumy.data.VideoScale

fun videoScaleLabel(context: Context, scale: VideoScale): String = context.getString(
    when (scale) {
        VideoScale.FIT -> R.string.video_scale_fit
        VideoScale.ZOOM -> R.string.video_scale_zoom
        VideoScale.FILL -> R.string.video_scale_fill
    },
)

fun VideoScale.resizeMode(): Int = when (this) {
    VideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

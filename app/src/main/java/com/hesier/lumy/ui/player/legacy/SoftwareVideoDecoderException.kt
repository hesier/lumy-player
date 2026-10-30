package com.hesier.lumy.ui.player.legacy

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException

@UnstableApi
class SoftwareVideoDecoderException(message: String, cause: Throwable? = null) :
    DecoderException(message, cause)

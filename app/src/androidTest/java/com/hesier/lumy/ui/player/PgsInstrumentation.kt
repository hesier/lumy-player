package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import androidx.media3.decoder.ffmpeg.TrueHdDecoderTest

/** Runs playback parser and decoder checks using the platform instrumentation APIs. */
class PgsInstrumentation : Instrumentation() {
    private var benchmark = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        benchmark = arguments?.getString("benchmark_sup") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            MultiObjectPgsParserTest().runAll()
            PgsParserFactoryTest().runAll()
            SupExtractorTest().runAll()
            MatroskaIndexTest().runAll()
            TrueHdDecoderTest().runAll(context)
            DolbyVisionCompatibilityTest().runAll(targetContext)
            val performance = if (benchmark) PgsPerformanceProbe().run(targetContext.filesDir) else ""
            result.putString("stream", "PGS, track filtering, SUP, Matroska index, TrueHD and Dolby Vision checks passed\n$performance")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

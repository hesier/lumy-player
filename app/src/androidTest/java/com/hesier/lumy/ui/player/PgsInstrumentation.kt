package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/** Runs Android bitmap tests using only the platform instrumentation APIs. */
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
            val performance = if (benchmark) PgsPerformanceProbe().run(targetContext.filesDir) else ""
            result.putString("stream", "PGS parser: 10 tests, track filtering and SUP extraction/seek/error checks passed\n$performance")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

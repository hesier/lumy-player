package com.hesier.lumy.ui.player

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/** Runs Android bitmap tests using only the platform instrumentation APIs. */
class PgsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            MultiObjectPgsParserTest().runAll()
            result.putString("stream", "PGS parser: 10 tests passed\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

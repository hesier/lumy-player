package com.hesier.lumy.ui.login

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.hesier.lumy.data.PlexHomeSessionTest

class PlexHomeDataInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            PlexHomeSessionTest(targetContext).runAll()
            result.putString("stream", "Plex Home session compatibility, account token separation, backup round trips, auto sign-in and malformed backup checks passed\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

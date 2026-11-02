package com.hesier.lumy.ui.player

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.File

/** Some emulator builds return null from UiAutomation despite shell capture working. */
internal fun captureDanmakuTest(instrumentation: Instrumentation, name: String): File {
    val file = File(instrumentation.targetContext.cacheDir, name)
    val image = instrumentation.uiAutomation.takeScreenshot()
    if (image != null) {
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    } else {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    check(file.length() > 0) { "Emulator screenshot was empty" }
    return file
}

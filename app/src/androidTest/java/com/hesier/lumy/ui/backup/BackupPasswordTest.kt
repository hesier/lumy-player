package com.hesier.lumy.ui.backup

import android.app.Instrumentation
import android.content.Context
import android.view.View
import android.widget.EditText
import com.hesier.lumy.R

/** Empty passwords bypass encryption only when creating a backup. */
internal class BackupPasswordTest(private val instrumentation: Instrumentation) {
    fun runAll(context: Context) {
        instrumentation.runOnMainSync {
            checkSubmission(context, "", "", accepted = true)
            checkSubmission(context, "   ", "", accepted = true)
            checkSubmission(context, "", "mismatch", accepted = false)
            checkSubmission(context, "short", "short", accepted = false)
            checkSubmission(context, "password123", "password123", accepted = true)
            var called = false
            val panel = BackupPasswordPanel(context, false) { called = true }
            try {
                panel.show()
                panel.findViewById<View>(R.id.confirm).performClick()
                check(panel.isShowing && !called)
            } finally { panel.dismiss() }
        }
    }

    private fun checkSubmission(context: Context, first: String, repeated: String, accepted: Boolean) {
        var submitted: CharArray? = null
        val panel = BackupPasswordPanel(context, true) { submitted = it }
        try {
            panel.show()
            panel.findViewById<EditText>(R.id.password).setText(first)
            panel.findViewById<EditText>(R.id.repeated).setText(repeated)
            panel.findViewById<View>(R.id.confirm).performClick()
            check(accepted == (submitted != null))
            check(accepted != panel.isShowing)
            if (accepted) check(first.toCharArray().contentEquals(submitted))
        } finally { submitted?.fill('\u0000'); panel.dismiss() }
    }
}

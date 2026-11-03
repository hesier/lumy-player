package com.hesier.lumy.ui.backup

import android.app.Instrumentation
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.hesier.lumy.R

/** Empty passwords bypass encryption only when creating a backup. */
internal class BackupPasswordTest(private val instrumentation: Instrumentation) {
    fun runAll(context: Context) {
        instrumentation.runOnMainSync {
            checkSubmission(context, "", "", accepted = true)
            checkSubmission(context, "   ", "", accepted = true)
            checkSubmission(context, "", "mismatch", accepted = false)
            checkSubmission(context, "a", "a", accepted = true)
            checkSubmission(context, "short", "short", accepted = true)
            checkSubmission(context, "a", "b", accepted = false)
            checkSubmission(context, "a".repeat(32), "a".repeat(32), accepted = true)
            checkSubmission(context, "a".repeat(33), "a".repeat(33), accepted = false)
            checkLengthFeedback(context)
            checkSubmission(context, "password123", "password123", accepted = true)
            checkRestorePassword(context, "a".repeat(33))
            var called = false
            val panel = BackupPasswordPanel(context, false) { called = true }
            try {
                panel.show()
                panel.findViewById<View>(R.id.confirm).performClick()
                check(panel.isShowing && !called)
            } finally { panel.dismiss() }
        }
    }

    private fun checkLengthFeedback(context: Context) {
        var submitted = false
        val panel = BackupPasswordPanel(context, true) { it.fill('\u0000'); submitted = true }
        try {
            panel.show()
            val first = panel.findViewById<EditText>(R.id.password)
            val repeated = panel.findViewById<EditText>(R.id.repeated)
            val error = panel.findViewById<TextView>(R.id.error)
            check(error.visibility == View.GONE)
            for (field in listOf(first, repeated)) {
                field.setText("x".repeat(33))
                check(33 == field.length()) { "Overlong password was silently truncated" }
                check(error.visibility == View.VISIBLE)
                check(context.getString(R.string.backup_password_too_long) == error.text.toString())
                panel.findViewById<View>(R.id.confirm).performClick()
                check(!submitted && panel.isShowing)
                field.setText("")
                check(error.visibility == View.GONE)
            }
            first.setText("x")
            repeated.setText("x")
            panel.findViewById<View>(R.id.confirm).performClick()
            check(submitted && !panel.isShowing)
        } finally { panel.dismiss() }
    }

    private fun checkRestorePassword(context: Context, password: String) {
        var submitted: CharArray? = null
        val panel = BackupPasswordPanel(context, false) { submitted = it }
        try {
            panel.show()
            panel.findViewById<EditText>(R.id.password).setText(password)
            panel.findViewById<View>(R.id.confirm).performClick()
            check(password.toCharArray().contentEquals(submitted)) { "Legacy restore password was rejected" }
        } finally { submitted?.fill('\u0000'); panel.dismiss() }
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

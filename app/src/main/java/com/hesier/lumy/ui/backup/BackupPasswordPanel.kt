package com.hesier.lumy.ui.backup

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewBackupPasswordBinding
import com.hesier.lumy.ui.widget.enableDpadNavigation

internal class BackupPasswordPanel(
    context: Context,
    private val creating: Boolean,
    private val onPassword: (CharArray) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewBackupPasswordBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((420 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        }
        b.title.setText(if (creating) R.string.backup_set_password else R.string.backup_enter_password)
        b.hint.setText(if (creating) R.string.backup_password_hint else R.string.backup_decrypt_hint)
        b.repeated.visibility = if (creating) View.VISIBLE else View.GONE
        b.password.imeOptions = (if (creating) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE) or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        val fields = if (creating) listOf(b.password, b.repeated) else listOf(b.password)
        fields.forEachIndexed { index, field ->
            field.enableDpadNavigation { direction ->
                if (direction == View.FOCUS_UP) fields.getOrNull(index - 1) ?: field
                else if (direction == View.FOCUS_DOWN) fields.getOrNull(index + 1) ?: b.confirm else field
            }
        }
        val tooLong = {
            creating && fields.any { it.length() > MAX_PASSWORD_LENGTH }
        }
        if (creating) fields.forEach { field ->
            field.doAfterTextChanged {
                b.error.setText(R.string.backup_password_too_long)
                b.error.visibility = if (tooLong()) View.VISIBLE else View.GONE
            }
        }
        val submit = {
            val password = CharArray(b.password.length()) { b.password.text[it] }
            val repeated = CharArray(b.repeated.length()) { b.repeated.text[it] }
            val error = when {
                tooLong() -> R.string.backup_password_too_long
                creating && !password.contentEquals(repeated) &&
                    !(password.all { it.isWhitespace() } && repeated.all { it.isWhitespace() }) -> R.string.backup_password_invalid
                !creating && password.isEmpty() -> R.string.backup_password_required
                else -> null
            }
            repeated.fill('\u0000')
            if (error != null) {
                password.fill('\u0000')
                b.error.setText(error)
                b.error.visibility = View.VISIBLE
            } else {
                b.password.text.clear()
                b.repeated.text.clear()
                dismiss()
                onPassword(password)
            }
        }
        fields.last().setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        b.confirm.setOnClickListener { submit() }
        b.confirm.nextFocusUpId = fields.last().id
        b.confirm.nextFocusDownId = b.confirm.id
        setOnDismissListener { b.password.text.clear(); b.repeated.text.clear() }
        b.password.requestFocus()
    }

    private companion object {
        const val MAX_PASSWORD_LENGTH = 32
    }
}

package com.hesier.lumy.ui.login

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.TextView
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewPlexHomePinBinding
import com.hesier.lumy.ui.widget.scaleOnFocus

/** Keeps the PIN only in memory and submits immediately after the fourth digit. */
class PlexHomePinPanel(
    context: Context,
    private val memberName: String,
    private val onSubmit: (String) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {
    private lateinit var binding: ViewPlexHomePinBinding
    private var digits = ""
    private var busy = false
    private val keys = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ViewPlexHomePinBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        window?.apply {
            setLayout((WIDTH_DP * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        binding.title.text = context.getString(R.string.plex_home_pin_title, memberName)
        binding.status.setText(R.string.plex_home_pin_hint)
        binding.cancel.setOnClickListener { cancel() }
        buildKeypad()
        updateDigits()
        keys.first().requestFocus()
    }

    private fun buildKeypad() {
        val labels = (1..9).map(Int::toString) + listOf(context.getString(R.string.plex_home_delete), "0")
        labels.forEachIndexed { index, label ->
            val key = TextView(context, null, 0, R.style.Button_Lumy).apply {
                id = View.generateViewId()
                text = label
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(KEY_WIDTH_DP)
                    height = dp(KEY_HEIGHT_DP)
                    setMargins(dp(4), dp(3), dp(4), dp(3))
                }
                setOnClickListener { if (index == DELETE_INDEX) deleteDigit() else addDigit(label) }
                scaleOnFocus()
            }
            keys.add(key)
            binding.keypad.addView(key)
        }
        keys.forEachIndexed { index, key ->
            val column = index % COLUMNS
            key.nextFocusLeftId = keys[if (column > 0) index - 1 else index].id
            key.nextFocusRightId = keys[if (column < COLUMNS - 1 && index + 1 < keys.size) index + 1 else index].id
            key.nextFocusUpId = keys[if (index >= COLUMNS) index - COLUMNS else index].id
            key.nextFocusDownId = if (index + COLUMNS < keys.size) keys[index + COLUMNS].id else binding.cancel.id
        }
        binding.cancel.nextFocusUpId = keys.last().id
        binding.cancel.nextFocusDownId = binding.cancel.id
    }

    private fun addDigit(digit: String) {
        if (busy || digits.length >= PIN_LENGTH) return
        digits += digit
        updateDigits()
        if (digits.length == PIN_LENGTH) {
            val pin = digits
            digits = ""
            busy = true
            binding.status.setText(R.string.plex_home_switching)
            onSubmit(pin)
        }
    }

    private fun deleteDigit() {
        if (!busy) {
            digits = digits.dropLast(1)
            updateDigits()
        }
    }

    private fun updateDigits() {
        binding.digits.text = "●".repeat(digits.length) + "○".repeat(PIN_LENGTH - digits.length)
        binding.keypad.contentDescription = context.getString(R.string.plex_home_pin_count, digits.length)
    }

    fun showError(message: Int) {
        busy = false
        digits = ""
        updateDigits()
        binding.status.setText(message)
        keys.first().requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val digit = when (event.keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (event.keyCode - KeyEvent.KEYCODE_0).toString()
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (event.keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
            else -> null
        }
        if (digit != null || event.keyCode == KeyEvent.KEYCODE_DEL) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (digit != null) addDigit(digit) else deleteDigit()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dismiss() {
        digits = ""
        if (::binding.isInitialized) binding.digits.text = ""
        super.dismiss()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val WIDTH_DP = 420
        const val KEY_WIDTH_DP = 96
        const val KEY_HEIGHT_DP = 44
        const val PIN_LENGTH = 4
        const val COLUMNS = 3
        const val DELETE_INDEX = 9
    }
}

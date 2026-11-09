package com.jarvis.ai.core.keyboard

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout

/**
 * Local, opt-in keyboard. It does not log, transmit, cache or inspect typed
 * characters. Android owns the input connection and keyboard selection.
 * Password and sensitive fields get ordinary key input, never an AI request.
 */
class LoraKeyboardService : InputMethodService() {
    private var arabic = true
    private var uppercase = false

    override fun onCreateInputView(): View = buildKeyboard()

    private fun buildKeyboard(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(7.dp, 12.dp, 7.dp, 12.dp)
            setBackgroundColor(Color.rgb(8, 12, 24))
        }

        val title = android.widget.TextView(this).apply {
            text = "LORA  •  KEYBOARD"
            setTextColor(Color.rgb(102, 196, 255))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10.dp)
        }
        root.addView(title)

        val rows = if (arabic) listOf(
            listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
            listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
            listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "د"),
        ) else listOf(
            "qwertyuiop".map(Char::toString),
            "asdfghjkl".map(Char::toString),
            "zxcvbnm".map(Char::toString),
        )

        rows.forEach { keys ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            keys.forEach { character ->
                line.addView(key(character) {
                    val actual = if (uppercase && !arabic) character.uppercase() else character
                    currentInputConnection?.commitText(actual, 1)
                }, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { setMargins(2.dp, 2.dp, 2.dp, 2.dp) })
            }
            root.addView(line)
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, weight: Float = 1f, action: () -> Unit) {
            actions.addView(key(label, action), LinearLayout.LayoutParams(0, 52.dp, weight).apply {
                setMargins(3.dp, 3.dp, 3.dp, 3.dp)
            })
        }
        add(if (arabic) "EN" else "عربي") {
            arabic = !arabic
            setInputView(buildKeyboard())
        }
        add("⇧") {
            uppercase = !uppercase
        }
        add("⌫") {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
        add("مسافة", 3f) {
            currentInputConnection?.commitText(" ", 1)
        }
        add("↵") {
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            if (action != null && action != EditorInfo.IME_ACTION_NONE) {
                currentInputConnection?.performEditorAction(action)
            } else {
                currentInputConnection?.commitText("\n", 1)
            }
        }
        root.addView(actions)
        return root
    }

    private fun key(label: String, onTap: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(1.dp, 1.dp, 1.dp, 1.dp)
            background = GradientDrawable().apply {
                cornerRadius = 10.dp.toFloat()
                setColor(Color.rgb(25, 38, 62))
                setStroke(1.dp, Color.rgb(50, 93, 140))
            }
            setOnClickListener { onTap() }
        }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()
}

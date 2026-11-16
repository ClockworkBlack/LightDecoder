package com.lightdecoder.ui.chat

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Like [com.lightdecoder.ui.applySystemBarPadding], but the bottom also
 * clears the on-screen keyboard. Drawing edge to edge turns off Android's
 * own "resize for the keyboard", so screens with a text field at the bottom
 * (the composer, the contact search, the sign-in page) must do it themselves.
 */
fun AppCompatActivity.applySystemBarAndImePadding(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}

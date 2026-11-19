package com.lightdecoder.ui

import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.checkbox.MaterialCheckBox
import com.lightdecoder.R
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The top bar's menu checkboxes (Blue Bubbles): the tick shows on the white box. */
@RunWith(AndroidJUnit4::class)
class MenuCheckBoxTest {

    @Test
    fun theTickIsNotTheBoxsColour() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val themed = ContextThemeWrapper(ContextThemeWrapper(app, R.style.Theme_LightDecoder), R.style.ThemeOverlay_LightDecoder_MenuPopup)
        val box = MaterialCheckBox(themed).apply { isChecked = true }
        val checked = intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled)
        val boxColour = assertNotNull(box.buttonTintList).let { box.buttonTintList!!.getColorForState(checked, 0) }
        val tick = box.buttonIconTintList
        assertNotNull("the tick has a colour of its own", tick)
        assertNotEquals("a white tick on a white box looks unticked", boxColour, tick!!.getColorForState(checked, 0))
    }
}

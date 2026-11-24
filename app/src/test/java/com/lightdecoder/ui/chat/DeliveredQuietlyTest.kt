package com.lightdecoder.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** "Delivered Quietly" under the user's latest delivered iMessage, as an iPhone says it (B6.3). */
@RunWith(AndroidJUnit4::class)
class DeliveredQuietlyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theFooterSaysDeliveredQuietlyOnlyForADeliveredOne() {
        assertEquals("Delivered Quietly", MessagesAdapter.receiptText(context, Receipt(read = false, quietly = true)))
        assertEquals("Delivered", MessagesAdapter.receiptText(context, Receipt(read = false)))
        // Read since: it says read, however it was delivered.
        assertEquals("Read", MessagesAdapter.receiptText(context, Receipt(read = true, quietly = true)))
    }
}

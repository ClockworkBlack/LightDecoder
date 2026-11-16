package com.lightdecoder.ui.wa

import androidx.appcompat.app.AppCompatActivity

/**
 * Links this device to the user's WhatsApp account: what the link is and
 * what it stores, the user's number, then the pairing code to type into
 * WhatsApp on their phone (or a QR code to scan with it). Opened with
 * [com.lightdecoder.relay.RelayIntents.waSetup].
 *
 * NOT IMPLEMENTED YET: an empty screen, so the manifest entry and the intent
 * have a class.
 */
class WaSetupActivity : AppCompatActivity()

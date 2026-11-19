package com.lightdecoder.imessage.direct

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Allows [SimAuth] through Shizuku (an app the user starts with Android's Wireless debugging, no
 * computer needed): it runs the one shell command `appops set --uid <LightDecoder> USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER allow`,
 * as OpenBubbles 2.0.0 does. Nothing else is ever run through Shizuku.
 */
object ShizukuGrant {

    const val PLAY_STORE = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"

    sealed class Result {
        object Granted : Result()
        /** Shizuku is not installed or not started. */
        object NotRunning : Result()
        /** The user said no to LightDecoder in Shizuku. */
        object Refused : Result()
        data class Failed(val why: String) : Result()
    }

    /** Whether Shizuku is up and reachable from this process. */
    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: RuntimeException) {
        false
    }

    /** Asks Shizuku for permission if needed, runs the command, and checks that it took. */
    suspend fun grant(context: Context): Result {
        if (!running()) return Result.NotRunning
        try {
            if (Shizuku.isPreV11()) return Result.Failed("This Shizuku is too old. Update Shizuku.")
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                val answer = CompletableDeferred<Boolean>()
                val listener = Shizuku.OnRequestPermissionResultListener { _, result -> answer.complete(result == PackageManager.PERMISSION_GRANTED) }
                Shizuku.addRequestPermissionResultListener(listener)
                try {
                    Shizuku.requestPermission(REQUEST_CODE)
                    if (withTimeoutOrNull(120_000) { answer.await() } != true) return Result.Refused
                } finally {
                    Shizuku.removeRequestPermissionResultListener(listener)
                }
            }
            val error = withContext(Dispatchers.IO) { run(SimAuth.shellCommand(context)) }
            if (error != null) return Result.Failed(error)
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        return if (SimAuth.allowed(context)) Result.Granted else Result.Failed("The command ran, but Android does not show the permission as allowed.")
    }

    /** Runs [command] as Shizuku's shell user. Null when it exited with 0, else what it printed. */
    private fun run(command: Array<String>): String? {
        val binder = Shizuku.getBinder() ?: return "Shizuku is not running."
        val process = IShizukuService.Stub.asInterface(binder).newProcess(command, null, null)
        try {
            val err = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).bufferedReader().use { it.readText() }
            val code = process.waitFor()
            return if (code == 0) null else err.trim().ifEmpty { "exit code $code" }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private const val REQUEST_CODE = 7302
}

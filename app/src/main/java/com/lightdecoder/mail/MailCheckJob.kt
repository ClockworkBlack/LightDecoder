package com.lightdecoder.mail

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Checks Gmail for new email in the background, about every 15 minutes (the
 * shortest Android allows), whenever there is a network; Android may run it
 * later to save battery. Scheduled while a Gmail account is added and
 * [MailPrefs.autoCheck] is on; survives restarts.
 */
class MailCheckJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val manager = MailManager.get(this)
        if (!manager.isPaired()) return false
        running = scope.launch {
            val ok = runCatching { manager.syncNow(notify = true) }.isSuccess
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 0x4d41_494c
        private const val PERIOD_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!MailPrefs.autoCheck(context)) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            runCatching {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, MailCheckJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(PERIOD_MS)
                        .setPersisted(true)
                        .build(),
                )
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}

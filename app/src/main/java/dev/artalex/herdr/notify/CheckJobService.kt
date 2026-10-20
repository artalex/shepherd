package dev.artalex.herdr.notify

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.artalex.herdr.HerdrApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The periodic check of [AgentWatch]: every 15 minutes at most, as Android allows (a dozing phone runs it less often),
 * with some network up. Scheduled while notifications are on; Android keeps it across reboots.
 */
class CheckJobService : JobService() {
  private var job: Job? = null

  override fun onStartJob(params: JobParameters): Boolean {
    val container = (application as HerdrApp).container
    job =
      container.scope.launch {
        container.watch.check()
        jobFinished(params, false)
      }
    return true
  }

  override fun onStopJob(params: JobParameters): Boolean {
    job?.cancel()
    return false
  }

  companion object {
    const val JOB_ID = 1
    private const val PERIOD_MS = 15 * 60_000L

    fun schedule(context: Context) {
      val scheduler = context.getSystemService(JobScheduler::class.java)
      // Scheduling it again would start its period over.
      if (scheduler.getPendingJob(JOB_ID) != null) return
      val job =
        JobInfo.Builder(JOB_ID, ComponentName(context, CheckJobService::class.java))
          .setPeriodic(PERIOD_MS)
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
          .setPersisted(true)
          .build()
      scheduler.schedule(job)
    }

    fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
  }
}

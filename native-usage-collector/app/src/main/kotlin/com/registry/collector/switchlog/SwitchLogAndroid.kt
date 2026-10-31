package com.registry.collector.switchlog

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Wraps UsageStatsManager.queryEvents. The only place that touches UsageEvents. */
class UsageEventsSource(private val context: Context) : UsageEventSource {
    override fun events(fromMs: Long, toMs: Long): List<RawEvent> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val events = usm.queryEvents(fromMs, toMs) ?: return emptyList()
        val e = UsageEvents.Event()
        val out = ArrayList<RawEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            out.add(RawEvent(e.timeStamp, e.packageName ?: "", e.eventType))
        }
        return out
    }
}

/** Own SharedPreferences file; commit() so the cursor is durable before returning. */
class SharedPrefsCursorStore(context: Context) : CursorStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun getCursor(): Long? = if (prefs.contains(K_CURSOR)) prefs.getLong(K_CURSOR, 0L) else null

    override fun setCursor(ms: Long) {
        if (!prefs.edit().putLong(K_CURSOR, ms).commit()) throw IOException("cursor commit failed")
    }

    override fun recordRun(ms: Long, events: Int) {
        prefs.edit().putLong(K_LAST_RUN, ms).putInt(K_LAST_EVENTS, events).commit()
    }

    override fun getBackfillEarliest(): Long? = if (prefs.contains(K_EARLIEST)) prefs.getLong(K_EARLIEST, 0L) else null

    override fun setBackfillEarliest(ms: Long) {
        prefs.edit().putLong(K_EARLIEST, ms).commit()
    }

    fun lastRunMs(): Long = prefs.getLong(K_LAST_RUN, 0L)
    fun lastRunEvents(): Int = prefs.getInt(K_LAST_EVENTS, 0)

    companion object {
        const val PREFS = "switch_log_cursor"
        private const val K_CURSOR = "cursor_ms"
        private const val K_LAST_RUN = "last_run_ms"
        private const val K_LAST_EVENTS = "last_run_events"
        private const val K_EARLIEST = "backfill_earliest_ms"
    }
}

fun switchLogDir(context: Context): File = File(context.filesDir, SwitchLogFormat.DIR_NAME)

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Thin shell: no logic, no Firebase, no network. */
class SwitchLogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = SharedPrefsCursorStore(applicationContext)
        if (!hasUsageAccess(applicationContext)) {
            store.recordRun(System.currentTimeMillis(), 0)
            return Result.success()
        }
        val runner = SwitchLogRunner(
            UsageEventsSource(applicationContext),
            switchLogDir(applicationContext),
            System::currentTimeMillis,
            store,
        ) { /* deliberately silent: nothing from this log goes to logcat */ }
        return if (runner.run() is RunResult.Failed) Result.retry() else Result.success()
    }
}

object SwitchLogScheduler {
    private const val PERIODIC = "switch_log_periodic"
    private const val BACKFILL = "switch_log_backfill"

    /** Every 3 h, flex 30 min, no constraints, KEEP. Independent of sign-in. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SwitchLogWorker>(3, TimeUnit.HOURS, 30, TimeUnit.MINUTES).build(),
        )
        enqueueBackfillIfNeeded(context)
    }

    /** One-time run while no cursor exists yet, so the first backfill happens at once (and again after usage access is granted). */
    fun enqueueBackfillIfNeeded(context: Context) {
        if (SharedPrefsCursorStore(context).getCursor() != null) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            BACKFILL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SwitchLogWorker>().build(),
        )
    }
}

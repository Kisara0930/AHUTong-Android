package com.ahu.ahutong.platform

import android.content.Context
import androidx.work.*
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.RuntimePluginLoader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HostPluginWork(private val context: Context) : PluginWorkScheduler {
    private val manager get() = WorkManager.getInstance(context)

    override fun enqueue(pluginId: String, taskId: String, uniqueName: String, initialDelayMs: Long,
        repeatIntervalMs: Long?, network: PluginWorkNetwork, batteryNotLow: Boolean,
        storageNotLow: Boolean, charging: Boolean, replace: Boolean, backoffMs: Long) {
        require(pluginId.matches(Regex("[a-z0-9_]+")) && taskId.isNotBlank() && uniqueName.isNotBlank())
        require(initialDelayMs >= 0 && backoffMs >= 10_000)
        if (!AhupGate.isFeatureEnabled(context, pluginId)) return
        val data = workDataOf("plugin" to pluginId, "task" to taskId,
            "accountId" to SessionStore.currentUser()?.xh.orEmpty())
        val constraints = Constraints.Builder().setRequiredNetworkType(when (network) {
            PluginWorkNetwork.NONE -> NetworkType.NOT_REQUIRED
            PluginWorkNetwork.CONNECTED -> NetworkType.CONNECTED
            PluginWorkNetwork.UNMETERED -> NetworkType.UNMETERED
        }).setRequiresBatteryNotLow(batteryNotLow).setRequiresStorageNotLow(storageNotLow)
            .setRequiresCharging(charging).build()
        val name = name(pluginId, uniqueName)
        if (repeatIntervalMs != null) {
            require(repeatIntervalMs >= 15 * 60_000) { "周期任务间隔至少 15 分钟" }
            val request = PeriodicWorkRequestBuilder<PluginDispatchWorker>(repeatIntervalMs, TimeUnit.MILLISECONDS)
                .setInputData(data).setConstraints(constraints).setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, backoffMs, TimeUnit.MILLISECONDS)
                .addTag(tag(pluginId)).build()
            manager.enqueueUniquePeriodicWork(name, if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            val request = OneTimeWorkRequestBuilder<PluginDispatchWorker>().setInputData(data)
                .setConstraints(constraints).setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, backoffMs, TimeUnit.MILLISECONDS)
                .addTag(tag(pluginId)).build()
            manager.enqueueUniqueWork(name, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }

    override fun cancel(pluginId: String, uniqueName: String) { manager.cancelUniqueWork(name(pluginId, uniqueName)) }
    override fun cancelAll(pluginId: String) { manager.cancelAllWorkByTag(tag(pluginId)) }
    private fun tag(id: String) = "ahup:$id"
    private fun name(id: String, name: String) = "ahup:$id:$name"
}

/** One stable component is declared to WorkManager regardless of the installed business set. */
class PluginDispatchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("plugin") ?: return Result.failure()
        val task = inputData.getString("task") ?: return Result.failure()
        if (!AhupGate.isInitialized(applicationContext)) return Result.success()
        if (!withContext(Dispatchers.IO) { AhupGate.ensureBackgroundReady(applicationContext) })
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        if (!AhupGate.isFeatureEnabled(applicationContext, id)) return Result.success()
        if (inputData.getString("accountId") != SessionStore.currentUser()?.xh.orEmpty()) return Result.success()
        val generation = AccountGeneration.current
        return try {
            val plugin = RuntimePluginLoader.ensureLoaded(applicationContext, id)
            val handler = plugin as? PluginWorkHandler ?: return Result.failure()
            val result = coroutineScope {
                val work = async { handler.run(task) }
                val guard = launch {
                    AccountGeneration.changes.first { it != generation }
                    work.cancel(CancellationException("Account changed"))
                }
                try { work.await() } finally { guard.cancel() }
            }
            if (generation != AccountGeneration.current || !AhupGate.isFeatureEnabled(applicationContext, id)) return Result.success()
            when (result) {
                PluginWorkResult.SUCCESS -> Result.success()
                PluginWorkResult.RETRY -> Result.retry()
                PluginWorkResult.FAILURE -> Result.failure()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }
}

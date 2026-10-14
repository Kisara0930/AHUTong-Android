package com.ahu.ahutong.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ahu.ahutong.core.plugin.*
import com.ahu.ahutong.ui.plugin.AhupGate
import com.ahu.ahutong.ui.plugin.RuntimePluginLoader
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.data.session.SessionStore

class HostComponentLauncher(private val context: Context) : PluginComponentLauncher {
    override fun openIsolatedWeb(pluginId: String, route: String, extras: Bundle) {
        require(pluginId == "student_mail") { "尚未注册此隔离容器" }
        check(AhupGate.isFeatureEnabled(context, pluginId)) { "请先启用此功能" }
        context.startActivity(Intent(context, PluginIsolatedWebActivity::class.java)
            .putExtras(extras).putExtra("plugin_id", pluginId).putExtra("plugin_route", route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class PluginIsolatedWebActivity : ComponentActivity() {
    private var controller: PluginActivityController? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("plugin_id") ?: "student_mail"
        if (id != "student_mail" || !AhupGate.isFeatureEnabled(this, id)) { finish(); return }
        lifecycleScope.launch {
            try {
                val handler = withContext(Dispatchers.IO) {
                    check(AhupGate.ensureBackgroundReady(this@PluginIsolatedWebActivity)) { "请先完成应用初始化或修复存储" }
                    RuntimePluginLoader.ensureLoaded(this@PluginIsolatedWebActivity, id) as? PluginIsolatedWebHandler
                        ?: error("插件不提供隔离网页")
                }
                controller = handler.createController(this@PluginIsolatedWebActivity, intent.getStringExtra("plugin_route") ?: "mailbox")
                controller!!.onCreate(savedInstanceState)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.widget.Toast.makeText(this@PluginIsolatedWebActivity, failure.message ?: "功能暂不可用", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.onNewIntent(intent)
    }
    override fun onDestroy() { controller?.onDestroy(); controller = null; super.onDestroy() }
}

abstract class HostPluginContentProvider(private val pluginId: String) : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "仅允许应用内部调用" }
        val context = requireNotNull(context)
        if (!AhupGate.ensureBackgroundReady(context)) return Bundle().apply { putString("error", "应用初始化或存储校验未完成") }
        if (!AhupGate.isFeatureEnabled(context, pluginId)) return Bundle().apply { putString("error", "功能未启用") }
        return try {
            val handler = RuntimePluginLoader.ensureLoaded(context, pluginId) as? PluginContentCallHandler
                ?: error("插件不提供此服务")
            handler.call(method, arg, extras)
        } catch (_: Exception) { Bundle().apply { putString("error", "服务暂不可用") } }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

class PluginMailSessionProvider : HostPluginContentProvider("student_mail")
class PluginGraduateNoticeProvider : HostPluginContentProvider("campus_notices")

/** Main-process lease check for isolated containers; secondary-process preferences are never authoritative. */
class PluginStateProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "仅允许应用内部调用" }
        val appContext = requireNotNull(context)
        val valid = method == "check" && arg != null && arg.matches(Regex("[a-z0-9_]+")) &&
            extras?.containsKey("account") == true && extras.containsKey("generation") &&
            AhupGate.isBootstrapValidated(appContext) && runCatching {
                AccountGeneration.withCurrent(extras.getLong("generation", -1L)) {
                    AhupGate.isFeatureEnabled(appContext, arg) &&
                        SessionStore.currentUser()?.xh.orEmpty() == extras.getString("account").orEmpty()
                }
            }.getOrDefault(false)
        return Bundle().apply { putBoolean("valid", valid) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

class HostHomeCards(private val context: Context) : PluginHomeCardManager {
    private val preferences = context.getSharedPreferences("ahup_home", Context.MODE_PRIVATE)
    override fun isVisible(id: String) = preferences.getBoolean("visible_$id", true)
    override fun setVisible(id: String, visible: Boolean): Boolean {
        require(id.matches(Regex("[a-z0-9_]+")))
        return preferences.edit().putBoolean("visible_$id", visible).commit()
    }
}

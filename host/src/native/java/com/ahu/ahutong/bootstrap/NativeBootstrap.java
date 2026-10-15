package com.ahu.ahutong.bootstrap;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.*;
import android.os.*;
import android.os.Process;
import com.ahu.ahutong.hostapi.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** This is the entire APK-to-runtime bridge; it never imports a downloadable implementation type. */
public final class NativeBootstrap {
    private static RuntimeGraph graph;
    private static Environment environment;
    private static volatile boolean failed;
    private static final Map<String,Component> components = new LinkedHashMap<>();
    public static synchronized RuntimeGraph prepare(ClassLoader loader, ApplicationInfo info) {
        if (graph != null) return graph;
        try {
            graph = new RuntimeGraph(loader, info);
            File blocked = new File(graph.store.root, "native-runtime-failure.json");
            if (blocked.isFile()) {
                Object previous = Json.object(Json.parse(Files.readAllBytes(blocked.toPath()))).get("versions");
                failed = foundationVersions().equals(previous);
            }
        }
        catch (Throwable error) { android.util.Log.e("NativeBootstrap", "初始化内核无法打开", error); failed = true; }
        return graph;
    }
    public static synchronized RuntimeGraph graph() { return graph; }
    public static synchronized boolean usable() { return !restartProcess() && !failed && graph != null && graph.isUsable(); }
    public static boolean restartProcess() {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName().endsWith(":bootstrap_restart");
        try (InputStream stream = new FileInputStream("/proc/self/cmdline")) {
            byte[] bytes = new byte[256]; int count = stream.read(bytes);
            return count > 0 && new String(bytes, 0, count, StandardCharsets.UTF_8).replace("\u0000", "").endsWith(":bootstrap_restart");
        } catch (Exception error) { return false; }
    }
    public static void runtimeFailure(Throwable error) {
        failed = true;
        if (graph == null) return;
        graph.recordError(error);
        try {
            graph.store.locked(() -> {
                Map<String,Object> report = new LinkedHashMap<>(); report.put("versions", foundationVersions());
                report.put("kind", error.getClass().getName());
                InstallStore.atomic(new File(graph.store.root, "native-runtime-failure.json"), report); return null;
            });
        } catch (Exception storageError) { graph.recordError(storageError); }
    }
    private static Map<String,Object> foundationVersions() throws Exception {
        Map<String,Object> installed = Json.object(graph.store.read().get("installed")), versions = new LinkedHashMap<>();
        for (String id : RuntimeGraph.FOUNDATIONS) if (installed.containsKey(id))
            versions.put(id, Json.object(installed.get(id)).get("activeVersion"));
        return versions;
    }
    public static void allowRuntimeRetry() throws Exception {
        graph.store.locked(() -> {
            File blocked = new File(graph.store.root, "native-runtime-failure.json");
            if (blocked.exists() && !blocked.delete()) throw new IOException("无法解除运行时隔离");
            return null;
        });
    }
    public static void attach(Application application, Context base) {
        RuntimeGraph value = prepare(base.getClassLoader(), base.getApplicationInfo());
        environment(application);
        if (Build.VERSION.SDK_INT < 29 && value != null) {
            // Android 8/9 predate instantiateClassLoader. Keep its narrowly scoped adapter here.
            try {
                Field packageInfo = base.getClass().getDeclaredField("mPackageInfo"); packageInfo.setAccessible(true);
                Object loaded = packageInfo.get(base);
                Field loader = loaded.getClass().getDeclaredField("mClassLoader"); loader.setAccessible(true);
                loader.set(loaded, value.loader);
            } catch (Throwable error) { runtimeFailure(error); }
        }
    }
    public static synchronized HostEnvironment environment(Context context) {
        if (environment == null) environment = new Environment(context);
        return environment;
    }
    public static synchronized Component component(String id) throws Exception {
        if (!usable()) throw new IllegalStateException("请先完成初始化或修复组件");
        Object record = Json.object(graph.store.read().get("installed")).get(Manifest.id(id));
        if (record == null || !Boolean.TRUE.equals(Json.object(record).get("enabled")))
            throw new IllegalStateException(id + " 组件未启用");
        RuntimeGraph.Node node = graph.optional(id);
        if (node == null) throw new IllegalStateException(id + " 未安装，请从市场安装");
        if (node.manifest.version != Json.positive(Json.object(record).get("activeVersion")))
            throw new IllegalStateException(id + " 已更新，请重启应用后使用");
        Component cached = components.get(id);
        if (cached != null) {
            if (cached.version != node.manifest.version) throw new IllegalStateException(id + " 已更新，请重启应用");
            return cached;
        }
        Class<?> type = node.loader.loadClass(node.manifest.entry);
        if (!HostPlugin.class.isAssignableFrom(type)) throw new IllegalArgumentException("组件未实现原生 View 接口: " + id);
        HostPlugin plugin = (HostPlugin)type.getDeclaredConstructor().newInstance();
        File resourceApk = new File(node.directory, "resources.apk");
        Context base = environment.application();
        Resources resources = base.getResources();
        if (resourceApk.isFile()) {
            ApplicationInfo info = Objects.requireNonNull(base.getPackageManager().getPackageArchiveInfo(resourceApk.getAbsolutePath(), 0)).applicationInfo;
            info.sourceDir = resourceApk.getAbsolutePath(); info.publicSourceDir = info.sourceDir;
            resources = base.getPackageManager().getResourcesForApplication(info);
        }
        final Resources own = resources;
        Context context = new ContextWrapper(base) {
            @Override public Resources getResources() { return own; }
            @Override public AssetManager getAssets() { return own.getAssets(); }
            @Override public ClassLoader getClassLoader() { return node.loader; }
            @Override public Context getApplicationContext() { return base.getApplicationContext(); }
        };
        Component component = new Component(id, node.manifest.version, plugin, node.loader, context, node.directory);
        components.put(id, component);
        try { plugin.initialize(environment); }
        catch (Throwable error) {
            components.remove(id); graph.recordError(error);
            if (error instanceof Exception) throw (Exception)error;
            throw new IllegalStateException("组件初始化失败: " + id, error);
        }
        return component;
    }
    public static void restart(Activity activity) {
        activity.startActivity(new Intent(activity, RestartActivity.class).putExtra("previous_pid", Process.myPid())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activity.finishAndRemoveTask();
    }
    private static final class Environment implements HostEnvironment {
        private final Context application;
        Environment(Context application) { this.application = application; }
        @Override public Context application() { return application; }
        @Override public Component component(String id) throws Exception { return NativeBootstrap.component(id); }
        @Override public void ensureInstalled(String[] ids, Progress progress) throws Exception {
            new NativeMarket(application, graph).install(new LinkedHashSet<>(Arrays.asList(ids)), progress);
        }
        @Override public Bundle installedInfo(String id) throws Exception {
            Manifest manifest = graph.store.active(id);
            if (manifest == null) throw new IllegalStateException("组件未安装: " + id);
            Bundle result = new Bundle(); result.putInt("versionCode", manifest.version);
            result.putString("manifest", Json.stringify(manifest.json));
            result.putString("directory", graph.store.directory(id, manifest.version).getAbsolutePath());
            return result;
        }
        @Override public File installationDirectory(String id) throws Exception {
            return graph.store.directory(id, Json.positive(Json.object(Json.object(graph.store.read().get("installed")).get(id)).get("activeVersion")));
        }
        @Override public String marketUrl() {
            // Reuse the original market preference so initialization, settings and repair share one endpoint.
            String configured = application.getSharedPreferences("plugin_shell", Context.MODE_PRIVATE).getString("market_url", null);
            if (configured != null && !configured.isEmpty()) return configured;
            try { return String.valueOf(graph.preferences().getOrDefault("market", "")); } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public boolean privacyAccepted() {
            try { return Boolean.TRUE.equals(graph.preferences().get("privacyAccepted")); } catch (Exception error) { return false; }
        }
        @Override public void startActivityForResult(Activity activity, Intent intent, int requestCode, Bundle options) {
            activity.startActivityForResult(intent, requestCode, options);
        }
        @Override public void requestPermissions(Activity activity, String[] permissions, int requestCode) { activity.requestPermissions(permissions, requestCode); }
        @Override public void schedule(String owner, String task, Bundle payload, long delay, long period, boolean network) {
            Bundle arguments = new Bundle(payload); arguments.putString("owner", Manifest.id(owner)); arguments.putString("task", task);
            arguments.putLong("delay", delay); arguments.putLong("period", period); arguments.putBoolean("network", network);
            dispatch("schedule", arguments);
        }
        @Override public void cancel(String owner, String task) {
            Bundle arguments = new Bundle(); arguments.putString("owner", Manifest.id(owner)); arguments.putString("task", task); dispatch("cancel", arguments);
        }
        private void dispatch(String operation, Bundle arguments) {
            try {
                if (!component("ui_runtime").plugin.dispatch(application, operation, arguments, ignored -> { }))
                    throw new IllegalStateException("运行时未提供系统桥接: " + operation);
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public int bridgeResource(String type, String name) {
            Context base = application instanceof ContextWrapper ? ((ContextWrapper)application).getBaseContext() : application;
            return base.getResources().getIdentifier(name, type, application.getPackageName());
        }
        @Override public <T> T stateTransaction(Callable<T> task) throws Exception { return graph.store.locked(task); }
    }
    private NativeBootstrap() { }
}

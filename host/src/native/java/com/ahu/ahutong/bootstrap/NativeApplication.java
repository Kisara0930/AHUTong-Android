package com.ahu.ahutong.bootstrap;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;

/** Always the framework Application. Downloaded presentation is a delegate, never part of the APK. */
public final class NativeApplication extends Application {
    private Application delegate;
    private Context runtimeContext;
    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(context);
        NativeBootstrap.attach(this, context);
        if (NativeBootstrap.restartProcess()) return;
        RuntimeGraph graph = NativeBootstrap.graph();
        if (graph != null && NativeBootstrap.usable()) {
            try {
                runtimeContext = new RuntimeResources(context, graph);
                NativeBootstrap.environment(this);
                NativeBootstrap.component("shared_runtime");
            } catch (Throwable error) { NativeBootstrap.runtimeFailure(error); }
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        if (NativeBootstrap.restartProcess()) return;
        RuntimeGraph graph = NativeBootstrap.graph();
        if (graph == null || !NativeBootstrap.usable()) return;
        try {
            if (runtimeContext == null) throw new IllegalStateException("基础运行时未能初始化");
            @SuppressWarnings("unchecked") Class<? extends Application> type =
                (Class<? extends Application>)graph.loader.loadClass("com.ahu.ahutong.PluginApplication");
            delegate = Instrumentation.newApplication(type, runtimeContext);
            delegate.onCreate();
            NativeBootstrap.component("ui_runtime");
        } catch (Throwable error) {
            delegate = null; NativeBootstrap.runtimeFailure(error);
        }
    }
    @Override public Context getApplicationContext() { return delegate == null ? this : delegate; }
    @Override public Resources getResources() { return runtimeContext == null ? super.getResources() : runtimeContext.getResources(); }
    @Override public AssetManager getAssets() { return runtimeContext == null ? super.getAssets() : runtimeContext.getAssets(); }
    @Override public ClassLoader getClassLoader() {
        RuntimeGraph graph = NativeBootstrap.graph(); return graph == null ? super.getClassLoader() : graph.loader;
    }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); if (delegate != null) delegate.onTrimMemory(level); }
    @Override public void onLowMemory() { super.onLowMemory(); if (delegate != null) delegate.onLowMemory(); }
}

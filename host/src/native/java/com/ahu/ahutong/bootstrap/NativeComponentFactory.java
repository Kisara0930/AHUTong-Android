package com.ahu.ahutong.bootstrap;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;

/** Android 9+ component factory: framework components link to one downloaded runtime graph. */
@android.annotation.TargetApi(28)
public final class NativeComponentFactory extends AppComponentFactory {
    @Override public ClassLoader instantiateClassLoader(ClassLoader original, ApplicationInfo info) {
        RuntimeGraph graph = NativeBootstrap.prepare(original, info);
        return graph == null ? original : graph.loader;
    }
    private ClassLoader runtime(ClassLoader fallback) {
        RuntimeGraph graph = NativeBootstrap.graph(); return graph == null ? fallback : graph.loader;
    }
    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!NativeBootstrap.usable() && name.equals("com.ahu.ahutong.PluginActivity")) return new BootstrapActivity();
        return super.instantiateActivity(runtime(loader), name, intent);
    }
    @Override public Application instantiateApplication(ClassLoader loader, String name) {
        return new NativeApplication();
    }
    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!NativeBootstrap.usable()) return new DeferredComponents.Provider();
        if (name.equals("androidx.startup.InitializationProvider")) return new DeferredComponents.SafeStartupProvider();
        return super.instantiateProvider(runtime(loader), name);
    }
    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!NativeBootstrap.usable()) return new DeferredComponents.Receiver();
        return super.instantiateReceiver(runtime(loader), name, intent);
    }
    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!NativeBootstrap.usable()) return new DeferredComponents.Service();
        return super.instantiateService(runtime(loader), name, intent);
    }
}

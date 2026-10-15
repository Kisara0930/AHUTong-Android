package com.ahu.ahutong.bootstrap;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.util.List;

/** Public Android resource-context construction; foundation APKs have distinct resource package IDs. */
public final class RuntimeResources extends ContextWrapper {
    private final Resources resources;
    private final ClassLoader loader;
    public RuntimeResources(Context base, RuntimeGraph graph) throws Exception {
        super(base); loader = graph.loader;
        List<File> apks = graph.resourceApks();
        if (Build.VERSION.SDK_INT >= 30) {
            resources = base.getResources();
            ResourcesLoader provider = new ResourcesLoader();
            for (File apk : apks) {
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    provider.addProvider(ResourcesProvider.loadFromApk(descriptor));
                }
            }
            resources.addLoaders(provider);
        } else {
            resources = legacyResources(base, apks);
        }
        // A missing SDK resource table is a repairable initialization error, never a silent UI fallback.
        if (resources.getIdentifier("shared_runtime_identity", "string", "com.ahu.ahutong.component.shared_runtime") == 0)
            throw new java.io.IOException("共享运行时资源表未加载，请修复基础组件");
    }
    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
    @Override public ClassLoader getClassLoader() { return loader; }
    @SuppressWarnings("deprecation") private static Resources legacyResources(Context context, List<File> apks) throws Exception {
        // Isolated Android 8-10 adapter. Modern Android exclusively uses public ResourcesLoader APIs.
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        add.setAccessible(true);
        if ((Integer)add.invoke(assets, context.getApplicationInfo().sourceDir) == 0) throw new java.io.IOException("宿主资源无法加载");
        for (File apk : apks) if ((Integer)add.invoke(assets, apk.getAbsolutePath()) == 0) throw new java.io.IOException("组件资源无法加载");
        return new Resources(assets, context.getResources().getDisplayMetrics(), context.getResources().getConfiguration());
    }
}

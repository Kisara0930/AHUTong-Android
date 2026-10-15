package com.ahu.ahutong.bootstrap;

import android.app.job.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** No optional/background implementation executes before consent and verified initialization. */
public final class DeferredComponents {
    public static final class Service extends JobService {
        @Override public boolean onStartJob(JobParameters parameters) { return false; }
        @Override public boolean onStopJob(JobParameters parameters) { return false; }
    }
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) { }
    }
    public static class Provider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String argument, Bundle values) {
            Bundle result = new Bundle(); result.putString("error", "请先完成初始化或修复组件"); return result;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new IllegalStateException("尚未初始化"); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { return 0; }
        @Override public int delete(Uri uri, String selection, String[] arguments) { return 0; }
    }
    public static final class SafeStartupProvider extends Provider {
        private android.content.pm.ProviderInfo info;
        @Override public void attachInfo(Context context, android.content.pm.ProviderInfo info) {
            this.info = info; super.attachInfo(context, info);
        }
        @Override public boolean onCreate() {
            try {
                ContentProvider delegate = (ContentProvider)NativeBootstrap.graph().loader
                    .loadClass("androidx.startup.InitializationProvider").getDeclaredConstructor().newInstance();
                delegate.attachInfo(getContext(), info);
            } catch (Throwable error) { NativeBootstrap.runtimeFailure(error); }
            return true;
        }
    }
    private DeferredComponents() { }
}

package com.ahu.ahutong.hostapi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;

/** Implementation remains in the native APK; it never links Compose, OkHttp or coroutine types. */
public interface HostEnvironment {
    Context application();
    Component component(String id) throws Exception;
    void ensureInstalled(String[] ids, Progress progress) throws Exception;
    Bundle installedInfo(String id) throws Exception;
    File installationDirectory(String id) throws Exception;
    String marketUrl();
    boolean privacyAccepted();
    void startActivityForResult(Activity activity, Intent intent, int requestCode, Bundle options);
    void requestPermissions(Activity activity, String[] permissions, int requestCode);
    void schedule(String owner, String task, Bundle payload, long delayMillis, long periodMillis, boolean networkRequired);
    void cancel(String owner, String task);
    int bridgeResource(String type, String name);
    <T> T stateTransaction(java.util.concurrent.Callable<T> task) throws Exception;

    interface Progress { void changed(String message, long current, long total); }
}

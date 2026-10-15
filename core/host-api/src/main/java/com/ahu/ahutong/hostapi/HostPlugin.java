package com.ahu.ahutong.hostapi;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

/** Native host boundary: only Android/JDK values cross the dynamically loaded runtime. */
public interface HostPlugin {
    void initialize(HostEnvironment environment) throws Exception;
    View createView(Activity activity, Bundle request) throws Exception;
    default void activityEvent(Activity activity, String event, Bundle values) { }
    default boolean dispatch(Context context, String operation, Bundle values, Reply reply) { return false; }
    default void close() { }

    interface Reply { void complete(Bundle result); }
}

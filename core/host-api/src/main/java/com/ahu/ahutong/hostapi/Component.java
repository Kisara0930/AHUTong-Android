package com.ahu.ahutong.hostapi;

import android.content.Context;
import java.io.File;

/** One verified version and one classloader for a component per process. */
public final class Component {
    public final String id;
    public final int version;
    public final HostPlugin plugin;
    public final ClassLoader loader;
    public final Context context;
    public final File directory;
    public Component(String id, int version, HostPlugin plugin, ClassLoader loader, Context context, File directory) {
        this.id = id; this.version = version; this.plugin = plugin;
        this.loader = loader; this.context = context; this.directory = directory;
    }
}

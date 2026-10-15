package com.ahu.ahutong.bootstrap;

/** Tiny APK-owned JNI bridge selects the process ABI, including Android native-bridge emulators. */
public final class NativeAbi {
    static { System.loadLibrary("bootstrap_abi"); }
    public static native String current();
    private NativeAbi() { }
}

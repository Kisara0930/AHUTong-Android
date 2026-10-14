package com.ahu.ahutong.sdk.nativebridge

/** Only pass a private copy of the old GuiXu directory; keep the original intact. */
object StorageNative {
    @JvmStatic fun load(absolutePath: String) = System.load(absolutePath)
    @JvmStatic external fun readStrings(snapshotPath: String, requestsJson: String): String
    @JvmStatic external fun listKeys(snapshotPath: String): String
}

package com.ahu.ahutong.plugin.campus_notices;

public final class NativeEntry extends com.ahu.ahutong.runtime.ComposePluginEntry {
    @Override protected com.ahu.ahutong.core.plugin.AhuPlugin createImplementation() { return new com.ahu.ahutong.plugin.campus_notices.CampusNoticesPlugin(); }
}

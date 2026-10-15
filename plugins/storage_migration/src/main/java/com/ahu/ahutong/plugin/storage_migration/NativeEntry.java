package com.ahu.ahutong.plugin.storage_migration;

public final class NativeEntry extends com.ahu.ahutong.runtime.ComposePluginEntry {
    @Override protected com.ahu.ahutong.core.plugin.AhuPlugin createImplementation() { return new com.ahu.ahutong.plugin.storage_migration.StorageMigrationPlugin(); }
}

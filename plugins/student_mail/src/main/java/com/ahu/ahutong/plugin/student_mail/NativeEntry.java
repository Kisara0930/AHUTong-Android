package com.ahu.ahutong.plugin.student_mail;

public final class NativeEntry extends com.ahu.ahutong.runtime.ComposePluginEntry {
    @Override protected com.ahu.ahutong.core.plugin.AhuPlugin createImplementation() { return new com.ahu.ahutong.plugin.student_mail.StudentMailPlugin(); }
}

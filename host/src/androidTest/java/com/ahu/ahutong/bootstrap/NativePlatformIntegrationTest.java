package com.ahu.ahutong.bootstrap;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Two invocations (prepare/verify), with a real process stop in between, against the local signed market. */
@RunWith(AndroidJUnit4.class)
public class NativePlatformIntegrationTest {
    @Test public void signedMarketInstallsRuntimeOnceAndRestoresOriginalLogin() throws Exception {
        String phase = InstrumentationRegistry.getArguments().getString("nativePhase");
        Assume.assumeTrue("Requires explicit multi-process fixture", Arrays.asList("prepare", "verify", "native_failure", "native_repair").contains(phase));
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue("Never run fixtures against user installation", context.getPackageName().endsWith(".pluginpreview"));
        RuntimeGraph graph = Objects.requireNonNull(NativeBootstrap.graph());
        File marker = new File(context.getFilesDir(), "native-platform-fixture");
        if ("native_failure".equals(phase)) {
            assertTrue(NativeBootstrap.usable());
            NativeBootstrap.runtimeFailure(new IllegalStateException("Isolated foundation recovery fixture"));
            assertFalse(NativeBootstrap.usable());
            assertTrue(new File(graph.store.root,"native-runtime-failure.json").isFile());
            return;
        }
        if ("native_repair".equals(phase)) {
            assertFalse("A failed foundation set must not execute again on restart",NativeBootstrap.usable());
            Intent launch=Objects.requireNonNull(context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity repair=instrumentation.startActivitySync(launch);
            try {
                assertTrue(repair instanceof BootstrapActivity);
                List<String> repairTexts = Collections.emptyList(); long deadline = System.currentTimeMillis()+10000;
                while (System.currentTimeMillis()<deadline) {
                    instrumentation.waitForIdleSync(); repairTexts=texts(instrumentation.getUiAutomation().getRootInActiveWindow());
                    if (repairTexts.contains("配置你的安大通")) break;
                    Thread.sleep(100);
                }
                assertTrue(repairTexts.toString(),repairTexts.contains("配置你的安大通"));
                NativeBootstrap.allowRuntimeRetry();
                assertFalse(new File(graph.store.root,"native-runtime-failure.json").exists());
            } finally { instrumentation.runOnMainSync(repair::finish); }
            return;
        }
        if ("prepare".equals(phase)) {
            assertFalse("Start from native initialization, not a second SDK copy", NativeBootstrap.usable());
            Map<String,Object> preferences = graph.preferences();
            preferences.put("privacyAccepted", true);
            preferences.put("market", "http://127.0.0.1:8092");
            graph.savePreferences(preferences);
            NativeMarket market = new NativeMarket(context, graph);
            Set<String> selected = new LinkedHashSet<>();
            for (Object value : market.catalog()) {
                Map<String,Object> record = Json.object(value), manifest = Json.object(record.get("manifest"));
                if (!Boolean.FALSE.equals(manifest.get("userVisible"))) selected.add(Json.text(manifest.get("id")));
                if ("shared_runtime".equals(manifest.get("id"))) {
                    // A real verified package must resume from a partial transport file, then pass full hashing/signature checks.
                    Map<String,Object> download=Json.object(record.get("download"));
                    java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:8092"+Json.text(download.get("url"))).openConnection();
                    connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                    connection.setRequestProperty("Range","bytes=0-16383");
                    try {
                        assertEquals(206,connection.getResponseCode());
                        byte[] prefix=RuntimeGraph.read(connection.getInputStream(),16384);assertEquals(16384,prefix.length);
                        File partial=new File(context.getCacheDir(),"native-downloads/shared_runtime-"+Json.text(download.get("sha256"))+".part");
                        Files.createDirectories(partial.toPath().getParent());Files.write(partial.toPath(),prefix);
                    } finally { connection.disconnect(); }
                }
            }
            market.install(selected, null);
            File[] temporary = new File(context.getCacheDir(), "native-downloads").listFiles();
            assertTrue("Successful installation removes downloaded archives", temporary == null || temporary.length == 0);
            Files.write(marker.toPath(), Integer.toString(Process.myPid()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        assertTrue("Prepare fixture first", marker.isFile());
        assertNotEquals("Force-stop before verification", Integer.parseInt(new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.UTF_8)), Process.myPid());
        String diagnostic = new File(context.getFilesDir(), "native-last-error").isFile()
            ? new String(Files.readAllBytes(new File(context.getFilesDir(), "native-last-error").toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
        assertTrue(diagnostic, NativeBootstrap.usable());
        ClassLoader shared = graph.optional("shared_runtime").loader;
        assertSame(shared, graph.optional("shared_runtime").loader);
        assertSame(shared.loadClass("kotlin.Unit"), graph.optional("ui_runtime").loader.loadClass("kotlin.Unit"));
        int resource = context.getResources().getIdentifier("shared_runtime_identity", "string", "com.ahu.ahutong.component.shared_runtime");
        assertEquals("SDK resource table must remain independently linked", 0x70, resource >>> 24);
        Intent intent = Objects.requireNonNull(context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            List<String> texts = Collections.emptyList();
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync(); texts = texts(instrumentation.getUiAutomation().getRootInActiveWindow());
                if (texts.stream().anyMatch(text -> text.contains("学号")) && texts.stream().anyMatch(text -> text.contains("智慧安大"))) break;
                Thread.sleep(100);
            }
            assertTrue(texts.toString(), texts.stream().anyMatch(text -> text.contains("学号")));
            assertTrue(texts.toString(), texts.stream().anyMatch(text -> text.contains("智慧安大")));
            assertFalse(texts.toString(), texts.stream().anyMatch(text -> text.equals("配置你的安大通")));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    private static List<String> texts(AccessibilityNodeInfo node) {
        List<String> result = new ArrayList<>(); if (node == null) return result;
        if (node.getText() != null) result.add(node.getText().toString());
        for (int index=0; index<node.getChildCount(); index++) result.addAll(texts(node.getChild(index)));
        node.recycle(); return result;
    }
}

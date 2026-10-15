package com.ahu.ahutong.bootstrap;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.ahu.ahutong.hostapi.Component;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the actual minified downloaded SDK without linking a second SDK into instrumentation. */
@RunWith(AndroidJUnit4.class)
public class NativeRuntimeBehaviorTest {
    private ClassLoader sdk;
    private Context context;
    private android.app.Instrumentation instrumentation;
    @Test public void installedRuntimePreservesComponentsAndAccountBoundaries() throws Exception {
        String phase = InstrumentationRegistry.getArguments().getString("runtimePhase");
        Assume.assumeTrue(Arrays.asList("components", "session_prepare", "session_verify").contains(phase));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Disposable preview only", context.getPackageName().endsWith(".pluginpreview"));
        assertTrue(NativeBootstrap.usable());
        sdk = NativeBootstrap.graph().optional("shared_runtime").loader;
        assertEquals(Boolean.TRUE, singleton("com.ahu.ahutong.ui.plugin.AhupGate", "ensureBackgroundReady", new Class<?>[]{Context.class},context));
        if (phase.equals("components")) { components(); return; }
        File marker = new File(context.getFilesDir(), "minimal-session-fixture");
        if (phase.equals("session_prepare")) {
            assertNull(singleton("com.ahu.ahutong.data.session.SessionStore", "currentUser"));
            ensureLoaded("account");
            persistUser("minimal-fixture-a");
            singleton("com.ahu.ahutong.data.session.AhuSessionState", "markAnonymous");
            Files.write(marker.toPath(), Integer.toString(Process.myPid()).getBytes(StandardCharsets.UTF_8));
            return;
        }
        assertTrue(marker.isFile());
        assertNotEquals(Integer.parseInt(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8)), Process.myPid());
        try {
            Object restored = singleton("com.ahu.ahutong.data.session.SessionStore", "currentUser");
            assertEquals("minimal-fixture-a", restored.getClass().getMethod("getXh").invoke(restored));
            ensureLoaded("account");
            assertEquals("Authenticated", status());
            verifyHomeCourseAndCampusCard();
            singleton("com.ahu.ahutong.data.session.AhuSessionState", "markExpired");
            Class<?> session = NativeBootstrap.component("account").loader.loadClass("com.ahu.ahutong.plugin.account.AccountSession");
            session.getDeclaredConstructor().newInstance();
            assertEquals("Expired", status());
            singleton("com.ahu.ahutong.data.session.SessionStore", "saveEvalToken", new Class<?>[]{String.class}, "fixture-token-a");
            persistUser("minimal-fixture-b");
            assertNull(singleton("com.ahu.ahutong.data.session.SessionStore", "evalToken"));
            singleton("com.ahu.ahutong.data.session.SessionStore", "saveEvalToken", new Class<?>[]{String.class}, "fixture-token-b");
            persistUser("minimal-fixture-a");
            assertEquals("fixture-token-a", singleton("com.ahu.ahutong.data.session.SessionStore", "evalToken"));
            singleton("com.ahu.ahutong.data.session.SessionStore", "saveEvalToken", new Class<?>[]{String.class}, "");
            persistUser("minimal-fixture-b");
            assertEquals("fixture-token-b", singleton("com.ahu.ahutong.data.session.SessionStore", "evalToken"));
            singleton("com.ahu.ahutong.data.session.SessionStore", "saveEvalToken", new Class<?>[]{String.class}, "");
        } finally {
            singleton("com.ahu.ahutong.data.session.SessionStore", "clearPersistedCurrentUser");
            singleton("com.ahu.ahutong.data.session.AhuSessionState", "markAnonymous");
            marker.delete();
        }
    }
    private void components() throws Exception {
        Map<String,Object> installed = Json.object(NativeBootstrap.graph().store.read().get("installed"));
        for (Map.Entry<String,Object> item : installed.entrySet()) {
            if (!Boolean.TRUE.equals(Json.object(item.getValue()).get("enabled"))) continue;
            String id = item.getKey();
            Component component = NativeBootstrap.component(id);
            assertSame("One shared runtime for " + id, sdk.loadClass("kotlin.Unit"), component.loader.loadClass("kotlin.Unit"));
            if (Arrays.asList(RuntimeGraph.FOUNDATIONS).contains(id)) continue;
            Object plugin = ensureLoaded(id);
            Object meta = plugin.getClass().getMethod("getMeta").invoke(plugin);
            assertEquals(id, meta.getClass().getMethod("getId").invoke(meta));
            if (id.equals("campus_card")) {
                Class<?> bridge = component.loader.loadClass("com.ahu.ahutong.sdk.nativebridge.CampusCardNative");
                bridge.getMethod("ensureLoaded").invoke(bridge.getField("INSTANCE").get(null));
            }
            if (id.equals("account")) {
                Context resources = (Context)singleton("com.ahu.ahutong.ui.plugin.RuntimePluginLoader", "resourceContext", new Class<?>[]{Context.class,String.class}, context, id);
                for (String name : Arrays.asList("emoji_username", "emoji_password", "emoji_left_hand", "emoji_right_hand")) {
                    int value = resources.getResources().getIdentifier(name,"mipmap","com.ahu.ahutong.plugin.account");
                    assertEquals(0x80, value >>> 24); assertNotNull(resources.getDrawable(value));
                }
            }
            if (id.equals("schedule")) {
                RemoteViews widget = (RemoteViews)plugin.getClass().getMethod("renderWidget", Context.class, int.class, Bundle.class)
                    .invoke(plugin, context, 42, new Bundle());
                assertEquals("Launcher template must belong to the native APK", 0x7f, widget.getLayoutId() >>> 24);
                instrumentation.runOnMainSync(() -> assertNotNull(widget.apply(context,new FrameLayout(context))));
            }
        }
        NativeBootstrap.environment(context).schedule("schedule","native-integration",new Bundle(),3600000,0,false);
        NativeBootstrap.environment(context).cancel("schedule","native-integration");
    }
    private Object ensureLoaded(String id) throws Exception {
        final Object[] value = new Object[1]; final Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try { value[0] = singleton("com.ahu.ahutong.ui.plugin.RuntimePluginLoader", "ensureLoaded",new Class<?>[]{Context.class,String.class},context,id); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(id + " failed to load",failure[0]);
        return value[0];
    }
    private Object singleton(String type, String method) throws Exception { return singleton(type,method,new Class<?>[0]); }
    private Object singleton(String type, String method, Class<?>[] arguments, Object... values) throws Exception {
        Class<?> owner = sdk.loadClass(type);
        return owner.getMethod(method,arguments).invoke(owner.getField("INSTANCE").get(null),values);
    }
    private void persistUser(String id) throws Exception {
        Class<?> user = sdk.loadClass("com.ahu.ahutong.data.model.User");
        Object value = user.getDeclaredConstructor().newInstance();
        user.getMethod("setXh",String.class).invoke(value,id);
        user.getMethod("setName",String.class).invoke(value,"Isolated runtime fixture");
        singleton("com.ahu.ahutong.data.session.SessionStore","persistCurrentUser",new Class<?>[]{user},value);
    }
    private String status() throws Exception {
        Object flow = singleton("com.ahu.ahutong.data.session.AhuSessionState","getStatus");
        return ((Enum<?>)sdk.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow)).name();
    }
    private void verifyHomeCourseAndCampusCard() throws Exception {
        ensureLoaded("schedule"); ensureLoaded("campus_card");
        ClassLoader schedule = NativeBootstrap.component("schedule").loader;
        Class<?> cacheType = schedule.loadClass("com.ahu.ahutong.plugin.schedule.ScheduleCache");
        Object companion = cacheType.getField("Companion").get(null);
        Object cache = companion.getClass().getMethod("current").invoke(companion);
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate monday = today.minusDays(today.getDayOfWeek().getValue()-1);
        cacheType.getMethod("saveSchoolYear",String.class).invoke(cache,"2026-2027");
        cacheType.getMethod("saveSchoolTerm",String.class).invoke(cache,"2026-2027-1");
        cacheType.getMethod("saveSchoolTermStartTime",String.class,String.class,String.class).invoke(cache,"2026-2027","1",monday.toString());
        cacheType.getMethod("saveSchoolTermInSemester",String.class,String.class,boolean.class,String.class).invoke(cache,"2026-2027","1",true,today.toString());
        Class<?> courseType = sdk.loadClass("com.ahu.ahutong.data.model.Course");
        Object course = courseType.getDeclaredConstructor().newInstance();
        courseType.getMethod("setName",String.class).invoke(course,"最小宿主课程状态验证");
        courseType.getMethod("setWeekday",String.class).invoke(course,Integer.toString(today.getDayOfWeek().getValue()));
        courseType.getMethod("setStartTime",String.class).invoke(course,"1");
        courseType.getMethod("setLength",String.class).invoke(course,"1");
        courseType.getMethod("setClockRange",String.class).invoke(course,"00:00-23:59");
        courseType.getMethod("setLocation",String.class).invoke(course,"测试教室");
        courseType.getMethod("setWeekIndexes",List.class).invoke(course,Collections.singletonList(1));
        cacheType.getMethod("saveSchedule",String.class,List.class).invoke(cache,"2026-2027-1",Collections.singletonList(course));
        Intent intent = Objects.requireNonNull(context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            List<String> texts = Collections.emptyList(); long deadline = System.currentTimeMillis()+20000;
            while (System.currentTimeMillis()<deadline) {
                instrumentation.waitForIdleSync(); texts=texts(instrumentation.getUiAutomation().getRootInActiveWindow());
                if (texts.stream().anyMatch(value -> value.contains("最小宿主课程状态验证")) && texts.stream().anyMatch(value -> value.contains("校园卡余额"))) break;
                Thread.sleep(100);
            }
            assertTrue(texts.toString(),texts.stream().anyMatch(value -> value.contains("最小宿主课程状态验证")));
            assertTrue(texts.toString(),texts.stream().anyMatch(value -> value.contains("正在上课")));
            assertTrue(texts.toString(),texts.stream().anyMatch(value -> value.contains("校园卡余额")));
            android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            if (screenshot != null) try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(context.getExternalFilesDir(null),"native-home-fixture.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
            }
            assertTrue("Original settings navigation must remain clickable",clickText(instrumentation.getUiAutomation().getRootInActiveWindow(),"设置"));
            List<String> settings=awaitText("偏好设置");
            assertTrue(settings.toString(),settings.contains(context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName));
            assertTrue(clickText(instrumentation.getUiAutomation().getRootInActiveWindow(),"偏好设置"));
            awaitText("外观扩展");
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
    private List<String> awaitText(String expected) throws Exception {
        List<String> values=Collections.emptyList();long deadline=System.currentTimeMillis()+20000;
        while(System.currentTimeMillis()<deadline){
            instrumentation.waitForIdleSync();values=texts(instrumentation.getUiAutomation().getRootInActiveWindow());
            if(values.contains(expected))return values;Thread.sleep(100);
        }
        throw new AssertionError("Missing "+expected+": "+values);
    }
    private static boolean clickText(AccessibilityNodeInfo node,String expected){
        if(node==null)return false;
        try {
            if(expected.contentEquals(node.getText()==null?"":node.getText())){
                AccessibilityNodeInfo candidate=AccessibilityNodeInfo.obtain(node);
                while(candidate!=null){
                    if(candidate.isClickable()){boolean clicked=candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK);candidate.recycle();return clicked;}
                    AccessibilityNodeInfo parent=candidate.getParent();candidate.recycle();candidate=parent;
                }
            }
            for(int i=0;i<node.getChildCount();i++)if(clickText(node.getChild(i),expected))return true;
            return false;
        } finally {node.recycle();}
    }
    private static List<String> texts(AccessibilityNodeInfo node) {
        List<String> result=new ArrayList<>(); if(node==null)return result;
        if(node.getText()!=null)result.add(node.getText().toString());
        for(int i=0;i<node.getChildCount();i++)result.addAll(texts(node.getChild(i)));
        node.recycle();return result;
    }
}

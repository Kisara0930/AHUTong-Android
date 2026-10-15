package com.ahu.ahutong.bootstrap;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Native-only consent, selection, progress and recovery. No feature UI is replaced by this screen. */
public final class BootstrapActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private RuntimeGraph graph;
    private LinearLayout content;
    private LinearLayout choices;
    private TextView status;
    private EditText address;
    private Button action;
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<PackageVerifier.Candidate> local = new ArrayList<>();
    private boolean busy;
    private volatile boolean destroyed;
    private Future<?> active;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); graph = NativeBootstrap.graph();
        if (graph == null) { TextView error = new TextView(this); error.setText("初始化内核无法打开。请重新安装完整签名 APK，用户数据不会被清除。"); setContentView(error); return; }
        try {
            Map<String,Object> installed = Json.object(graph.store.read().get("installed"));
            for (Map.Entry<String,Object> entry : installed.entrySet()) if (Boolean.TRUE.equals(Json.object(entry.getValue()).get("enabled"))) selected.add(entry.getKey());
            Object pending = graph.preferences().get("pendingSelection");
            if (pending != null) {
                List<Object> ids = Json.array(pending);
                if (ids.size() > 128) throw new IOException("恢复的功能选择过大");
                selected.clear(); for (Object id : ids) selected.add(Manifest.id(Json.text(id)));
            }
            show();
        } catch (Exception error) { renderError(error); }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setPadding(0, dp(8), 0, dp(8)); content.addView(label); return label;
    }
    private Button button(String label, Runnable listener) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(ignored -> listener.run()); content.addView(button); return button;
    }
    private void show() throws Exception {
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(28)); scroll.addView(content); setContentView(scroll);
        text("配置你的安大通", 26);
        status = text("初始化完成后进入原来的登录页、首页和导航。", 15);
        Map<String,Object> preferences = graph.preferences();
        if (!Boolean.TRUE.equals(preferences.get("privacyAccepted"))) {
            LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(20), dp(28), dp(20), dp(28));
            TextView heading = new TextView(this); heading.setText("隐私政策"); heading.setTextSize(24); page.addView(heading);
            ScrollView policy = new ScrollView(this); TextView body = new TextView(this); body.setTextSize(14); body.setTextIsSelectable(true);
            int privacy = getResources().getIdentifier("privacy_policy", "raw", getPackageName());
            if (privacy != 0) body.setText(new String(RuntimeGraph.read(getResources().openRawResource(privacy), 256 * 1024), StandardCharsets.UTF_8));
            policy.addView(body); page.addView(policy, new LinearLayout.LayoutParams(-1, 0, 1));
            CheckBox accept = new CheckBox(this); accept.setText("我已阅读并同意隐私政策"); page.addView(accept);
            action = new Button(this); action.setText("同意并配置功能"); action.setOnClickListener(ignored -> {
                if (!accept.isChecked()) { accept.setError("请先阅读并确认隐私政策"); return; }
                try { preferences.put("privacyAccepted", true); graph.savePreferences(preferences); show(); } catch (Exception error) { renderError(error); }
            });
            page.addView(action); setContentView(page);
            return;
        }
        text("组件市场", 20);
        address = new EditText(this); address.setSingleLine(true); address.setHint("https://… 或本机测试市场地址");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(NativeBootstrap.environment(this).marketUrl()); content.addView(address);
        button("读取市场与功能列表", () -> {
            String value = address.getText().toString().trim();
            execute(() -> {
            NativeMarket.checked(value);
            configureMarket(value);
            List<Object> catalog = new NativeMarket(this, graph).catalog(); post(() -> displayCatalog(catalog));
            });
        });
        choices = new LinearLayout(this); choices.setOrientation(LinearLayout.VERTICAL); content.addView(choices);
        button("导入本地 AHUP 文件", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, 7301);
        });
        button("导入本地组件目录", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 7302));
        action = button("下载安装所选功能", () -> {
            Set<String> snapshot = new LinkedHashSet<>(selected);
            String value = address.getText().toString().trim();
            execute(() -> {
                NativeMarket.checked(value);
                configureMarket(value);
                new NativeMarket(this, graph).install(snapshot, this::progress, false);
                post(this::completed);
            });
        });
        File last = new File(getFilesDir(), "native-last-error");
        if (last.isFile()) text("上次初始化诊断：\n" + new String(Files.readAllBytes(last.toPath()), StandardCharsets.UTF_8), 13);
        text("账户、基础界面和共享运行时为必需组件。未启用的功能不会加载；安装暂存包验证后清理，原有用户数据保持不变。", 14);
        text("修复与回滚", 20);
        button("恢复上一组兼容组件", () -> execute(() -> {
            boolean restored = graph.store.rollbackGraph(graph.api(), graph.runtime(), Build.VERSION.SDK_INT, Collections.singletonList(NativeAbi.current()));
            post(() -> {
                status.setText(restored ? "已恢复上一组兼容组件，用户数据保留。请重新启动应用。" : "没有可恢复的兼容组件组，可导入或下载官方修复组件。");
                if (restored) { action.setText("重启安大通"); action.setOnClickListener(ignored -> NativeBootstrap.restart(this)); }
            });
        }));
        if (new File(graph.store.root, "native-runtime-failure.json").isFile()) button("手动重试已隔离的基础运行时", () -> execute(() -> {
            NativeBootstrap.allowRuntimeRetry(); post(() -> NativeBootstrap.restart(this));
        }));
        EditText repairId = new EditText(this); repairId.setSingleLine(true); repairId.setHint("需要修复的组件 ID，如 ui_runtime"); content.addView(repairId);
        button("恢复上一正常组件版本", () -> {
            String id = repairId.getText().toString().trim();
            execute(() -> {
                boolean restored = graph.store.rollback(Manifest.id(id), graph.api(), graph.runtime(), Build.VERSION.SDK_INT, Collections.singletonList(NativeAbi.current()));
                post(() -> {
                    if (restored) {
                        status.setText("已恢复上一正常组件版本，用户数据保留。请重新启动应用。");
                        action.setText("重启安大通"); action.setOnClickListener(ignored -> NativeBootstrap.restart(this));
                    } else status.setText("没有可回退的版本，可从市场重新下载或导入官方修复组件。原有数据保留。");
                });
            });
        });
    }
    private void configureMarket(String value) throws Exception {
        NativeMarket.checked(value);
        Map<String,Object> preferences = graph.preferences(); preferences.put("market", value); graph.savePreferences(preferences);
        if (!getSharedPreferences("plugin_shell", MODE_PRIVATE).edit().putString("market_url", value).commit()) throw new IOException("无法保存市场配置");
    }
    private void displayCatalog(List<Object> records) {
        choices.removeAllViews();
        TextView title = new TextView(this); title.setText("选择需要的功能"); title.setTextSize(20); choices.addView(title);
        for (Object value : records) {
            Map<String,Object> record = Json.object(value), manifest = Json.object(record.get("manifest"));
            String id = Json.text(manifest.get("id"));
            if (Boolean.FALSE.equals(manifest.get("userVisible")) || required().contains(id)) continue;
            CheckBox choice = new CheckBox(this); choice.setText(Json.text(manifest.get("title")) + " · " + Json.text(manifest.get("versionName")));
            choice.setChecked(selected.contains(id)); choice.setOnCheckedChangeListener((button, checked) -> { if (checked) selected.add(id); else selected.remove(id); });
            choices.addView(choice);
        }
    }
    private Set<String> required() {
        Set<String> ids = new LinkedHashSet<>(); for (Object id : Json.array(graph.configuration.get("required"))) ids.add(Json.text(id)); return ids;
    }
    private void execute(Work work) {
        if (busy) return; busy = true; if (action != null) action.setEnabled(false); status.setText("正在处理组件…");
        active = executor.submit(() -> {
            try { work.run(); } catch (Throwable error) { post(() -> renderError(error)); }
            finally { post(() -> { busy = false; if (action != null) action.setEnabled(true); }); }
        });
    }
    private void progress(String message, long current, long total) {
        post(() -> status.setText(message + (total > 0 ? " · " + current + "/" + total : "")));
    }
    private void renderError(Throwable error) {
        graph.recordError(error);
        if (status != null) status.setText("未完成初始化：" + String.valueOf(error.getMessage()) + "\n原有数据已保留，可重试下载或重新导入官方组件。");
        else { TextView label = new TextView(this); label.setText("初始化失败，原有数据已保留：" + error.getMessage()); setContentView(label); }
    }
    private void completed() {
        status.setText("组件已验证安装。安装暂存包已清理，重启后进入安大通。");
        action.setText("进入安大通"); action.setOnClickListener(ignored -> NativeBootstrap.restart(this)); action.setEnabled(true);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (requestCode == 7301) {
            if (data.getClipData() != null) for (int i=0;i<data.getClipData().getItemCount();i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
        } else if (requestCode == 7302 && data.getData() != null) {
            Uri tree = data.getData();
            try (android.database.Cursor cursor = getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (cursor != null && cursor.moveToNext()) if (cursor.getString(1).toLowerCase(Locale.ROOT).endsWith(".ahup"))
                    uris.add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
            } catch (Exception error) { renderError(error); return; }
        }
        if (uris.isEmpty()) return;
        execute(() -> {
            if (uris.size() > 128) throw new IOException("一次最多导入 128 个组件");
            for (PackageVerifier.Candidate candidate : local) candidate.close(); local.clear();
            try {
                for (Uri uri : uris) {
                    File archive = File.createTempFile("ahup-import-", ".tmp", getCacheDir());
                    try {
                        try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(archive)) {
                            if (input == null) throw new IOException("无法读取组件文件");
                            byte[] bytes = new byte[32768]; long size=0; int count;
                            while ((count=input.read(bytes))!=-1) { size+=count; if(size>PackageVerifier.MAX_TOTAL)throw new IOException("组件包过大"); output.write(bytes,0,count); }
                            output.getFD().sync();
                        }
                        PackageVerifier.Candidate candidate = graph.verifier.inspect(archive, new File(graph.store.root,"inspection"));
                        candidate.manifest.compatible(graph.api(),graph.runtime(),Build.VERSION.SDK_INT,Collections.singletonList(NativeAbi.current()));
                        local.add(candidate);
                    } finally { if (!archive.delete() && archive.exists()) throw new IOException("无法清理导入暂存包"); }
                }
                post(() -> {
                    List<Object> records = new ArrayList<>();
                    for (PackageVerifier.Candidate candidate : local) { Map<String,Object> record=new LinkedHashMap<>(); record.put("manifest",candidate.manifest.json); records.add(record); }
                    displayCatalog(records); status.setText("本地组件验签通过。选择功能后完成初始化。");
                    action.setText("完成初始化，进入安大通"); action.setOnClickListener(ignored -> installLocal());
                });
            } catch (Throwable error) { for(PackageVerifier.Candidate candidate:local)candidate.close();local.clear();throw error; }
        });
    }
    private void installLocal() {
        Set<String> snapshot = new LinkedHashSet<>(selected);
        execute(() -> {
            Map<String,Manifest> manifests = new LinkedHashMap<>();
            Map<String,Object> installed = Json.object(graph.store.read().get("installed"));
            for (String id : installed.keySet()) {
                Manifest manifest = graph.store.active(id);
                if (manifest != null && manifest.hostApi == graph.api() && manifest.runtime.equals(graph.runtime())) manifests.put(id, manifest);
            }
            for (PackageVerifier.Candidate candidate : local) manifests.put(candidate.manifest.id,candidate.manifest);
            Set<String> requested=new LinkedHashSet<>(snapshot);requested.addAll(required());
            if(new File(getFilesDir(),"rust-sdk").exists())requested.add("storage_migration");
            Set<String> enabled=NativeMarket.closure(requested,manifests);
            List<PackageVerifier.Candidate> needed = new ArrayList<>();
            for (PackageVerifier.Candidate candidate : local) if (enabled.contains(candidate.manifest.id)) needed.add(candidate);
            try {
                graph.store.install(needed,required(),enabled,graph.api(),graph.runtime(),Build.VERSION.SDK_INT,Collections.singletonList(NativeAbi.current()));
                Map<String,Object> preferences = graph.preferences(); preferences.remove("pendingSelection"); graph.savePreferences(preferences);
            }
            finally { for(PackageVerifier.Candidate candidate:local)candidate.close();local.clear(); }
            post(this::completed);
        });
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (active != null) active.cancel(true);
        // Cleanup runs after the interrupted worker, so extraction never races candidate disposal.
        executor.submit(() -> {
            for (PackageVerifier.Candidate candidate : local) try { candidate.close(); } catch (IOException error) { graph.recordError(error); }
            local.clear();
        });
        executor.shutdown();
        super.onDestroy();
    }
    private void post(Runnable work) { runOnUiThread(() -> { if (!destroyed) work.run(); }); }
    private interface Work { void run() throws Exception; }
}

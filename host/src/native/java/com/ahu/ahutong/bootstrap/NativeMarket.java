package com.ahu.ahutong.bootstrap;

import android.content.Context;
import com.ahu.ahutong.hostapi.HostEnvironment;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public marketplace transport, available before any shared HTTP implementation is downloaded. */
public final class NativeMarket {
    private final Context context;
    private final RuntimeGraph graph;
    public NativeMarket(Context context, RuntimeGraph graph) { this.context = context; this.graph = graph; }
    public static URI checked(String value) throws Exception {
        URI uri = new URI(value.trim());
        boolean local = Arrays.asList("127.0.0.1", "localhost", "::1", "[::1]", "10.0.2.2").contains(uri.getHost());
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null ||
                (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && local)))
            throw new IllegalArgumentException("市场必须使用 HTTPS，本机测试地址可使用 HTTP");
        return uri;
    }
    private URI endpoint(String path) throws Exception {
        String base = NativeBootstrap.environment(context).marketUrl();
        if (base.isEmpty()) throw new IOException("请先填写市场地址或导入本地组件");
        return checked(base.endsWith("/") ? base : base + "/").resolve(path.startsWith("/") ? path.substring(1) : path);
    }
    private Map<String,Object> compatibility() {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("hostApiVersion", (long)graph.api()); value.put("runtimeVersion", graph.runtime());
        value.put("sdk", (long)android.os.Build.VERSION.SDK_INT);
        value.put("abis", Collections.singletonList(NativeAbi.current())); return value;
    }
    private Map<String,Object> request(String path, Map<String,Object> body) throws Exception {
        if (!Boolean.TRUE.equals(graph.preferences().get("privacyAccepted"))) throw new IllegalStateException("请先阅读并确认隐私政策");
        URI uri = endpoint(path);
        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "AHUTong-NativeInit/2");
        try {
            if (body != null) {
                byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("市场请求失败 HTTP " + status);
            return Json.object(Json.parse(RuntimeGraph.read(connection.getInputStream(), 1024 * 1024)));
        } finally { connection.disconnect(); }
    }
    public List<Object> catalog() throws Exception {
        String query = "v1/plugins?hostApiVersion=" + graph.api() + "&runtimeVersion=" + URLEncoder.encode(graph.runtime(), "UTF-8") +
            "&sdk=" + android.os.Build.VERSION.SDK_INT + "&abis=" + URLEncoder.encode(NativeAbi.current(), "UTF-8");
        return Json.array(request(query, null).get("plugins"));
    }
    public void install(Set<String> requested, HostEnvironment.Progress progress) throws Exception {
        install(requested, progress, true);
    }
    public void install(Set<String> requested, HostEnvironment.Progress progress, boolean preserveExistingSelection) throws Exception {
        Set<String> selected = new LinkedHashSet<>(requested);
        Map<String,Object> state = graph.store.read(), installed = Json.object(state.get("installed"));
        Map<String,Object> compatible = new LinkedHashMap<>();
        Map<String,Manifest> verified = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : installed.entrySet()) {
            if ((!preserveExistingSelection || !Boolean.TRUE.equals(Json.object(entry.getValue()).get("enabled"))) && !requested.contains(entry.getKey())) continue;
            Manifest manifest = graph.store.active(entry.getKey());
            if (preserveExistingSelection && Boolean.TRUE.equals(Json.object(entry.getValue()).get("enabled"))) selected.add(entry.getKey());
            if (manifest.hostApi == graph.api() && manifest.runtime.equals(graph.runtime())) {
                compatible.put(entry.getKey(), (long)manifest.version);
                verified.put(entry.getKey(), manifest);
            }
        }
        for (Object id : Json.array(graph.configuration.get("required"))) selected.add(Json.text(id));
        if (new File(context.getFilesDir(), "rust-sdk").exists() || new File(context.getFilesDir(), "ahup_migrations/guixu-v1/state.json").exists())
            selected.add("storage_migration");
        Map<String,Object> preferences = graph.preferences();
        preferences.put("pendingSelection", new ArrayList<>(selected)); graph.savePreferences(preferences);
        Map<String,Object> body = compatibility(); body.put("installed", compatible); body.put("requested", new ArrayList<>(selected));
        Map<String,Object> resolved = request("v1/resolve", body);
        List<PackageVerifier.Candidate> candidates = new ArrayList<>();
        List<File> archives = new ArrayList<>();
        boolean committed = false;
        try {
            List<Object> downloads = Json.array(resolved.get("downloads"));
            int index = 0;
            for (Object record : downloads) {
                Map<String,Object> item = Json.object(record), metadata = Json.object(item.get("manifest"));
                String id = Manifest.id(Json.text(metadata.get("id")));
                if (progress != null) progress.changed("下载并验证 " + Json.text(metadata.get("title")), index++, downloads.size());
                File archive = download(id, Json.object(item.get("download")), progress);
                archives.add(archive);
                try {
                    PackageVerifier.Candidate candidate = graph.verifier.inspect(archive, new File(graph.store.root, "inspection"));
                    if (!candidate.manifest.id.equals(id) || candidate.manifest.version != Json.positive(metadata.get("versionCode"))) {
                        candidate.close(); throw new IOException("下载内容与依赖计划不一致");
                    }
                    candidates.add(candidate);
                    verified.put(id, candidate.manifest);
                } catch (Exception error) {
                    // Invalid packages are not retained; valid completed downloads survive a later interruption.
                    archive.delete(); throw error;
                }
            }
            Set<String> enabled = closure(selected, verified);
            List<PackageVerifier.Candidate> needed = new ArrayList<>();
            for (PackageVerifier.Candidate candidate : candidates) if (enabled.contains(candidate.manifest.id)) needed.add(candidate);
            Set<String> required = new LinkedHashSet<>();
            for (Object id : Json.array(graph.configuration.get("required"))) required.add(Json.text(id));
            graph.store.install(needed, required, enabled, graph.api(), graph.runtime(), android.os.Build.VERSION.SDK_INT,
                Collections.singletonList(NativeAbi.current()));
            committed = true;
            preferences = graph.preferences(); preferences.remove("pendingSelection"); graph.savePreferences(preferences);
        } finally {
            for (PackageVerifier.Candidate candidate : candidates) candidate.close();
            if (committed) for (File archive : archives) if (!archive.delete() && archive.exists())
                throw new IOException("无法清理已安装组件的暂存包");
        }
    }
    public static Set<String> closure(Set<String> selected, Map<String,Manifest> manifests) throws Exception {
        Set<String> result = new LinkedHashSet<>(); List<String> pending = new ArrayList<>(selected);
        for (int index = 0; index < pending.size(); index++) {
            String id = pending.get(index);
            if (!result.add(id)) continue;
            if (result.size() > 128) throw new IOException("依赖数量超出限制");
            Manifest manifest = manifests.get(id);
            if (manifest == null) throw new IOException("依赖计划缺少所选组件: " + id);
            pending.addAll(manifest.dependencies.keySet());
        }
        return result;
    }
    private File download(String id, Map<String,Object> metadata, HostEnvironment.Progress progress) throws Exception {
        long expectedSize = Json.number(metadata.get("size")); String sha = Json.text(metadata.get("sha256"));
        if (expectedSize < 1 || expectedSize > PackageVerifier.MAX_TOTAL || !sha.matches("[a-f0-9]{64}")) throw new IOException("无效下载记录");
        File cache = new File(context.getCacheDir(), "native-downloads");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("无法创建下载目录");
        File file = new File(cache, id + "-" + sha + ".part");
        long offset = file.isFile() ? file.length() : 0;
        if (offset > expectedSize) { if (!file.delete()) throw new IOException("暂存包损坏"); offset = 0; }
        if (offset != expectedSize) {
            URI uri = endpoint(Json.text(metadata.get("url")));
            HttpURLConnection connection = null;
            try {
                for (int hop = 0; hop < 6; hop++) {
                    connection = (HttpURLConnection)checked(uri.toString()).toURL().openConnection();
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
                    connection.setRequestProperty("User-Agent", "AHUTong-NativeInit/2");
                    if (offset > 0) { connection.setRequestProperty("Range", "bytes=" + offset + "-"); connection.setRequestProperty("If-Range", "\"" + sha + "\""); }
                    int status = connection.getResponseCode();
                    if (status >= 300 && status <= 399) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("重定向缺少地址");
                        URI target = checked(uri.resolve(location).toString());
                        if ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) throw new IOException("拒绝下载降级跳转");
                        connection.disconnect(); connection = null; uri = target; continue;
                    }
                    if (status != 200 && status != 206) throw new IOException("组件下载失败 HTTP " + status);
                    if (status == 206) {
                        String range = connection.getHeaderField("Content-Range");
                        if (!Objects.equals(range, "bytes " + offset + "-" + (expectedSize - 1) + "/" + expectedSize)) throw new IOException("续传范围不一致");
                    } else offset = 0;
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(file, offset > 0)) {
                        byte[] buffer = new byte[32768]; int count; long size = offset;
                        while ((count = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已暂停，可再次继续");
                            size += count; if (size > expectedSize) throw new IOException("组件下载超过声明大小");
                            output.write(buffer, 0, count);
                            if (progress != null) progress.changed("下载 " + id, size, expectedSize);
                        }
                        output.getFD().sync();
                    }
                    break;
                }
            } finally { if (connection != null) connection.disconnect(); }
        }
        if (file.length() != expectedSize || !PackageVerifier.sha256(file).equals(sha)) {
            if (file.exists() && !file.delete()) throw new IOException("校验失败且暂存包无法清理");
            throw new IOException("下载完整性校验失败，请重试");
        }
        return file;
    }
}

package com.ahu.ahutong.bootstrap;

import java.util.*;

/** Signed AHUP metadata; no application/runtime libraries are necessary to parse it. */
public final class Manifest {
    public final Map<String,Object> json;
    public final String id, title, entry, runtime;
    public final int version, hostApi, minSdk, dataVersion;
    public final boolean required, visible;
    public final List<String> abis;
    public final Map<String,Payload> files = new LinkedHashMap<>();
    public final Map<String,Dependency> dependencies = new LinkedHashMap<>();
    public Manifest(byte[] raw) throws Exception {
        json = Json.object(Json.parse(raw));
        if (Json.positive(json.get("formatVersion")) != 3 || Boolean.FALSE.equals(json.get("release")))
            throw new IllegalArgumentException("仅接受签名的正式 AHUP v3");
        id = id(Json.text(json.get("id")));
        title = Json.text(json.get("title"));
        entry = Json.text(json.get("entryClass"));
        if (!entry.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")) throw new IllegalArgumentException("无效入口类");
        version = Json.positive(json.get("versionCode"));
        hostApi = Json.positive(json.get("hostApiVersion"));
        minSdk = Json.positive(json.get("minSdk"));
        dataVersion = Json.positive(json.getOrDefault("dataVersion", 1L));
        runtime = Json.text(json.get("runtimeVersion"));
        if (runtime.isEmpty() || runtime.length() > 128) throw new IllegalArgumentException("无效运行时版本");
        required = Boolean.TRUE.equals(json.get("required"));
        visible = !Boolean.FALSE.equals(json.get("userVisible"));
        abis = new ArrayList<>();
        for (Object abi : Json.array(json.get("abis"))) {
            String value = Json.text(abi);
            if (!Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(value)) throw new IllegalArgumentException("无效 ABI");
            abis.add(value);
        }
        for (Object value : Json.array(json.get("dependencies"))) {
            Map<String,Object> object = Json.object(value);
            String name = id(Json.text(object.get("id")));
            int minimum = Json.positive(object.get("minVersion"));
            int maximum = object.get("maxVersion") == null ? Integer.MAX_VALUE : Json.positive(object.get("maxVersion"));
            if (name.equals(id) || maximum < minimum || dependencies.put(name, new Dependency(minimum, maximum)) != null)
                throw new IllegalArgumentException("无效或重复依赖");
        }
        if (dependencies.size() > 128) throw new IllegalArgumentException("依赖数量过多");
        long total = 0;
        for (Object value : Json.array(json.get("files"))) {
            Map<String,Object> object = Json.object(value);
            String path = path(Json.text(object.get("path")));
            String sha = Json.text(object.get("sha256"));
            long size = Json.number(object.get("size"));
            if (path.equals("manifest.json") || path.equals("signature.sig") || !sha.matches("[a-f0-9]{64}") ||
                    size < 0 || size > PackageVerifier.MAX_FILE || files.put(path, new Payload(path, sha, size)) != null)
                throw new IllegalArgumentException("无效载荷记录");
            total += size;
            if (total > PackageVerifier.MAX_TOTAL) throw new IllegalArgumentException("解压大小过大");
        }
        if (files.isEmpty() || files.size() > 4094 || files.keySet().stream().noneMatch(p -> p.matches("(?:dex/)?classes(?:[2-9]|[1-9][0-9]+)?\\.dex")))
            throw new IllegalArgumentException("DEX 载荷缺失或数量无效");
    }
    public void compatible(int api, String runtime, int sdk, List<String> deviceAbis) {
        if (hostApi != api || !this.runtime.equals(runtime) || minSdk > sdk ||
                (!abis.isEmpty() && Collections.disjoint(abis, deviceAbis)))
            throw new IllegalArgumentException(id + " 组件与宿主、运行时或设备不兼容，请安装新版组件");
    }
    public static String id(String value) {
        if (!value.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("无效组件 ID");
        return value;
    }
    public static String path(String value) {
        if (value.isEmpty() || value.startsWith("/") || value.contains("\\") || value.contains(":") || value.indexOf(0) >= 0)
            throw new IllegalArgumentException("不安全的载荷路径");
        for (String segment : value.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
            throw new IllegalArgumentException("不安全的载荷路径");
        return value;
    }
    public static final class Payload {
        public final String path, sha256; public final long size;
        Payload(String path, String sha256, long size) { this.path = path; this.sha256 = sha256; this.size = size; }
    }
    public static final class Dependency {
        public final int min, max;
        Dependency(int min, int max) { this.min = min; this.max = max; }
        public boolean accepts(int value) { return value >= min && value <= max; }
    }
}

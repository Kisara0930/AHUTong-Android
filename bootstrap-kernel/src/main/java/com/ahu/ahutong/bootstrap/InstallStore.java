package com.ahu.ahutong.bootstrap;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Shares the existing ahup-v3 state/schema and never touches application credentials or feature data. */
public final class InstallStore {
    public final File root;
    private final PackageVerifier verifier;
    private static final Map<String,Object> LOCKS = new HashMap<>();
    private static final ThreadLocal<Set<String>> HELD = ThreadLocal.withInitial(HashSet::new);
    private final String lockId;
    private final Object monitor;
    public InstallStore(File root, PackageVerifier verifier) throws IOException {
        this.root = root; this.verifier = verifier;
        Files.createDirectories(root.toPath()); lockId = root.getCanonicalPath();
        synchronized (LOCKS) { monitor = LOCKS.computeIfAbsent(lockId, ignored -> new Object()); }
    }
    public <T> T locked(Callable<T> task) throws Exception {
        synchronized (monitor) {
            if (HELD.get().contains(lockId)) return task.call();
            try (RandomAccessFile file = new RandomAccessFile(new File(root, "state.lock"), "rw");
                 java.nio.channels.FileLock ignored = file.getChannel().lock()) {
                HELD.get().add(lockId);
                try { return task.call(); } finally { HELD.get().remove(lockId); }
            }
        }
    }
    public Map<String,Object> read() throws Exception { return locked(this::readUnlocked); }
    private Map<String,Object> readUnlocked() throws Exception {
        File path = new File(root, "state.json");
        Map<String,Object> value;
        if (path.isFile()) value = Json.object(Json.parse(Files.readAllBytes(path.toPath())));
        else {
            value = new LinkedHashMap<>(); value.put("installed", new LinkedHashMap<String,Object>());
            value.put("required", new ArrayList<>()); value.put("initialized", false);
        }
        Map<String,Object> installed = Json.object(value.get("installed"));
        if (installed.size() > 128) throw new IOException("组件状态过大");
        for (Map.Entry<String,Object> entry : installed.entrySet()) {
            Manifest.id(entry.getKey()); Map<String,Object> record = Json.object(entry.getValue());
            Json.positive(record.get("activeVersion"));
            for (String key : Arrays.asList("previousVersion", "lastGoodVersion"))
                if (record.get(key) != null) Json.positive(record.get(key));
            if (!(record.get("enabled") instanceof Boolean)) throw new IOException("启用状态损坏");
        }
        for (Object id : Json.array(value.get("required"))) Manifest.id(Json.text(id));
        if (!(value.get("initialized") instanceof Boolean)) throw new IOException("初始化状态损坏");
        return value;
    }
    public File directory(String id, int version) throws IOException {
        if (version < 1) throw new IOException("无效版本");
        return PackageVerifier.contained(root, "packages/" + Manifest.id(id) + "/" + version);
    }
    public Manifest active(String id) throws Exception {
        return locked(() -> {
            Object value = Json.object(readUnlocked().get("installed")).get(Manifest.id(id));
            return value == null ? null : verifier.verifyDirectory(directory(id, Json.positive(Json.object(value).get("activeVersion"))));
        });
    }
    public void install(List<PackageVerifier.Candidate> candidates, Set<String> required, Set<String> enabled,
                        int api, String runtime, int sdk, List<String> abis) throws Exception {
        locked(() -> {
            Map<String,Object> state = readUnlocked();
            Map<String,Object> installed = Json.object(state.get("installed"));
            Map<String,Manifest> graph = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : installed.entrySet()) {
                Map<String,Object> record = Json.object(entry.getValue());
                if (!enabled.contains(entry.getKey()) && !required.contains(entry.getKey())) continue;
                graph.put(entry.getKey(), verifier.verifyDirectory(directory(entry.getKey(), Json.positive(record.get("activeVersion")))));
            }
            Set<String> seen = new HashSet<>();
            for (PackageVerifier.Candidate candidate : candidates) {
                Manifest manifest = verifier.verifyDirectory(candidate.directory);
                if (!seen.add(manifest.id)) throw new IOException("重复候选组件");
                Map<String,Object> old = installed.containsKey(manifest.id) ? Json.object(installed.get(manifest.id)) : null;
                if (old != null && Json.positive(old.get("activeVersion")) > manifest.version) throw new IOException("不允许静默降级组件");
                manifest.compatible(api, runtime, sdk, abis);
                graph.put(manifest.id, manifest);
            }
            for (String id : required) if (!graph.containsKey(id)) throw new IOException("基础组件缺失: " + id);
            verifyGraph(graph, api, runtime, sdk, abis);
            for (PackageVerifier.Candidate candidate : candidates) {
                Manifest manifest = candidate.manifest;
                File destination = directory(manifest.id, manifest.version);
                if (destination.exists()) {
                    verifier.verifyDirectory(destination);
                    if (!Arrays.equals(Files.readAllBytes(new File(destination, "manifest.json").toPath()),
                                       Files.readAllBytes(new File(candidate.directory, "manifest.json").toPath())))
                        throw new IOException("同一组件版本不可修改，请增加版本号");
                } else {
                    Files.createDirectories(destination.toPath().getParent());
                    File pending = Files.createTempDirectory(destination.toPath().getParent(), ".pending-").toFile();
                    try {
                        List<String> paths = new ArrayList<>(manifest.files.keySet());
                        paths.add("manifest.json"); paths.add("signature.sig");
                        for (String path : paths) {
                            File source = PackageVerifier.contained(candidate.directory, path), output = PackageVerifier.contained(pending, path);
                            Files.createDirectories(output.toPath().getParent());
                            try (InputStream input = new FileInputStream(source); FileOutputStream stream = new FileOutputStream(output)) {
                                byte[] bytes = new byte[32768]; int count;
                                while ((count = input.read(bytes)) != -1) stream.write(bytes, 0, count);
                                stream.getFD().sync();
                            }
                            if (!output.setReadOnly()) throw new IOException("无法保护已验证载荷");
                        }
                        verifier.verifyDirectory(pending);
                        Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    } finally { if (pending.exists()) PackageVerifier.deleteOwned(destination.getParentFile(), pending); }
                }
                Map<String,Object> old = installed.containsKey(manifest.id) ? Json.object(installed.get(manifest.id)) : null;
                Map<String,Object> record = new LinkedHashMap<>();
                record.put("activeVersion", (long)manifest.version);
                if (old != null) {
                    long previous = Json.positive(old.get("activeVersion"));
                    if (previous != manifest.version) record.put("previousVersion", previous);
                    else if (old.get("previousVersion") != null) record.put("previousVersion", old.get("previousVersion"));
                    record.put("lastGoodVersion", old.getOrDefault("lastGoodVersion", previous));
                }
                record.put("enabled", enabled.contains(manifest.id) || required.contains(manifest.id));
                installed.put(manifest.id, record);
            }
            // Preserve unselected old installations and their data, but never execute incompatible code.
            for (Map.Entry<String,Object> entry : installed.entrySet())
                Json.object(entry.getValue()).put("enabled", enabled.contains(entry.getKey()) || required.contains(entry.getKey()));
            state.put("required", new ArrayList<>(required)); state.put("initialized", true);
            atomic(new File(root, "previous-state.json"), readUnlocked());
            atomic(new File(root, "state.json"), state);
            return null;
        });
    }
    public static void verifyGraph(Map<String,Manifest> graph, int api, String runtime, int sdk, List<String> abis) throws Exception {
        Set<String> complete = new HashSet<>(), visiting = new HashSet<>();
        for (String id : graph.keySet()) visit(id, graph, complete, visiting, api, runtime, sdk, abis);
    }
    public boolean rollback(String id, int api, String runtime, int sdk, List<String> abis) throws Exception {
        return locked(() -> {
            Map<String,Object> state = readUnlocked(), installed = Json.object(state.get("installed"));
            Object item = installed.get(Manifest.id(id));
            if (item == null) return false;
            Map<String,Object> record = Json.object(item);
            if (record.get("previousVersion") == null) return false;
            int previous = Json.positive(record.get("previousVersion"));
            Manifest current = verifier.verifyDirectory(directory(id, Json.positive(record.get("activeVersion"))));
            Manifest restored = verifier.verifyDirectory(directory(id, previous));
            if (restored.dataVersion < current.dataVersion) throw new IOException("数据版本不允许回退，原有数据已保留");
            Map<String,Manifest> graph = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : installed.entrySet()) {
                Map<String,Object> value = Json.object(entry.getValue());
                if (!Boolean.TRUE.equals(value.get("enabled"))) continue;
                graph.put(entry.getKey(), entry.getKey().equals(id) ? restored : verifier.verifyDirectory(directory(entry.getKey(), Json.positive(value.get("activeVersion")))));
            }
            verifyGraph(graph, api, runtime, sdk, abis);
            record.put("activeVersion", (long)previous); record.put("lastGoodVersion", (long)previous); record.remove("previousVersion");
            atomic(new File(root, "state.json"), state);
            return true;
        });
    }
    /** Restore a coupled runtime upgrade as one graph, never a mixture of incompatible SDK versions. */
    public boolean rollbackGraph(int api, String runtime, int sdk, List<String> abis) throws Exception {
        return locked(() -> {
            File previous = new File(root, "previous-state.json");
            if (!previous.isFile()) return false;
            Map<String,Object> restored = Json.object(Json.parse(Files.readAllBytes(previous.toPath())));
            Map<String,Object> oldInstalled = Json.object(restored.get("installed"));
            if (!Boolean.TRUE.equals(restored.get("initialized"))) return false;
            Map<String,Object> current = Json.object(readUnlocked().get("installed"));
            Map<String,Manifest> graph = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : oldInstalled.entrySet()) {
                Map<String,Object> record = Json.object(entry.getValue());
                if (!Boolean.TRUE.equals(record.get("enabled"))) continue;
                Manifest target = verifier.verifyDirectory(directory(entry.getKey(), Json.positive(record.get("activeVersion"))));
                if (current.containsKey(entry.getKey())) {
                    Manifest active = verifier.verifyDirectory(directory(entry.getKey(), Json.positive(Json.object(current.get(entry.getKey())).get("activeVersion"))));
                    if (target.dataVersion < active.dataVersion) throw new IOException("数据版本不允许整组回退，原有数据已保留");
                }
                graph.put(entry.getKey(), target);
            }
            for (Object value : Json.array(restored.get("required")))
                if (!graph.containsKey(Manifest.id(Json.text(value)))) throw new IOException("回滚组缺少必需组件");
            verifyGraph(graph, api, runtime, sdk, abis);
            // Keep newer directories and business data; only the verified selection pointer changes.
            atomic(new File(root, "state.json"), restored);
            return true;
        });
    }
    private static void visit(String id, Map<String,Manifest> graph, Set<String> complete, Set<String> visiting,
                              int api, String runtime, int sdk, List<String> abis) throws Exception {
        if (complete.contains(id)) return;
        if (!visiting.add(id)) throw new IOException("组件循环依赖: " + id);
        Manifest manifest = graph.get(id);
        if (manifest == null) throw new IOException("依赖组件缺失: " + id);
        manifest.compatible(api, runtime, sdk, abis);
        for (Map.Entry<String,Manifest.Dependency> dependency : manifest.dependencies.entrySet()) {
            Manifest installed = graph.get(dependency.getKey());
            if (installed == null || !dependency.getValue().accepts(installed.version)) throw new IOException("依赖版本不兼容: " + dependency.getKey());
            visit(dependency.getKey(), graph, complete, visiting, api, runtime, sdk, abis);
        }
        visiting.remove(id); complete.add(id);
    }
    public static void atomic(File file, Object value) throws Exception {
        File pending = new File(file.getParentFile(), file.getName() + ".pending");
        try (FileOutputStream stream = new FileOutputStream(pending)) {
            stream.write(Json.stringify(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)); stream.getFD().sync();
        }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}

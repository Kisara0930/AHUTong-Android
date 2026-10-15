package com.ahu.ahutong.bootstrap;

import android.content.pm.ApplicationInfo;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.nio.file.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.zip.ZipFile;

/** Foundation class ownership is process-wide. Business code is never added to the application loader. */
public final class RuntimeGraph {
    public static final String[] FOUNDATIONS = {"shared_runtime", "ui_runtime", "camera_runtime", "content_runtime"};
    public final ApplicationInfo applicationInfo;
    public final PackageVerifier verifier;
    public final InstallStore store;
    public final ClassLoader loader;
    public final Map<String,Object> configuration;
    private final ClassLoader nativeLoader;
    private final Map<String,Node> nodes = new LinkedHashMap<>();
    private final Set<String> constructing = new HashSet<>();
    private boolean usable;
    public RuntimeGraph(ClassLoader nativeLoader, ApplicationInfo info) throws Exception {
        this.nativeLoader = nativeLoader; this.applicationInfo = new ApplicationInfo(info);
        byte[] pin, config;
        try (ZipFile apk = new ZipFile(info.sourceDir)) {
            pin = read(apk.getInputStream(apk.getEntry("assets/ahup_trusted_certificate.der")), 16384);
            config = read(apk.getInputStream(apk.getEntry("assets/initialization.json")), 65536);
        }
        verifier = new PackageVerifier(CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pin)).getPublicKey());
        configuration = Json.object(Json.parse(config));
        store = new InstallStore(new File(info.dataDir, "files/ahup-v3"), verifier);
        loader = new ClassLoader(nativeLoader) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (nativeBoundary(name)) return getParent().loadClass(name);
                boolean ready = isUsable() && NativeBootstrap.usable();
                if (name.equals("com.ahu.ahutong.PluginActivity") && !ready) return BootstrapActivity.class;
                if (!ready) return getParent().loadClass(name);
                for (String id : FOUNDATIONS) {
                    try { Node node = optional(id); if (node != null) return node.loader.loadClass(name); }
                    catch (ClassNotFoundException ignored) { }
                    catch (Exception error) { throw new ClassNotFoundException(id + " 运行时加载失败", error); }
                }
                return getParent().loadClass(name);
            }
        };
        refreshReadiness();
    }
    public synchronized boolean isUsable() { return usable; }
    public synchronized void refreshReadiness() {
        usable = false;
        try {
            Map<String,Object> consent = preferences();
            if (!Boolean.TRUE.equals(consent.get("privacyAccepted"))) return;
            Map<String,Object> state = store.read();
            if (!Boolean.TRUE.equals(state.get("initialized"))) return;
            Map<String,Object> selected = Json.object(state.get("installed"));
            Map<String,Manifest> graph = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : selected.entrySet()) {
                if (Boolean.TRUE.equals(Json.object(entry.getValue()).get("enabled"))) graph.put(entry.getKey(), store.active(entry.getKey()));
            }
            for (Object required : Json.array(configuration.get("required")))
                if (!graph.containsKey(Json.text(required))) return;
            InstallStore.verifyGraph(graph, api(), runtime(), android.os.Build.VERSION.SDK_INT,
                Collections.singletonList(NativeAbi.current()));
            usable = true;
        } catch (Exception error) { recordError(error); }
    }
    public int api() { return Json.positive(configuration.get("hostApiVersion")); }
    public String runtime() { return Json.text(configuration.get("runtimeVersion")); }
    public Map<String,Object> preferences() throws Exception {
        File path = new File(applicationInfo.dataDir, "files/native-bootstrap.json");
        return path.isFile() ? Json.object(Json.parse(Files.readAllBytes(path.toPath()))) : new LinkedHashMap<>();
    }
    public void savePreferences(Map<String,Object> preferences) throws Exception {
        File path = new File(applicationInfo.dataDir, "files/native-bootstrap.json");
        Files.createDirectories(path.toPath().getParent());
        store.locked(() -> { InstallStore.atomic(path, preferences); return null; });
    }
    public void recordError(Throwable error) {
        android.util.Log.e("NativeRuntime", "原生初始化/加载失败", error);
        try {
            File path = new File(applicationInfo.dataDir, "files/native-last-error");
            Files.createDirectories(path.toPath().getParent());
            Files.write(path.toPath(), (error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
    public synchronized Node optional(String id) throws Exception {
        Node cached = nodes.get(id);
        if (cached != null) return cached;
        Object selected = Json.object(store.read().get("installed")).get(Manifest.id(id));
        if (selected == null || !Boolean.TRUE.equals(Json.object(selected).get("enabled"))) return null;
        Manifest manifest = store.active(Manifest.id(id));
        if (manifest == null) return null;
        manifest.compatible(api(), runtime(), android.os.Build.VERSION.SDK_INT, Collections.singletonList(NativeAbi.current()));
        if (!constructing.add(id)) throw new IOException("循环加载依赖: " + id);
        try {
            List<Node> dependencies = new ArrayList<>();
            for (Map.Entry<String,Manifest.Dependency> dependency : manifest.dependencies.entrySet()) {
                Node node = optional(dependency.getKey());
                if (node == null || !dependency.getValue().accepts(node.manifest.version)) throw new IOException("缺少兼容依赖: " + dependency.getKey());
                dependencies.add(node);
            }
            // Keep the common SDK -> APK and UI -> SDK chains recognizable to ART's DEX optimizer.
            // Only a component with multiple declared owners needs a composite dependency parent.
            ClassLoader parent = dependencies.isEmpty() ? nativeLoader : dependencies.size() == 1 ? dependencies.get(0).loader : new ClassLoader(nativeLoader) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (nativeBoundary(name)) return getParent().loadClass(name);
                    for (Node node : dependencies) {
                        try { return node.loader.loadClass(name); } catch (ClassNotFoundException ignored) { }
                    }
                    return getParent().loadClass(name);
                }
            };
            File directory = store.directory(id, manifest.version);
            List<String> dexes = new ArrayList<>();
            for (Manifest.Payload payload : manifest.files.values()) {
                boolean resourceArchive = payload.path.equals("resources.apk");
                if (!resourceArchive && !payload.path.matches("(?:dex/)?classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                File original = PackageVerifier.contained(directory, payload.path);
                File cache = new File(applicationInfo.dataDir, "code_cache/native-ahup/" + id + "/" + manifest.version + "/" + payload.sha256 + (resourceArchive ? ".apk" : ".dex"));
                Files.createDirectories(cache.toPath().getParent());
                if (!cache.isFile() || !PackageVerifier.sha256(cache).equals(payload.sha256)) {
                    File pending = File.createTempFile(".dex-", ".pending", cache.getParentFile());
                    try (InputStream input = new FileInputStream(original); FileOutputStream output = new FileOutputStream(pending)) {
                        byte[] bytes = new byte[32768]; int count;
                        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                        output.getFD().sync();
                    }
                    if (!pending.setReadOnly()) throw new IOException("无法保护代码缓存");
                    Files.move(pending.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!cache.setReadOnly()) throw new IOException("代码缓存必须只读");
                dexes.add(cache.getAbsolutePath());
            }
            String nativePath = null;
            for (String abi : Collections.singletonList(NativeAbi.current())) {
                File path = new File(directory, "lib/" + abi);
                if (path.isDirectory()) { nativePath = path.getAbsolutePath(); break; }
            }
            DexClassLoader componentLoader = id.equals("shared_runtime")
                ? new OwnedRuntimeLoader(String.join(File.pathSeparator, dexes), nativePath, parent)
                : new DexClassLoader(String.join(File.pathSeparator, dexes), null, nativePath, parent);
            Node node = new Node(manifest, directory, componentLoader);
            nodes.put(id, node); return node;
        } finally { constructing.remove(id); }
    }
    public synchronized List<File> resourceApks() throws Exception {
        List<File> paths = new ArrayList<>();
        for (String id : FOUNDATIONS) {
            Node node = optional(id);
            if (node != null) {
                File path = new File(node.directory, "resources.apk");
                if (path.isFile()) paths.add(path);
            }
        }
        return paths;
    }
    public static byte[] read(InputStream input, int limit) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("输入超过限制");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    public static final class Node {
        public final Manifest manifest;
        public final File directory;
        public final ClassLoader loader;
        Node(Manifest manifest, File directory, ClassLoader loader) { this.manifest = manifest; this.directory = directory; this.loader = loader; }
    }
    private static boolean nativeBoundary(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") ||
            name.startsWith("dalvik.") || name.startsWith("org.json.") || name.startsWith("org.xml.") ||
            name.startsWith("org.w3c.") || name.startsWith("com.ahu.ahutong.hostapi.") || name.startsWith("com.ahu.ahutong.bootstrap.");
    }
    private static final class OwnedRuntimeLoader extends DexClassLoader {
        private final java.util.concurrent.ConcurrentHashMap<String,Object> classLocks = new java.util.concurrent.ConcurrentHashMap<>();
        OwnedRuntimeLoader(String paths, String natives, ClassLoader parent) { super(paths, null, natives, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (classLocks.computeIfAbsent(name, ignored -> new Object())) {
                if (nativeBoundary(name)) return getParent().loadClass(name);
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try { type = findClass(name); } catch (ClassNotFoundException missing) { type = getParent().loadClass(name); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}

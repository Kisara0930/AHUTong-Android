package com.ahu.ahutong.bootstrap;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class KernelVerificationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private KeyPair keys;
    private PackageVerifier verifier;
    @Before public void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        keys = generator.generateKeyPair(); verifier = new PackageVerifier(keys.getPublic());
    }
    @After public void releaseWindowsTestPermissions() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(temporary.getRoot().toPath())) {
            paths.forEach(path -> path.toFile().setWritable(true));
        }
    }
    private Map<String,Object> metadata(String id, int version, List<Object> dependencies) throws Exception {
        File payload = temporary.newFile(); Files.write(payload.toPath(), new byte[]{'d','e','x','\n'});
        Map<String,Object> file = new LinkedHashMap<>();
        file.put("path", "classes.dex"); file.put("size", 4L); file.put("sha256", PackageVerifier.sha256(payload));
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", 3L); manifest.put("id", id); manifest.put("title", "组件");
        manifest.put("versionCode", (long)version); manifest.put("versionName", "1.0." + version);
        manifest.put("entryClass", "example.NativeEntry"); manifest.put("hostApiVersion", 2L);
        manifest.put("runtimeVersion", "2"); manifest.put("minSdk", 26L);
        manifest.put("abis", Collections.emptyList()); manifest.put("dependencies", dependencies);
        manifest.put("files", Collections.singletonList(file)); manifest.put("release", true);
        return manifest;
    }
    private File archive(Map<String,Object> manifest, boolean tamper, String extraName) throws Exception {
        byte[] raw = Json.stringify(manifest).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(keys.getPrivate()); signer.update(raw);
        File file = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            add(zip, "manifest.json", raw); add(zip, "signature.sig", signer.sign());
            add(zip, "classes.dex", tamper ? new byte[]{'b','a','d','!'} : new byte[]{'d','e','x','\n'});
            if (extraName != null) add(zip, extraName, new byte[]{1});
        }
        return file;
    }
    private void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private File inspections() throws Exception { return temporary.newFolder(); }
    @Test public void verifiesRealRsaSignatureAndPayload() throws Exception {
        try (PackageVerifier.Candidate candidate = verifier.inspect(archive(metadata("account", 3, Collections.emptyList()), false, null), inspections())) {
            assertEquals("account", candidate.manifest.id);
            candidate.manifest.compatible(2, "2", 36, Collections.singletonList("arm64-v8a"));
            assertThrows(IllegalArgumentException.class, () -> candidate.manifest.compatible(1, "1", 36, Collections.singletonList("arm64-v8a")));
        }
    }
    @Test public void rejectsPayloadTamperBeforeAnyCodeCanLoad() throws Exception {
        File file = archive(metadata("account", 3, Collections.emptyList()), true, null);
        assertThrows(GeneralSecurityException.class, () -> verifier.inspect(file, inspections()));
    }
    @Test public void rejectsUntrustedKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        PackageVerifier other = new PackageVerifier(generator.generateKeyPair().getPublic());
        File file = archive(metadata("account", 3, Collections.emptyList()), false, null);
        assertThrows(GeneralSecurityException.class, () -> other.inspect(file, inspections()));
    }
    @Test public void rejectsExtraFilesAndTraversal() throws Exception {
        File extra = archive(metadata("account", 3, Collections.emptyList()), false, "extra.bin");
        assertThrows(GeneralSecurityException.class, () -> verifier.inspect(extra, inspections()));
        File traversal = archive(metadata("account", 3, Collections.emptyList()), false, "../escaped");
        assertThrows(IllegalArgumentException.class, () -> verifier.inspect(traversal, inspections()));
    }
    @Test public void rejectsZipSymlinkAttributes() throws Exception {
        File file = archive(metadata("account", 3, Collections.emptyList()), false, null);
        byte[] bytes = Files.readAllBytes(file.toPath());
        for (int i = 0; i < bytes.length - 46; i++) {
            if (bytes[i] == 0x50 && bytes[i+1] == 0x4b && bytes[i+2] == 1 && bytes[i+3] == 2) {
                bytes[i+5] = 3; bytes[i+40] = 0; bytes[i+41] = (byte)0xa0; break;
            }
        }
        Files.write(file.toPath(), bytes);
        assertThrows(IOException.class, () -> verifier.inspect(file, inspections()));
    }
    @Test public void rejectsDuplicateKeysNonIntegerAndMalformedUtf8() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1e309}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> Json.parse(new byte[]{(byte)0xc0,(byte)0x80}));
        Map<String,Object> decoded = Json.object(Json.parse("{\"a\":\"中文\\n\\\"\",\"b\":[true,null,3]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(decoded, Json.parse(Json.stringify(decoded).getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void commitsDependenciesAtomicallyAndKeepsRollbackPointer() throws Exception {
        InstallStore store = new InstallStore(temporary.newFolder(), verifier);
        Map<String,Object> dependency = new LinkedHashMap<>(); dependency.put("id", "shared_runtime"); dependency.put("minVersion", 1L);
        try (PackageVerifier.Candidate base = verifier.inspect(archive(metadata("shared_runtime", 1, Collections.emptyList()), false, null), inspections());
             PackageVerifier.Candidate account = verifier.inspect(archive(metadata("account", 3, Collections.singletonList(dependency)), false, null), inspections())) {
            assertThrows(IOException.class, () -> store.install(Collections.singletonList(account), Collections.singleton("account"),
                Collections.singleton("account"), 2, "2", 36, Collections.singletonList("arm64-v8a")));
            assertTrue(Json.object(store.read().get("installed")).isEmpty());
            Set<String> selected = new LinkedHashSet<>(Arrays.asList("account", "shared_runtime"));
            store.install(Arrays.asList(base, account), selected, selected, 2, "2", 36, Collections.singletonList("arm64-v8a"));
            assertEquals(2, Json.object(store.read().get("installed")).size());
            assertEquals(3, store.active("account").version);
        }
        try (PackageVerifier.Candidate account = verifier.inspect(archive(metadata("account", 4, Collections.singletonList(dependency)), false, null), inspections())) {
            Set<String> selected = new LinkedHashSet<>(Arrays.asList("account", "shared_runtime"));
            store.install(Collections.singletonList(account), selected, selected, 2, "2", 36, Collections.singletonList("arm64-v8a"));
            Map<String,Object> record = Json.object(Json.object(store.read().get("installed")).get("account"));
            assertEquals(3L, record.get("previousVersion")); assertEquals(4, store.active("account").version);
            assertTrue(store.rollback("account", 2, "2", 36, Collections.singletonList("arm64-v8a")));
            assertEquals(3, store.active("account").version);
        }
    }
    @Test public void refusesRollbackAcrossAnIncompatibleDataVersion() throws Exception {
        InstallStore store = new InstallStore(temporary.newFolder(), verifier);
        Set<String> ids = Collections.singleton("account");
        try (PackageVerifier.Candidate first = verifier.inspect(archive(metadata("account", 3, Collections.emptyList()), false, null), inspections())) {
            store.install(Collections.singletonList(first), ids, ids, 2, "2", 36, Collections.singletonList("arm64-v8a"));
        }
        Map<String,Object> changed = metadata("account", 4, Collections.emptyList()); changed.put("dataVersion", 2L);
        try (PackageVerifier.Candidate second = verifier.inspect(archive(changed, false, null), inspections())) {
            store.install(Collections.singletonList(second), ids, ids, 2, "2", 36, Collections.singletonList("arm64-v8a"));
        }
        assertThrows(IOException.class, () -> store.rollback("account", 2, "2", 36, Collections.singletonList("arm64-v8a")));
        assertThrows(IOException.class, () -> store.rollbackGraph(2, "2", 36, Collections.singletonList("arm64-v8a")));
        assertEquals(4, store.active("account").version);
    }
    @Test public void restoresCoupledSdkAndConsumerVersionsAtomically() throws Exception {
        InstallStore store = new InstallStore(temporary.newFolder(), verifier);
        Set<String> ids = new LinkedHashSet<>(Arrays.asList("account", "shared_runtime"));
        for (int version = 1; version <= 2; version++) {
            Map<String,Object> dependency = new LinkedHashMap<>();
            dependency.put("id", "shared_runtime"); dependency.put("minVersion", (long)version); dependency.put("maxVersion", (long)version);
            try (PackageVerifier.Candidate sdk = verifier.inspect(archive(metadata("shared_runtime", version, Collections.emptyList()), false, null), inspections());
                 PackageVerifier.Candidate account = verifier.inspect(archive(metadata("account", version + 2, Collections.singletonList(dependency)), false, null), inspections())) {
                store.install(Arrays.asList(sdk, account), ids, ids, 2, "2", 36, Collections.singletonList("arm64-v8a"));
            }
        }
        assertThrows(IOException.class, () -> store.rollback("shared_runtime", 2, "2", 36, Collections.singletonList("arm64-v8a")));
        assertEquals(2, store.active("shared_runtime").version);
        assertTrue(store.rollbackGraph(2, "2", 36, Collections.singletonList("arm64-v8a")));
        assertEquals(1, store.active("shared_runtime").version);
        assertEquals(3, store.active("account").version);
        assertTrue(store.directory("shared_runtime", 2).isDirectory());
    }
    @Test public void sameVersionMutationCannotReplaceInstalledCode() throws Exception {
        InstallStore store = new InstallStore(temporary.newFolder(), verifier);
        Map<String,Object> original = metadata("account", 3, Collections.emptyList());
        try (PackageVerifier.Candidate package1 = verifier.inspect(archive(original, false, null), inspections())) {
            store.install(Collections.singletonList(package1), Collections.singleton("account"), Collections.singleton("account"), 2, "2", 36, Collections.singletonList("arm64-v8a"));
        }
        original.put("title", "变更的相同版本");
        try (PackageVerifier.Candidate package2 = verifier.inspect(archive(original, false, null), inspections())) {
            assertThrows(IOException.class, () -> store.install(Collections.singletonList(package2), Collections.singleton("account"), Collections.singleton("account"), 2, "2", 36, Collections.singletonList("arm64-v8a")));
            assertEquals("组件", store.active("account").title);
        }
    }
}

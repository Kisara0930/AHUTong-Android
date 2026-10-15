package com.ahu.ahutong.bootstrap;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Verification happens in an owned temporary directory; no submitted code is executed. */
public final class PackageVerifier {
    public static final long MAX_TOTAL = 256L * 1024 * 1024, MAX_FILE = 128L * 1024 * 1024;
    private final PublicKey key;
    public PackageVerifier(PublicKey key) { this.key = key; }
    public Candidate inspect(File archive, File inspectionRoot) throws Exception {
        if (archive.length() > MAX_TOTAL) throw new IOException("组件包过大");
        checkCentralDirectory(archive);
        Files.createDirectories(inspectionRoot.toPath());
        File temporary = Files.createTempDirectory(inspectionRoot.toPath(), "package-").toFile();
        try {
            Set<String> seen = new HashSet<>();
            long total = 0;
            try (ZipFile zip = new ZipFile(archive)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = Manifest.path(entry.getName());
                    if (entry.isDirectory() || !seen.add(name) || seen.size() > 4096 || entry.getSize() < 0 ||
                            entry.getSize() > (name.equals("manifest.json") ? 1024 * 1024 : MAX_FILE))
                        throw new IOException("无效、重复或过大的 ZIP 条目");
                    File file = contained(temporary, name);
                    Files.createDirectories(file.toPath().getParent());
                    long size = 0;
                    try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(file)) {
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            size += count; total += count;
                            if (size > entry.getSize() || total > MAX_TOTAL) throw new IOException("ZIP 解压超过限制");
                            output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                    if (size != entry.getSize()) throw new IOException("ZIP 条目不完整");
                }
            }
            Manifest manifest = verifyDirectory(temporary);
            return new Candidate(manifest, temporary, inspectionRoot);
        } catch (Exception error) { deleteOwned(inspectionRoot, temporary); throw error; }
    }
    public Manifest verifyDirectory(File directory) throws Exception {
        File manifestFile = contained(directory, "manifest.json"), signatureFile = contained(directory, "signature.sig");
        if (manifestFile.length() > 1024 * 1024 || signatureFile.length() < 1 || signatureFile.length() > 16384)
            throw new GeneralSecurityException("缺少或过大的签名清单");
        byte[] raw = Files.readAllBytes(manifestFile.toPath());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key); signature.update(raw);
        if (!signature.verify(Files.readAllBytes(signatureFile.toPath()))) throw new GeneralSecurityException("组件签名不受信任");
        Manifest manifest = new Manifest(raw);
        Set<String> expected = new HashSet<>(manifest.files.keySet());
        expected.add("manifest.json"); expected.add("signature.sig");
        Set<String> actual = new HashSet<>();
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(path)) throw new IOException("不允许符号链接");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) throw new IOException("不允许特殊文件");
                actual.add(directory.toPath().relativize(path).toString().replace(File.separatorChar, '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        if (!actual.equals(expected)) throw new GeneralSecurityException("已安装目录与签名清单不一致");
        for (Manifest.Payload payload : manifest.files.values()) {
            File file = contained(directory, payload.path);
            if (file.length() != payload.size || !sha256(file).equals(payload.sha256))
                throw new GeneralSecurityException("组件载荷校验失败: " + payload.path);
        }
        return manifest;
    }
    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    public static File contained(File root, String relative) throws IOException {
        File file = new File(root, Manifest.path(relative));
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("载荷越界");
        return file;
    }
    public static void deleteOwned(File owner, File target) throws IOException {
        if (!target.toPath().toAbsolutePath().normalize().startsWith(owner.toPath().toAbsolutePath().normalize()) || owner.equals(target))
            throw new IOException("拒绝清理工作区之外的路径");
        if (!target.exists()) return;
        Files.walkFileTree(target.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error; Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
    private static long u32(RandomAccessFile file) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt())); }
    private static int u16(RandomAccessFile file) throws IOException { return Short.toUnsignedInt(Short.reverseBytes(file.readShort())); }
    private static void checkCentralDirectory(File archive) throws IOException {
        // Java ZipEntry does not expose Unix attributes. Reject symlinks/encryption/ZIP64 before extraction.
        try (RandomAccessFile file = new RandomAccessFile(archive, "r")) {
            long end = -1;
            for (long position = file.length() - 22; position >= Math.max(0, file.length() - 65557); position--) {
                file.seek(position);
                if (u32(file) == 0x06054b50L) { end = position; break; }
            }
            if (end < 0) throw new IOException("ZIP 结束目录缺失");
            file.seek(end + 4);
            int disk = u16(file), centralDisk = u16(file), onDisk = u16(file), entries = u16(file);
            long size = u32(file), offset = u32(file); int comment = u16(file);
            if (disk != 0 || centralDisk != 0 || entries != onDisk || entries > 4096 || entries == 0 ||
                    offset + size != end || end + 22 + comment != file.length()) throw new IOException("不支持此 ZIP 目录");
            file.seek(offset);
            for (int index = 0; index < entries; index++) {
                if (u32(file) != 0x02014b50L) throw new IOException("ZIP 目录损坏");
                int creator = u16(file); u16(file); int flags = u16(file); u16(file);
                file.skipBytes(16);
                int name = u16(file), extra = u16(file), note = u16(file); u16(file); u16(file);
                long attributes = u32(file); u32(file);
                int type = (int)((attributes >>> 16) & 0170000);
                if ((flags & 1) != 0 || ((creator >>> 8) == 3 && type != 0 && type != 0100000))
                    throw new IOException("不允许加密或特殊 ZIP 文件");
                file.seek(file.getFilePointer() + name + extra + note);
            }
            if (file.getFilePointer() != end) throw new IOException("ZIP 目录大小不一致");
        }
    }
    public static final class Candidate implements AutoCloseable {
        public final Manifest manifest;
        public final File directory;
        private final File owner;
        Candidate(Manifest manifest, File directory, File owner) { this.manifest = manifest; this.directory = directory; this.owner = owner; }
        @Override public void close() throws IOException { deleteOwned(owner, directory); }
    }
}

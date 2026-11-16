package com.rag.knowledge.app.ingest;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 上传文件的本地落盘。目录结构 {@code storage/kb{库号}/{sha256}.{扩展名}}：
 * 内容寻址让同一份文件重复上传只落一次盘，也让「文档 → 原始文件」这一跳在重新入库时可复现。
 */
@Component
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    public record Stored(String relativePath, String sha256, long size) {
    }

    private final Path root;

    public FileStorage(RagProperties props) {
        this.root = Paths.get(props.storage().dir()).toAbsolutePath().normalize();
    }

    public String root() {
        return root.toString();
    }

    public Stored store(long kbId, String fileName, byte[] content) {
        String sha = sha256(content);
        String relative = "kb" + kbId + "/" + sha + extensionOf(fileName);
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.write(target, content);
            }
        } catch (IOException e) {
            log.error("写入失败：{}", target, e);
            throw RagException.conflict("文件写入失败：" + target,
                    "检查 rag.storage.dir 指向的目录是否存在且可写（当前 " + root + "）");
        }
        log.info("落盘 {} -> {}（{} 字节）", fileName, relative, content.length);
        return new Stored(relative, sha, content.length);
    }

    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw RagException.notFound("原始文件读不到：" + relativePath
                    + " ｜ 处理建议: 确认 storage 目录未被移动，或删除该文档后重新上传");
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            log.warn("删除文件失败，忽略：{}", relativePath, e);
        }
    }

    /** 路径穿越防线：库里存的相对路径必须仍然落在 storage 根目录下。 */
    private Path resolve(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw RagException.badRequest("非法文件路径：" + relativePath);
        }
        return target;
    }

    public static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256，这不该发生在标准 JDK 上", e);
        }
    }

    /** 只保留扩展名字符，避免上传文件名里的路径符号污染落盘路径。 */
    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String safe = ext.replaceAll("[^a-z0-9]", "");
        return safe.isEmpty() ? "" : "." + safe;
    }
}

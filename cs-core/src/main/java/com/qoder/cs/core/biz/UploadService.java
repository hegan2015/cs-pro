package com.qoder.cs.core.biz;

import com.qoder.cs.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 图片附件存储：服务端生成 UUID 文件名 + 扩展名白名单，落 data/uploads 经 /uploads/** 静态访问。
 * 进 LLM 时转 base64 data URL（本地路径对模型供应商不可达）。
 */
@Slf4j
@Service
public class UploadService {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final int MAX_PER_MESSAGE = 3;
    private static final Map<String, String> MIME = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "webp", "image/webp", "gif", "image/gif");
    /** 仅允许本服务生成的 /uploads/{uuid}.{ext} 形态，防任意 URL 注入 prompt */
    private static final Pattern URL_PATTERN =
            Pattern.compile("/uploads/[0-9a-f-]{36}\\.(png|jpg|jpeg|webp|gif)");

    private final Path dir;

    public UploadService(@Value("${cs.uploads.dir:./data/uploads}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    public String save(String originalName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw BizException.param("文件为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw BizException.param("图片过大（上限 5MB）");
        }
        String ext = extOf(originalName);
        if (!MIME.containsKey(ext)) {
            throw BizException.param("仅支持 png/jpg/jpeg/webp/gif 图片");
        }
        String name = UUID.randomUUID() + "." + ext;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), bytes);
        } catch (Exception e) {
            throw BizException.biz("图片保存失败: " + e.getMessage());
        }
        log.info("图片上传 name={} bytes={}", name, bytes.length);
        return "/uploads/" + name;
    }

    /** 逗号列表逐项校验（URL 形态 + 数量上限）；null/空返回 null */
    public static String validateList(String attachments) {
        if (attachments == null || attachments.isBlank()) {
            return null;
        }
        String[] urls = attachments.split(",");
        if (urls.length > MAX_PER_MESSAGE) {
            throw BizException.param("图片最多 " + MAX_PER_MESSAGE + " 张");
        }
        for (String u : urls) {
            if (!URL_PATTERN.matcher(u.trim()).matches()) {
                throw BizException.param("非法附件地址: " + u);
            }
        }
        return String.join(",", java.util.Arrays.stream(urls).map(String::trim).toList());
    }

    /** 读取本地文件转 data URL；文件缺失返回 null（历史附件被清理时对话不中断） */
    public String dataUrl(String url) {
        if (url == null || !URL_PATTERN.matcher(url).matches()) {
            return null;
        }
        Path file = dir.resolve(url.substring("/uploads/".length()));
        try {
            byte[] bytes = Files.readAllBytes(file);
            String ext = url.substring(url.lastIndexOf('.') + 1);
            return "data:" + MIME.get(ext) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("附件读取失败 url={} err={}", url, e.getMessage());
            return null;
        }
    }

    private static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
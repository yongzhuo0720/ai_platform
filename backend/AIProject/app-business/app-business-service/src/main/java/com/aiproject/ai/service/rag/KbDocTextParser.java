package com.aiproject.ai.service.rag;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 文档上传的文本解析与校验。
 *
 * <p>服务端只解析文本类格式（txt/md/csv/json/log）：PDF/DOCX 的解析器仍在浏览器里
 * （pdfjs / mammoth 已经是现有依赖），后端不重复引入一套四百 KB 级的解析库。
 * 浏览器解析后的正文通过同一个上传接口提交，后续分块、向量化、重试完全一致。</p>
 *
 * <p>所有限制在服务端重新校验：前端提示只是体验，不能作为安全边界。</p>
 */
public final class KbDocTextParser {

    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 200000;

    private static final Set<String> SERVER_TYPES = new HashSet<String>(
            Arrays.asList("txt", "md", "markdown", "csv", "json", "log"));
    private static final Set<String> CLIENT_TYPES = new HashSet<String>(
            Arrays.asList("txt", "md", "markdown", "csv", "json", "log", "pdf", "docx"));

    private KbDocTextParser() {
    }

    public static String fileType(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }

    public static boolean isServerParseable(String type) {
        return SERVER_TYPES.contains(normalizeType(type));
    }

    public static boolean isAcceptedClientType(String type) {
        return CLIENT_TYPES.contains(normalizeType(type));
    }

    /**
     * 解析服务端可读的文本文件。
     *
     * @throws IllegalArgumentException 文件过大、格式不支持、编码无法识别或内容像二进制
     */
    public static ParsedText parseFile(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件过大，单篇最多 20MB");
        }
        String type = fileType(fileName);
        if (!isServerParseable(type)) {
            throw new IllegalArgumentException(
                    "服务端暂不支持解析 ." + (type.isEmpty() ? "未知" : type)
                            + " 文件；PDF/DOCX 请由浏览器提取文本后提交，或上传 txt/md");
        }
        String text = normalizeAndValidate(decode(bytes));
        return new ParsedText(text, normalizeType(type), bytes.length);
    }

    /**
     * 接收浏览器已提取的正文（PDF/DOCX 走这里）。
     */
    public static ParsedText parseClientText(String fileType, String content,
                                             Long sizeBytes, String fileName) {
        String type = normalizeType(fileType);
        if (type.isEmpty()) {
            type = fileType(fileName);
        }
        if (type.isEmpty()) {
            type = "txt";
        }
        if (!isAcceptedClientType(type)) {
            throw new IllegalArgumentException("不支持的文档类型：" + type);
        }
        String text = normalizeAndValidate(content);
        long size = sizeBytes == null || sizeBytes <= 0
                ? text.getBytes(StandardCharsets.UTF_8).length : sizeBytes;
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件过大，单篇最多 20MB");
        }
        return new ParsedText(text, type, size);
    }

    /**
     * 统一换行、去 BOM、去首尾空白，并校验长度与二进制特征。
     * 超限直接拒绝而不是静默截断——截断后的文档会让评估结果悄悄失真。
     */
    public static String normalizeAndValidate(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        String text = raw;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("文档解析后没有有效内容");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("解析后共 " + text.length()
                    + " 字符，超过单篇 " + MAX_TEXT_CHARS + " 字符上限，请拆分后再上传");
        }
        int control = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0000') {
                throw new IllegalArgumentException("文件包含二进制内容，无法作为文本处理");
            }
            if (c < 0x20 && c != '\n' && c != '\t') {
                control++;
            }
        }
        if (control > 0 && (double) control / text.length() > 0.01) {
            throw new IllegalArgumentException("文件包含大量不可见控制字符，可能不是文本文件");
        }
        return text;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            // JDK 必带 SHA-256；走到这里说明运行环境异常，不能把文档当「无哈希」放过去
            throw new IllegalStateException("计算文档指纹失败", e);
        }
    }

    private static String decode(byte[] bytes) {
        if (startsWith(bytes, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF})) {
            return decodeStrict(bytes, 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xFE})) {
            return decodeStrict(bytes, 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFE, (byte) 0xFF})) {
            return decodeStrict(bytes, 2, StandardCharsets.UTF_16BE);
        }
        // 企业内网仍有 GBK 文本：先按 UTF-8 严格解码，失败再试 GBK。
        // 顺序不能反——GBK 几乎不会报错，先试它会把 UTF-8 中文解成乱码。
        try {
            return decodeStrict(bytes, 0, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException utf8Error) {
            try {
                return decodeStrict(bytes, 0, Charset.forName("GBK"));
            } catch (IllegalArgumentException gbkError) {
                throw new IllegalArgumentException("无法识别文件编码，请另存为 UTF-8 后重试");
            }
        }
    }

    private static String decodeStrict(byte[] bytes, int offset, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("文本编码校验失败", e);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    /** 解析结果：规范化正文 + 文件类型 + 原始字节数 */
    public static class ParsedText {
        private final String content;
        private final String fileType;
        private final long sizeBytes;

        ParsedText(String content, String fileType, long sizeBytes) {
            this.content = content;
            this.fileType = fileType;
            this.sizeBytes = sizeBytes;
        }

        public String getContent() {
            return content;
        }

        public String getFileType() {
            return fileType;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }
    }
}

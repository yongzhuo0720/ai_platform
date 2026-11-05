package com.aiproject.ai.service.rag;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档解析与校验的单元测试。
 *
 * <p>这些边界如果只靠前端校验，绕过页面直接调用接口就能把二进制文件、
 * 超大文本塞进向量库；因此每个上限都在这里验证服务端会拒绝。</p>
 */
class KbDocTextParserTest {

    @Test
    void parseFile_shouldNormalizeBomAndCrlf() {
        byte[] bytes = ("\uFEFF第一行\r\n第二行\r第三行").getBytes(StandardCharsets.UTF_8);
        KbDocTextParser.ParsedText parsed = KbDocTextParser.parseFile("制度.md", bytes);

        assertEquals("第一行\n第二行\n第三行", parsed.getContent());
        assertEquals("md", parsed.getFileType());
        assertEquals(bytes.length, parsed.getSizeBytes());
    }

    @Test
    void parseFile_shouldFallbackToGbkWhenUtf8Fails() {
        Charset gbk = Charset.forName("GBK");
        byte[] bytes = "差旅报销制度".getBytes(gbk);
        KbDocTextParser.ParsedText parsed = KbDocTextParser.parseFile("制度.txt", bytes);

        assertEquals("差旅报销制度", parsed.getContent());
    }

    @Test
    void parseFile_shouldRejectBinaryContent() {
        // NUL 必须放在正文中间：trim() 会先吃掉首尾的控制字符，落在边缘就无法触发二进制校验
        byte[] bytes = new byte[]{'A', 0x00, 'B'};
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> KbDocTextParser.parseFile("伪装.txt", bytes));
        assertTrue(error.getMessage().contains("二进制"));
    }

    @Test
    void parseFile_shouldRejectUnsupportedFormat() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> KbDocTextParser.parseFile("安装包.exe", new byte[]{1, 2, 3}));
        assertTrue(error.getMessage().contains("暂不支持解析"));
    }

    @Test
    void parseFile_shouldRejectTextOverLimitInsteadOfTruncating() {
        byte[] bytes = repeat('甲', KbDocTextParser.MAX_TEXT_CHARS + 1)
                .getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> KbDocTextParser.parseFile("超长.txt", bytes));
        assertTrue(error.getMessage().contains("超过单篇"));
    }

    @Test
    void parseFile_shouldRejectEmptyAfterTrim() {
        assertThrows(IllegalArgumentException.class,
                () -> KbDocTextParser.parseFile("空.txt", " \r\n\t ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parseClientText_shouldAcceptPdfExtractedText() {
        KbDocTextParser.ParsedText parsed = KbDocTextParser.parseClientText(
                "pdf", "  扫描件提取正文  ", 1024L, "制度.pdf");

        assertEquals("扫描件提取正文", parsed.getContent());
        assertEquals("pdf", parsed.getFileType());
        assertEquals(1024L, parsed.getSizeBytes());
    }

    @Test
    void parseClientText_shouldDefaultTypeFromFileName() {
        KbDocTextParser.ParsedText parsed = KbDocTextParser.parseClientText(
                null, "正文", null, "制度.docx");
        assertEquals("docx", parsed.getFileType());
    }

    @Test
    void parseClientText_shouldRejectUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> KbDocTextParser.parseClientText("exe", "正文", 10L, "a.exe"));
    }

    @Test
    void sha256_shouldBeStableAndDistinguishContent() {
        String first = KbDocTextParser.sha256("差旅报销");
        String second = KbDocTextParser.sha256("差旅报销");
        String other = KbDocTextParser.sha256("差旅报销 ");

        assertEquals(first, second);
        assertNotEquals(first, other);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void fileType_shouldBeLowerCaseAndHandleMissingExtension() {
        assertEquals("pdf", KbDocTextParser.fileType("A.PDF"));
        assertEquals("", KbDocTextParser.fileType("README"));
        assertEquals("", KbDocTextParser.fileType(null));
        assertTrue(KbDocTextParser.isServerParseable("markdown"));
        assertFalse(KbDocTextParser.isServerParseable("pdf"));
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}

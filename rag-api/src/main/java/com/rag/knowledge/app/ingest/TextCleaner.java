package com.rag.knowledge.app.ingest;

import java.util.regex.Pattern;

/**
 * 入库前的文本治理。顺序有讲究：先归一（保住段落边界）再检测乱码，
 * 否则压缩空白会把「乱码段落」洗成看起来正常的文本。
 */
public final class TextCleaner {

    private static final Pattern LINE_BREAKS = Pattern.compile("\r\n|\r|\n");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD]");
    private static final Pattern SPACES = Pattern.compile("[\\u00A0\\u2007\\u202F]");
    /** '\f'（U+000C）是分页符，PDF 解析靠它算页码，不能当控制符洗掉。 */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000E-\\u001F]");
    private static final Pattern PAGE_BREAK = Pattern.compile("\n*\\f\n*");
    private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

    private TextCleaner() {
    }

    /**
     * 只做无损归一：统一换行、去零宽与软连字符、去掉会污染 tsvector 的控制符、压缩多余空行。
     * 不压缩行内空白——代码块与表格里的空格是内容。
     * 返回文本才是分块偏移量的基准，所以这里加的换行不影响原文溯源。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = LINE_BREAKS.matcher(raw).replaceAll("\n");
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        text = SPACES.matcher(text).replaceAll(" ");
        text = CONTROL.matcher(text).replaceAll("");
        text = text.replace("\t", "    ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        // 分页符独占一行：粘在句尾会让上一页末行和下一页首行被当成同一段
        return PAGE_BREAK.matcher(text).replaceAll("\n\n\f\n\n").trim();
    }

    /**
     * PDF 的 CID 字体缺 ToUnicode 表时，Tika 抽出来的是看着像正文的乱码，静默入库等于往库里灌噪声。
     * 用「可解释字符占比」判定，异常时把处置建议一起抛给调用方。
     */
    public static void rejectGarbled(String text, String sourceName) {
        int total = 0;
        int suspicious = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            if (c >= 0xE000 && c <= 0xF8FF) {
                suspicious++;          // 私用区，CID 乱码的主要落点
            } else if ((c >= 0xFF10 && c <= 0xFF19) || (c >= 0xFF21 && c <= 0xFF3A) || (c >= 0xFF41 && c <= 0xFF5A)) {
                suspicious++;          // 全角字母数字：正常中文排版只出现全角标点，成片全角 ASCII 才是乱码
            } else if (Character.getType(c) == Character.FORMAT) {
                suspicious++;
            }
        }
        if (total == 0) {
            throw new GarbledDocumentException(sourceName, "解析结果为空文本");
        }
        double ratio = (double) suspicious / total;
        if (ratio > 0.15) {
            throw new GarbledDocumentException(sourceName,
                    "疑似 CID 字体乱码（异常字符占比 " + Math.round(ratio * 100) + "%），入库只会得到答非所问的检索结果");
        }
    }

    public static class GarbledDocumentException extends RuntimeException {
        private final String sourceName;

        public GarbledDocumentException(String sourceName, String reason) {
            super(reason + "；文件：" + sourceName
                    + " ｜ 处理建议: 重新导出成带文字层的 PDF，或直接上传可复制文本的版本");
            this.sourceName = sourceName;
        }

        public String sourceName() {
            return sourceName;
        }
    }
}

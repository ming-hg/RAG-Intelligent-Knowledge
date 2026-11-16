package com.rag.knowledge.app.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextCleanerTest {

    private static String text(String fragment, int times) {
        return fragment.repeat(times);
    }

    @Test
    void lineBreaksAndZeroWidthNoiseAreNormalized() {
        String raw = text("第一段\r\n", 2) + "第二段" + (char) 0xA0 + "第三段" + (char) 0x200D + "第四段"
                + text("\n", 4) + "第五段";

        assertThat(TextCleaner.normalize(raw)).isEqualTo("第一段\n第一段\n第二段 第三段第四段\n\n第五段");
    }

    @Test
    void noBreakSpaceBecomesAnOrdinarySpace() {
        String cleaned = TextCleaner.normalize("第二段" + (char) 0xA0 + "第三段");

        assertThat(cleaned).isEqualTo("第二段 第三段");
    }

    @Test
    void tabsBecomeSpacesButInlineSpacingSurvives() {
        assertThat(TextCleaner.normalize("列A" + (char) 9 + "列B")).isEqualTo("列A    列B");
        assertThat(TextCleaner.normalize("a  b")).isEqualTo("a  b");
    }

    /** 页码全靠 '\f' 定位，洗掉它等于所有 PDF 都变成「第 1 页」。 */
    @Test
    void formFeedIsKeptAndGivenItsOwnLine() {
        String expected = "第一页文字\n\n" + (char) 12 + "\n\n第二页文字";

        assertThat(TextCleaner.normalize("第一页文字\n" + (char) 12 + "第二页文字")).isEqualTo(expected);
        assertThat(TextCleaner.normalize("第一页文字" + (char) 12 + "第二页文字")).isEqualTo(expected);
        assertThat(TextCleaner.normalize("第一页文字\n\n\n" + (char) 12 + "\n\n\n第二页文字")).isEqualTo(expected);
    }

    @Test
    void nullAndEmptyStayEmpty() {
        assertThat(TextCleaner.normalize(null)).isEmpty();
        assertThat(TextCleaner.normalize("")).isEmpty();
    }

    @Test
    void ordinaryChineseTextPassesTheGarbleCheck() {
        String text = "本系统使用 pgvector 存储向量，检索时同时走向量路与中文全文路，再做 RRF 融合。";

        assertThatCode(() -> TextCleaner.rejectGarbled(text, "架构.pdf")).doesNotThrowAnyException();
    }

    /** 少量全角标点混在正文里是正常排版，不能因此拒绝整篇文档。 */
    @Test
    void fullWidthPunctuationIsNotGarbled() {
        String text = text("结论：系统可用（详见第 3 章）。", 10);

        assertThatCode(() -> TextCleaner.rejectGarbled(text, "手册.docx")).doesNotThrowAnyException();
    }

    @Test
    void cidFontGarbleIsRejectedWithAnActionableHint() {
        String garbled = text("" + (char) 0xE000 + (char) 0xE001 + (char) 0xE002, 30) + "本地知识库";

        assertThatThrownBy(() -> TextCleaner.rejectGarbled(garbled, "扫描件.pdf"))
                .isInstanceOf(TextCleaner.GarbledDocumentException.class)
                .hasMessageContaining("扫描件.pdf")
                .hasMessageContaining("乱码")
                .hasMessageContaining("带文字层的 PDF");
    }

    @Test
    void blankParseResultIsRejected() {
        assertThatThrownBy(() -> TextCleaner.rejectGarbled("   \n\t ", "空壳.pdf"))
                .isInstanceOf(TextCleaner.GarbledDocumentException.class)
                .hasMessageContaining("空文本");
    }
}

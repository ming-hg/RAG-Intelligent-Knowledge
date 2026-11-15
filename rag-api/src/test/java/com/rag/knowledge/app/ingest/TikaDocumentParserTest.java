package com.rag.knowledge.app.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 页码来自 PDFBox/Tika 的真实事件，所以这里用 PDFBox 现场生成两页 PDF，不依赖测试资源文件。
 */
class TikaDocumentParserTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    private static byte[] pdf(int pages, int linesPerPage) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int p = 1; p <= pages; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(50, 700);
                    for (int line = 1; line <= linesPerPage; line++) {
                        cs.showText("page " + p + " line " + line + " explains the retrieval design.");
                        cs.newLineAtOffset(0, -16);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
        }
        return out.toByteArray();
    }

    @Test
    void pdfPagesBecomeFormFeedsAndPageCount() throws Exception {
        TikaDocumentParser.Parsed parsed = parser.parse(pdf(2, 12), "design.pdf");

        assertThat(parsed.pageCount()).isEqualTo(2);
        assertThat(parsed.mime()).contains("application/pdf");
        assertThat(parsed.rawText().indexOf('\f')).isPositive();
        assertThat(parsed.rawText().lastIndexOf('\f')).isEqualTo(parsed.rawText().indexOf('\f'));
        assertThat(parsed.rawText()).contains("page 1 line 1").contains("page 2 line 12");
        assertThat(parsed.rawText().indexOf("page 2")).isGreaterThan(parsed.rawText().indexOf('\f'));
    }

    /** 分块器只认 '\f'，页码链路断在解析层的话，引用会一律显示「第 1 页」。 */
    @Test
    void blocksCarryTheirRealPages() throws Exception {
        String cleaned = TextCleaner.normalize(parser.parse(pdf(2, 12), "design.pdf").rawText());
        String base = ChineseSemanticSplitter.offsetBase(cleaned);

        List<ChineseSemanticSplitter.Segment> segments = new ChineseSemanticSplitter()
                .split(cleaned, ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).isNotEmpty();
        assertThat(segments).anyMatch(s -> s.pageEnd() == 1);
        assertThat(segments).anyMatch(s -> s.pageStart() == 2 && s.pageEnd() == 2);
        assertThat(segments).allSatisfy(s -> {
            assertThat(s.content()).isEqualTo(base.substring(s.charStart(), s.charEnd()));
            assertThat(s.content()).isNotBlank();
        });
    }

    @Test
    void singlePagePdfStaysOnPageOne() throws Exception {
        String cleaned = TextCleaner.normalize(parser.parse(pdf(1, 3), "one.pdf").rawText());

        List<ChineseSemanticSplitter.Segment> segments = new ChineseSemanticSplitter()
                .split(cleaned, ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(s -> {
            assertThat(s.pageStart()).isEqualTo(1);
            assertThat(s.pageEnd()).isEqualTo(1);
        });
    }

    @Test
    void markdownIsParsedAsPlainTextWithoutPageMarkers() {
        String raw = """
                # 检索设计

                向量路与关键词路各自取 topK，再做 RRF 融合。

                装配阶段按父块补齐上下文。
                """;

        TikaDocumentParser.Parsed parsed = parser.parse(raw.getBytes(StandardCharsets.UTF_8), "design.md");
        List<ChineseSemanticSplitter.Segment> segments = new ChineseSemanticSplitter()
                .split(TextCleaner.normalize(parsed.rawText()), ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(parsed.pageCount()).isEqualTo(1);
        assertThat(parsed.rawText()).doesNotContain("\f").contains("RRF");
        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0).headingPath()).isEqualTo("检索设计");
    }

    @Test
    void damagedBytesFailWithTheFileName() {
        byte[] junk = "%PDF-1.7\r\n\u0000\u0001 broken stream \u0000\u0001\r\n%%EOF".getBytes(StandardCharsets.UTF_8);

        // 解析不出任何正文时必须报错，不能塞一个空文档进 READY 状态
        assertThatThrownBy(() -> {
            TikaDocumentParser.Parsed parsed = parser.parse(junk, "损坏.pdf");
            TextCleaner.rejectGarbled(TextCleaner.normalize(parsed.rawText()), "损坏.pdf");
        }).isInstanceOf(RuntimeException.class);
    }
}

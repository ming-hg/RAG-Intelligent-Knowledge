package com.rag.knowledge.app.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChineseSemanticSplitterTest {

    private final ChineseSemanticSplitter splitter = new ChineseSemanticSplitter();

    /** lines 段 × sentencesPerLine 句 × 每句约 charsPerSentence 字 */
    private static String paragraphs(int lines, int sentencesPerLine, int charsPerSentence) {
        StringBuilder sb = new StringBuilder();
        String filler = "论点说明本地知识库".repeat(Math.max(1, charsPerSentence / 10));
        for (int line = 0; line < lines; line++) {
            for (int s = 0; s < sentencesPerLine; s++) {
                sb.append('第').append(line).append('章').append(s).append('节').append(filler).append('。');
            }
            sb.append('\n').append('\n');
        }
        return sb.toString();
    }

    private List<ChineseSemanticSplitter.Segment> split(String raw, ChineseSemanticSplitter.ChunkConfig config) {
        return splitter.split(TextCleaner.normalize(raw), config);
    }

    @Test
    void noSegmentExceedsTheModelWindow() {
        var config = new ChineseSemanticSplitter.ChunkConfig(400, 120, 60, 1200, true);
        List<ChineseSemanticSplitter.Segment> segments = split(paragraphs(40, 6, 60), config);

        // 只有子块进 embedding 模型，受 512 token 截断约束；父块给装配上下文看，上限是 parentMaxChars
        assertThat(segments).isNotEmpty();
        assertThat(segments.stream().filter(ChineseSemanticSplitter.Segment::searchable).toList())
                .allSatisfy(segment -> assertThat(segment.content()).hasSizeLessThanOrEqualTo(400));
        assertThat(segments.stream().filter(s -> !s.searchable()).toList())
                .allSatisfy(segment -> assertThat(segment.content()).hasSizeLessThanOrEqualTo(1200));
    }

    /** 偏移量写错不会报错，只会让引用跳到错误的原文位置，所以逐块回验。 */
    @Test
    void offsetsPointBackAtTheOriginalText() {
        String cleaned = TextCleaner.normalize(paragraphs(25, 4, 50));

        List<ChineseSemanticSplitter.Segment> segments = splitter.split(cleaned,
                ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment ->
                assertThat(cleaned.substring(segment.charStart(), segment.charEnd()).trim()).isEqualTo(segment.content()));
    }

    @Test
    void longSectionGetsParentAndSearchableChildren() {
        var config = new ChineseSemanticSplitter.ChunkConfig(300, 100, 50, 900, true);
        List<ChineseSemanticSplitter.Segment> segments = split(paragraphs(12, 8, 60), config);

        List<ChineseSemanticSplitter.Segment> parents = segments.stream().filter(s -> !s.searchable()).toList();
        List<ChineseSemanticSplitter.Segment> children = segments.stream()
                .filter(s -> s.searchable() && s.parentChunkNo() != null).toList();

        assertThat(parents).isNotEmpty();
        assertThat(children).isNotEmpty();
        assertThat(children).allSatisfy(child -> {
            ChineseSemanticSplitter.Segment parent = segments.get(child.parentChunkNo());
            assertThat(parent.searchable()).isFalse();
            assertThat(parent.charStart()).isLessThanOrEqualTo(child.charStart());
            assertThat(parent.charEnd()).isGreaterThanOrEqualTo(child.charEnd());
        });
    }

    @Test
    void singleBlockSectionIsNotDuplicatedAsParentAndChild() {
        List<ChineseSemanticSplitter.Segment> segments = split("只有一句话的短文档，没有可以再分的空间。",
                ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).searchable()).isTrue();
        assertThat(segments.get(0).parentChunkNo()).isNull();
    }

    /** 一个父块单位装下全部段落，这样相邻块之间的关系只由子块切分与重叠逻辑决定。 */
    private static final ChineseSemanticSplitter.ChunkConfig OVERLAP_CONFIG =
            new ChineseSemanticSplitter.ChunkConfig(200, 60, 60, 4000, false);

    @Test
    void consecutiveSentencesAreOverlappedWithinTheSameSection() {
        List<ChineseSemanticSplitter.Segment> blocks = blocks(split(paragraphs(10, 4, 60), OVERLAP_CONFIG));

        assertThat(blocks).hasSizeGreaterThan(2);
        for (int i = 1; i < blocks.size(); i++) {
            ChineseSemanticSplitter.Segment previous = blocks.get(i - 1);
            ChineseSemanticSplitter.Segment current = blocks.get(i);
            int shared = previous.charEnd() - current.charStart();

            // 每对相邻块都要真的重叠，否则横跨块边界的那句永远搜不到
            assertThat(shared).isPositive();
            assertThat(current.content()).startsWith(previous.content().substring(previous.content().length() - shared));
        }
    }

    @Test
    void blocksNeverStartMidSentence() {
        String cleaned = TextCleaner.normalize(paragraphs(10, 4, 60));
        List<ChineseSemanticSplitter.Segment> blocks = blocks(splitter.split(cleaned, OVERLAP_CONFIG));

        for (int i = 1; i < blocks.size(); i++) {
            int start = blocks.get(i).charStart();
            // 前一个非空白字符必须是句末标点：重叠只能整句对齐，不能切出半句
            while (start > 0 && Character.isWhitespace(cleaned.charAt(start - 1))) {
                start--;
            }
            assertThat(cleaned.charAt(start - 1))
                    .as("第 %d 块从半句开始", i)
                    .isIn('。', '！', '？', '；', '!', '?', ';');
        }
    }

    @Test
    void overlapCanBeTurnedOff() {
        var config = new ChineseSemanticSplitter.ChunkConfig(200, 60, 0, 4000, false);
        List<ChineseSemanticSplitter.Segment> blocks = blocks(split(paragraphs(10, 4, 60), config));

        assertThat(blocks).hasSizeGreaterThan(2);
        for (int i = 1; i < blocks.size(); i++) {
            assertThat(blocks.get(i).charStart()).isGreaterThanOrEqualTo(blocks.get(i - 1).charEnd());
        }
    }

    /** 参与向量检索的块，按 chunkNo 顺序。 */
    private static List<ChineseSemanticSplitter.Segment> blocks(List<ChineseSemanticSplitter.Segment> segments) {
        return segments.stream().filter(ChineseSemanticSplitter.Segment::searchable).toList();
    }

    @Test
    void markdownHeadingsBuildTheHeadingPath() {
        String doc = """
                # 系统架构

                本节说明整体分层与依赖方向。

                ## 检索层

                检索层同时走向量路与关键词路，再做 RRF 融合。

                ## 装配层

                装配层按父块补齐上下文，并给每段答案标注引用来源。
                """;
        List<ChineseSemanticSplitter.Segment> segments = split(doc, ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0).headingPath()).isEqualTo("系统架构");
        assertThat(segments).anySatisfy(segment -> {
            assertThat(segment.headingPath()).isEqualTo("系统架构 > 检索层");
            assertThat(segment.content()).contains("RRF");
        });
    }

    @Test
    void decorativeLinesAreNotEmbedded() {
        String doc = """
                ----------------

                正文从这里开始，包含足够长的句子以便形成一个可检索的块，这里补充更多内容让块不至于过短而被召回忽略。

                ————————
                """;
        List<ChineseSemanticSplitter.Segment> segments = split(doc, ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).content()).contains("正文从这里开始");
    }

    @Test
    void shortPagesMergeIntoOneBlockSpanningBothPages() {
        String doc = "第一页的句子在这里结束。\n\n\f第二页的句子也从这里结束。\n\n";
        List<ChineseSemanticSplitter.Segment> segments = split(doc, ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).pageStart()).isEqualTo(1);
        assertThat(segments.get(0).pageEnd()).isEqualTo(2);
    }

    @Test
    void longPagesKeepTheirOwnPageNumbers() {
        String doc = paragraphs(8, 4, 60) + "\f" + paragraphs(8, 4, 60);
        List<ChineseSemanticSplitter.Segment> segments = split(doc, ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).anyMatch(s -> s.pageEnd() == 1);
        assertThat(segments).anyMatch(s -> s.pageStart() == 2 && s.pageEnd() == 2);
        assertThat(segments).allSatisfy(s -> assertThat(s.pageStart()).isLessThanOrEqualTo(s.pageEnd()));
    }

    @Test
    void textWithoutPageMarkersIsAlwaysPageOne() {
        List<ChineseSemanticSplitter.Segment> segments = split(paragraphs(3, 2, 40),
                ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).allSatisfy(segment -> {
            assertThat(segment.pageStart()).isEqualTo(1);
            assertThat(segment.pageEnd()).isEqualTo(1);
        });
    }

    @Test
    void chunkNumbersAreDenseAndOrdered() {
        List<ChineseSemanticSplitter.Segment> segments = split(paragraphs(20, 6, 60),
                ChineseSemanticSplitter.ChunkConfig.defaults());

        assertThat(segments).filteredOn(s -> s.parentChunkNo() != null).isNotEmpty();
        assertThat(segments.stream().map(ChineseSemanticSplitter.Segment::chunkNo).toList())
                .isEqualTo(java.util.stream.IntStream.range(0, segments.size()).boxed().toList());
    }

    @Test
    void invalidConfigsAreRejectedWithReasons() {
        assertThatThrownBy(() -> new ChineseSemanticSplitter.ChunkConfig(20, 10, 5, 100, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("childChars");
        assertThatThrownBy(() -> new ChineseSemanticSplitter.ChunkConfig(400, 120, 500, 1200, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlapChars");
        assertThatThrownBy(() -> new ChineseSemanticSplitter.ChunkConfig(400, 120, 60, 200, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentMaxChars");
    }

    @Test
    void emptyAndBlankInputProduceNoSegments() {
        assertThat(splitter.split("", ChineseSemanticSplitter.ChunkConfig.defaults())).isEmpty();
        assertThat(splitter.split(null, ChineseSemanticSplitter.ChunkConfig.defaults())).isEmpty();
        assertThat(split("   \n\n  \t \n", ChineseSemanticSplitter.ChunkConfig.defaults())).isEmpty();
    }
}

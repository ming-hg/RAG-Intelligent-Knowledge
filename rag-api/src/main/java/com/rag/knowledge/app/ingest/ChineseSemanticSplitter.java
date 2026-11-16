package com.rag.knowledge.app.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 中文语义分块：段落 → 章节（父块）→ 句子级子块。
 * <p>
 * 尺寸上限来自模型而不是审美：bge-base-zh-v1.5 在 512 token 处静默截断，中文约 1 字 1 token，
 * 子块按 400 字留余量，超出部分不是被模型看见，而是被丢掉。
 */
@Component
public final class ChineseSemanticSplitter {

    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+\\S.*$");
    private static final Pattern CN_HEADING = Pattern.compile(
            "^\\s*(第[零一二三四五六七八九十百千0-9]+[章节部分篇条款]|附[录件]|摘要|参考文献|目录|前言)[：:]?\\s*\\S{0,40}$");
    private static final Pattern NUM_HEADING = Pattern.compile("^\\s*(\\d+(?:\\.\\d+){0,3})\\s+\\S{1,40}$");
    private static final Pattern MEANINGFUL = Pattern.compile("[\\p{IsHan}\\p{Alpha}\\d]");

    public record ChunkConfig(int childChars, int childMinChars, int overlapChars, int parentMaxChars,
                              boolean parentChild) {

        public static ChunkConfig defaults() {
            return new ChunkConfig(400, 120, 60, 1200, true);
        }

        public ChunkConfig {
            if (childChars < 50) {
                throw new IllegalArgumentException("childChars 太小（<50）会切碎语义，建议 300–450");
            }
            if (childMinChars <= 0 || childMinChars > childChars) {
                throw new IllegalArgumentException("childMinChars 必须在 0 与 childChars 之间");
            }
            if (overlapChars < 0 || overlapChars >= childChars) {
                throw new IllegalArgumentException("overlapChars 不能大于等于 childChars");
            }
            if (parentMaxChars < childChars) {
                throw new IllegalArgumentException("parentMaxChars 不能小于 childChars，否则父子块没有意义");
            }
        }
    }

    /**
     * parentChunkNo 为 null 的行既是检索命中块也是上下文块；父块行只作为装配上下文的来源，不参与向量检索。
     */
    public record Segment(String content, int chunkNo, Integer parentChunkNo, int charStart, int charEnd,
                          String headingPath, int pageStart, int pageEnd, boolean searchable) {
    }

    private record Span(int start, int end) {
    }

    /**
     * 分块偏移量的基准文本。'\f' 与 '\n' 等宽互换，所以页码定位和字符溯源用的是同一套下标；
     * kb_document.content 存的是 split 入参那份清洗后文本，回放偏移量时必须先过这里。
     */
    public static String offsetBase(String cleaned) {
        return cleaned == null ? "" : cleaned.replace('\f', '\n');
    }

    public List<Segment> split(String cleaned, ChunkConfig config) {
        if (cleaned == null || cleaned.isBlank()) {
            return List.of();
        }
        int[] pageBreaks = pageIndexes(cleaned);
        String text = offsetBase(cleaned);

        List<Segment> segments = new ArrayList<>();
        for (Section section : sections(text)) {
            String headingPath = String.join(" > ", section.headingPath());
            for (Span parentUnit : parentUnits(text, section.paraRanges(), config)) {
                emitUnit(text, parentUnit, headingPath, pageBreaks, config, segments);
            }
        }
        return segments;
    }

    private void emitUnit(String text, Span unit, String headingPath, int[] pageBreaks, ChunkConfig config,
                          List<Segment> out) {
        List<Span> children = childSpans(text, unit, config);
        if (children.isEmpty()) {
            return;
        }
        if (children.size() == 1 || !config.parentChild()) {
            for (Span child : children) {
                add(out, text, child, headingPath, pageBreaks, null, true);
            }
            return;
        }
        int added = add(out, text, unit, headingPath, pageBreaks, null, false);
        Integer parentNo = added < 0 ? null : added;
        for (Span child : children) {
            add(out, text, child, headingPath, pageBreaks, parentNo, true);
        }
    }

    /** 返回该块在 out 中的序号；被丢弃时返回 -1，调用方据此决定子块的父指针。 */
    private int add(List<Segment> out, String text, Span span, String headingPath, int[] pageBreaks,
                    Integer parentNo, boolean searchable) {
        int start = span.start();
        int end = span.end();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return -1;
        }
        String content = text.substring(start, end);
        if (!MEANINGFUL.matcher(content).find()) {
            return -1;   // 纯符号/页眉页脚，入库会返回 768 维的噪声向量
        }
        out.add(new Segment(content, out.size(), parentNo, start, end, headingPath,
                pageOf(pageBreaks, start), pageOf(pageBreaks, end - 1), searchable));
        return out.size() - 1;
    }

    /** 父块单位：按段落累积到 parentMaxChars 为止；单段超长时自成单位。 */
    private List<Span> parentUnits(String text, List<Span> paragraphs, ChunkConfig config) {
        List<Span> units = new ArrayList<>();
        int unitStart = -1;
        int unitEnd = -1;
        for (Span paragraph : paragraphs) {
            int paragraphChars = paragraph.end() - paragraph.start();
            if (unitStart < 0) {
                unitStart = paragraph.start();
                unitEnd = paragraph.end();
                continue;
            }
            boolean fits = paragraphChars + (unitEnd - unitStart) <= config.parentMaxChars();
            if (fits) {
                unitEnd = Math.max(unitEnd, paragraph.end());
            } else {
                units.add(new Span(unitStart, unitEnd));
                unitStart = paragraph.start();
                unitEnd = paragraph.end();
            }
        }
        if (unitStart >= 0) {
            units.add(new Span(unitStart, unitEnd));
        }
        return units;
    }

    /**
     * 子块：句子边界优先，够 childChars 就收口。
     * 上限是不可以商量的：任何超过 childChars 的块都会被 embedding 模型在 512 token 处静默截断，
     * 表现为「文档里有这段却搜不到」，且没有任何报错，所以宁可留碎块也不能并成超限块。
     */
    private List<Span> childSpans(String text, Span unit, ChunkConfig config) {
        List<Span> pieces = new ArrayList<>();
        for (Span sentence : sentences(text, unit)) {
            if (sentence.end() - sentence.start() > config.childChars()) {
                pieces.addAll(hardSplit(text, sentence, config.childChars()));
            } else {
                pieces.add(sentence);
            }
        }
        List<Span> children = new ArrayList<>();
        int start = pieces.get(0).start();
        int end = pieces.get(0).end();
        for (int i = 1; i < pieces.size(); i++) {
            Span piece = pieces.get(i);
            if (piece.end() - start > config.childChars()) {
                Span closed = new Span(start, end);
                children.add(closed);
                start = overlapStart(text, closed, piece, config);
                end = piece.end();
            } else {
                end = piece.end();
            }
        }
        children.add(new Span(start, end));

        List<Span> merged = new ArrayList<>();
        for (Span child : children) {
            Span previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            boolean tooSmall = child.end() - child.start() < config.childMinChars();
            boolean fits = previous != null && child.end() - previous.start() <= config.childChars();
            if (tooSmall && fits) {
                merged.set(merged.size() - 1, new Span(previous.start(), child.end()));
            } else {
                merged.add(child);
            }
        }
        return merged;
    }

    /** 无句子边界的长块（表格、代码、无标点文本）按次级标点退让切，最后才硬切。 */
    private List<Span> hardSplit(String text, Span sentence, int limit) {
        List<Span> result = new ArrayList<>();
        int start = sentence.start();
        while (sentence.end() - start > limit) {
            int cut = fallbackCut(text, start + limit - 1, start, limit);
            result.add(new Span(start, cut));
            start = cut;
        }
        if (sentence.end() > start) {
            result.add(new Span(start, sentence.end()));
        }
        return result;
    }

    /** 从上限位置往回找最近的次级标点；找不到就在上限处硬切，保证块不会超模型窗口。 */
    private int fallbackCut(String text, int ceiling, int start, int limit) {
        int floor = start + limit / 2;
        for (int j = ceiling; j > floor; j--) {
            char c = text.charAt(j);
            if (c == '，' || c == '、' || c == ',' || c == ' ' || c == '：' || c == ':') {
                return j + 1;
            }
        }
        return ceiling + 1;
    }

    /**
     * 重叠起点必须是整句边界，否则引用会指向半句话，用户在原文里对不上。
     * overlapChars 只是预算：句子比预算长时往前多带一句，宁超预算也不出半句。
     */
    private int overlapStart(String text, Span previous, Span nextSentence, ChunkConfig config) {
        if (config.overlapChars() == 0) {
            return nextSentence.start();
        }
        int from = Math.max(previous.start(), previous.end() - config.overlapChars());
        int boundary = lastSentenceStart(text, from, previous.end());
        if (boundary < 0) {
            boundary = lastSentenceStart(text, previous.start(), from);
        }
        return boundary > previous.start() ? boundary : nextSentence.start();
    }

    /** 返回 [from, to) 这段里最后一个完整句子的起始下标；没有句子边界时返回 -1。 */
    private int lastSentenceStart(String text, int from, int to) {
        for (int i = to - 2; i >= from; i--) {
            if (isSentenceEnd(text.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private List<Span> sentences(String text, Span unit) {
        List<Span> result = new ArrayList<>();
        int start = unit.start();
        for (int i = unit.start(); i < unit.end(); i++) {
            char c = text.charAt(i);
            if (isSentenceEnd(c)) {
                int end = c == '\n' ? i : i + 1;
                if (end > start) {
                    result.add(new Span(start, end));
                }
                start = i + 1;
            }
        }
        if (unit.end() > start) {
            result.add(new Span(start, unit.end()));
        }
        return result.isEmpty() ? List.of(unit) : result;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';' || c == '\n';
    }

    private record Section(List<Span> paraRanges, List<String> headingPath) {
    }

    /** 段落级扫描：保留绝对偏移，同时按标题行切章节。 */
    private List<Section> sections(String text) {
        List<Section> sections = new ArrayList<>();
        List<Span> paragraphs = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        Deque<String> markdownStack = new ArrayDeque<>();

        int cursor = 0;
        while (cursor < text.length()) {
            int lineEnd = text.indexOf('\n', cursor);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(cursor, lineEnd);
            if (line.isBlank()) {
                cursor = lineEnd + 1;
                continue;
            }
            int headingLevel = headingLevel(line);
            if (headingLevel > 0) {
                if (!paragraphs.isEmpty()) {
                    sections.add(new Section(List.copyOf(paragraphs), List.copyOf(headingPath)));
                    paragraphs = new ArrayList<>();
                }
                while (markdownStack.size() >= headingLevel && !markdownStack.isEmpty()) {
                    markdownStack.removeLast();
                }
                markdownStack.addLast(stripHeading(line));
                headingPath = List.copyOf(markdownStack);
                cursor = lineEnd + 1;
                continue;
            }
            int previousEnd = paragraphs.isEmpty() ? -1 : paragraphs.get(paragraphs.size() - 1).end();
            boolean continues = previousEnd >= 0 && previousEnd == cursor - 1;
            if (continues) {
                Span last = paragraphs.remove(paragraphs.size() - 1);
                paragraphs.add(new Span(last.start(), lineEnd));
            } else {
                paragraphs.add(new Span(cursor, lineEnd));
            }
            cursor = lineEnd + 1;
        }
        if (!paragraphs.isEmpty()) {
            sections.add(new Section(List.copyOf(paragraphs), List.copyOf(headingPath)));
        }
        return sections;
    }

    /** 0 表示非标题；返回层级用于维护标题栈。 */
    private int headingLevel(String line) {
        var md = MD_HEADING.matcher(line);
        if (md.matches()) {
            return md.group(1).length();
        }
        if (CN_HEADING.matcher(line).matches()) {
            return 1;
        }
        var num = NUM_HEADING.matcher(line);
        if (num.matches()) {
            return Math.min(num.group(1).split("\\.").length, 6);
        }
        return 0;
    }

    private String stripHeading(String line) {
        String stripped = line.trim();
        stripped = stripped.replaceFirst("^#+\\s*", "");
        return stripped.length() > 60 ? stripped.substring(0, 60) : stripped;
    }

    private int[] pageIndexes(String text) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\f') {
                indexes.add(i);
            }
        }
        return indexes.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 页码从 1 开始；无分页符的文本（md/txt）恒为 1。 */
    private int pageOf(int[] pageBreaks, int charIndex) {
        int page = 1;
        for (int index : pageBreaks) {
            if (index < charIndex) {
                page++;
            } else {
                break;
            }
        }
        return page;
    }
}

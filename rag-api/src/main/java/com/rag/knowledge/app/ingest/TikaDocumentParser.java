package com.rag.knowledge.app.ingest;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * Tika 解析：拿到带页边界的纯文本。
 * <p>
 * 为什么不用现成的 parseToString：实测 Tika 3.3.3 的纯文本输出<b>不含分页符</b>，
 * 页边界只存在于 XHTML 事件的 {@code <div class="page">} 上。页码是引用的定位信息，
 * 所以这里自己接 SAX 事件，把页 div 翻成 '\f' 交给 {@link TextCleaner} 与分块器。
 */
@Component
public class TikaDocumentParser {

    private static final Set<String> BLOCK_END = Set.of("p", "div", "li", "br", "table", "tr", "td", "th",
            "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "code", "pre");

    public record Parsed(String rawText, int pageCount, String mime) {
    }

    private final Parser parser = new AutoDetectParser();

    public Parsed parse(byte[] content, String fileName) {
        Metadata metadata = new Metadata();
        if (fileName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        ParseContext context = new ParseContext();
        // 扫描件走 OCR 需要外部 tesseract，本版本明确不做：宁可报空文本也不要静默灌噪声
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdfConfig);

        PageAwareTextHandler handler = new PageAwareTextHandler();
        try {
            parser.parse(new ByteArrayInputStream(content), handler, metadata, context);
        } catch (IOException | SAXException | TikaException e) {
            throw new IllegalStateException("解析失败：" + (fileName == null ? "未知文件" : fileName)
                    + " ｜ 原因：" + rootMessage(e), e);
        }
        return new Parsed(handler.text(), pageCount(metadata, handler.pages()), metadata.get(HttpHeaders.CONTENT_TYPE));
    }

    /**
     * 优先用解析器报的页数（PDF 的 xmpTPg:NPages）；没有页概念的类型（md/txt/docx）返回 1，
     * 这样 kb_document.page_count 与块上的 page_start/page_end 永远自洽。
     */
    private int pageCount(Metadata metadata, int pagesSeen) {
        for (String key : new String[] { "xmpTPg:NPages", "page-count", "meta:page-count", "w:sdtCount" }) {
            String value = metadata.get(key);
            if (value != null) {
                try {
                    int parsed = Integer.parseInt(value.trim());
                    return parsed > 0 ? parsed : 1;
                } catch (NumberFormatException ignored) {
                    return Math.max(pagesSeen, 1);
                }
            }
        }
        return Math.max(pagesSeen, 1);
    }

    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName() + ": " + cursor.getMessage();
    }

    private static final class PageAwareTextHandler extends DefaultHandler {

        private final StringBuilder out = new StringBuilder();
        private int pages;
        private boolean inBody;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("body".equals(localName)) {
                inBody = true;
                return;
            }
            if (!inBody) {
                return;
            }
            if ("div".equals(localName) && "page".equals(attributes.getValue("class"))) {
                pages++;
                // 页与页之间才插分页符：页首插会让所有正文都算到第 2 页
                if (pages > 1) {
                    out.append('\f');
                }
            } else if ("br".equals(localName)) {
                out.append('\n');
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inBody) {
                out.append(ch, start, length);
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
            characters(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("body".equals(localName)) {
                inBody = false;
                return;
            }
            if (inBody && BLOCK_END.contains(localName)) {
                out.append('\n');
            }
        }

        String text() {
            return out.toString();
        }

        int pages() {
            return pages;
        }
    }
}

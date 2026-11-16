package com.rag.knowledge.app.ingest;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档级分块参数覆盖（kb_document.chunk_override）。
 * <p>
 * 只存「与全局默认不同」的那几个键：整份抄一遍的话，改了全局默认也追不上，覆盖列会变成一坨过期快照。
 * 键缺席即「这项跟随全局」，所以这里全字段可空，合并时逐键回退。
 */
public record ChunkOverride(Integer childChars, Integer childMinChars, Integer overlapChars,
                           Integer parentMaxChars, Boolean parentChild) {

    private static final Logger log = LoggerFactory.getLogger(ChunkOverride.class);

    public static final ChunkOverride EMPTY = new ChunkOverride(null, null, null, null, null);

    /** 库里存的坏 JSON 不该让整篇文档入库失败：退化成「没有覆盖」并留一条日志。 */
    public static ChunkOverride read(ObjectMapper json, String raw) {
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return EMPTY;
        }
        try {
            ChunkOverride stored = json.readValue(raw, ChunkOverride.class);
            return stored == null ? EMPTY : stored;
        } catch (RuntimeException ex) {
            log.warn("chunk_override 解析失败，按全局默认处理：{}（原文 {}）", ex.getMessage(), raw);
            return EMPTY;
        }
    }

    /** 空覆盖写 NULL 而不是 '{}'：列里留 NULL，才分得清「没设过」和「设成全部等于默认」。 */
    public String write(ObjectMapper json) {
        if (isEmpty()) {
            return null;
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        if (childChars != null) {
            keys.put("childChars", childChars);
        }
        if (childMinChars != null) {
            keys.put("childMinChars", childMinChars);
        }
        if (overlapChars != null) {
            keys.put("overlapChars", overlapChars);
        }
        if (parentMaxChars != null) {
            keys.put("parentMaxChars", parentMaxChars);
        }
        if (parentChild != null) {
            keys.put("parentChild", parentChild);
        }
        return json.writeValueAsString(keys);
    }

    public boolean isEmpty() {
        return childChars == null && childMinChars == null && overlapChars == null && parentMaxChars == null
                && parentChild == null;
    }

    /** 逐键取第一个非 null：请求参数覆盖文档覆盖，文档覆盖覆盖全局默认。 */
    public ChunkOverride orElse(ChunkOverride fallback) {
        if (fallback == null) {
            return this;
        }
        return new ChunkOverride(childChars == null ? fallback.childChars : childChars,
                childMinChars == null ? fallback.childMinChars : childMinChars,
                overlapChars == null ? fallback.overlapChars : overlapChars,
                parentMaxChars == null ? fallback.parentMaxChars : parentMaxChars,
                parentChild == null ? fallback.parentChild : parentChild);
    }

    /**
     * 合并成真正生效的参数，并把越界值挡成 400。
     * <p>
     * 边界必须在这里查而不是让 {@link ChineseSemanticSplitter.ChunkConfig} 抛
     * {@link IllegalArgumentException}：那样用户填错参数会变成服务故障，而且手工改过库的人会把入库 worker 直接打死。
     */
    public ChineseSemanticSplitter.ChunkConfig merged(RagProperties.Chunk global) {
        ChunkOverride full = orElse(of(global));
        if (full.childChars < 50 || full.childChars > 4000 || full.childMinChars <= 0
                || full.childMinChars > full.childChars || full.overlapChars < 0
                || full.overlapChars >= full.childChars || full.parentMaxChars < full.childChars) {
            throw RagException.badRequest("分块参数不合理：child-chars=" + full.childChars + "，child-min-chars="
                    + full.childMinChars + "，overlap-chars=" + full.overlapChars + "，parent-max-chars="
                    + full.parentMaxChars + " ｜ 约束: 50<=child<=4000，0<min<=child，overlap<child，parent>=child"
                    + "（子块超过 400 字会被 bge 的 512 token 窗口静默截断）");
        }
        return new ChineseSemanticSplitter.ChunkConfig(full.childChars, full.childMinChars, full.overlapChars,
                full.parentMaxChars, full.parentChild);
    }

    /** 落库前把「跟全局一样」的键摘掉，覆盖列里只留真正的差异。 */
    public ChunkOverride deltasAgainst(RagProperties.Chunk global) {
        return new ChunkOverride(keep(childChars, global.childChars()), keep(childMinChars, global.childMinChars()),
                keep(overlapChars, global.overlapChars()), keep(parentMaxChars, global.parentMaxChars()),
                parentChild != null && parentChild != global.parentChild() ? parentChild : null);
    }

    private static ChunkOverride of(RagProperties.Chunk global) {
        return new ChunkOverride(global.childChars(), global.childMinChars(), global.overlapChars(),
                global.parentMaxChars(), global.parentChild());
    }

    private static Integer keep(Integer value, int fallback) {
        return value == null || value == fallback ? null : value;
    }
}

package com.rag.knowledge.web;

import com.rag.knowledge.app.rag.HybridSearchService;
import com.rag.knowledge.app.rag.SearchHit;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.common.RagException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 只检索、不生成：调 topK / 阈值 / 分块参数时靠它当场看效果，不必每次等问题生成完。
 * <p>
 * 响应里除了命中还带回「这次实际走了哪几路、用的哪种关键词能力」，因为召回变差时第一个问题
 * 永远是「到底搜了没有」，而静默降级在纯命中列表里看不出来。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final HybridSearchService searchService;

    public SearchController(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    public record SearchBody(String query, List<Long> kbIds, List<Long> docIds, String mode, Integer topK,
                             Integer snippetChars) {
    }

    public record HitView(int rank, long segmentId, long docId, String docName, int chunkNo, String location,
                          String snippet, Integer pageStart, Integer pageEnd, Integer charStart, Integer charEnd,
                          boolean hasParent, Integer vectorRank, Integer keywordRank, double rrf,
                          Double similarity) {
    }

    public record SearchView(String requestedMode, String effectiveMode, String keywordEngine,
                             String keywordEngineNote, boolean degraded, String vectorFailure, int hitCount,
                             long tookMs, List<HitView> hits) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SearchView> search(@RequestBody SearchBody body) {
        return ApiResponse.ok(execute(body));
    }

    @GetMapping
    public ApiResponse<SearchView> searchGet(@RequestParam String q,
                                            @RequestParam(required = false) Long kbId,
                                            @RequestParam(required = false) List<Long> kbIds,
                                            @RequestParam(required = false) List<Long> docIds,
                                            @RequestParam(required = false) String mode,
                                            @RequestParam(required = false) Integer topK) {
        List<Long> scopes = kbIds == null || kbIds.isEmpty()
                ? (kbId == null ? List.of() : List.of(kbId)) : kbIds;
        return ApiResponse.ok(execute(new SearchBody(q, scopes, docIds, mode, topK, null)));
    }

    private SearchView execute(SearchBody body) {
        if (body == null || body.query() == null || body.query().isBlank()) {
            throw RagException.badRequest("query 不能为空");
        }
        if (body.kbIds() == null || body.kbIds().isEmpty()) {
            throw RagException.badRequest("必须指定检索范围（kbId 或 kbIds）"
                    + " ｜ 单库部署用 kbId=1，也可以先调 GET /api/knowledge-bases 看有哪些库");
        }
        HybridSearchService.Outcome outcome = searchService.search(body.query().trim(), body.kbIds(),
                body.docIds(), body.mode(), body.topK());
        int snippetChars = body.snippetChars() == null ? 160 : Math.min(Math.max(body.snippetChars(), 20), 2000);
        List<HitView> hits = new ArrayList<>(outcome.hits().size());
        for (int i = 0; i < outcome.hits().size(); i++) {
            hits.add(view(outcome.hits().get(i), i + 1, snippetChars));
        }
        return new SearchView(outcome.requested().name(), outcome.effective().name(), outcome.engine().name(),
                outcome.engine().description(), outcome.degraded(), outcome.vectorFailure(), hits.size(),
                outcome.tookMs(), hits);
    }

    private static HitView view(SearchHit hit, int rank, int snippetChars) {
        return new HitView(rank, hit.segmentId(), hit.docId(), hit.docName(), hit.chunkNo(), hit.location(),
                hit.snippet(snippetChars), hit.pageStart(), hit.pageEnd(), hit.charStart(), hit.charEnd(),
                hit.parentId() != null, hit.vectorRank(), hit.keywordRank(), hit.rrf(), hit.similarity());
    }
}

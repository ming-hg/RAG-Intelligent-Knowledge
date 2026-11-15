package com.rag.knowledge.web;

import com.rag.knowledge.app.ingest.IngestService;
import com.rag.knowledge.common.ApiResponse;
import com.rag.knowledge.db.KnowledgeBaseRepository;
import com.rag.knowledge.db.KnowledgeBaseRepository.KnowledgeBase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库列表。第一版只有一个库，但这个接口决定了前端要不要做库切换 UI，
 * 以及启动时默认库是否已经按当前 embedding 配置建好。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseRepository knowledgeBases;
    private final IngestService ingestService;

    public KnowledgeBaseController(KnowledgeBaseRepository knowledgeBases, IngestService ingestService) {
        this.knowledgeBases = knowledgeBases;
        this.ingestService = ingestService;
    }

    public record KnowledgeBaseView(long id, String name, String embeddingModel, int embeddingDim, int indexVersion) {

        static KnowledgeBaseView of(KnowledgeBase kb) {
            return new KnowledgeBaseView(kb.id(), kb.name(), kb.embeddingModel(), kb.embeddingDim(), kb.indexVersion());
        }
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseView>> list() {
        long defaultId = ingestService.defaultKbId();
        return ApiResponse.ok(knowledgeBases.list().stream().map(KnowledgeBaseView::of).toList(),
                "默认知识库 id=" + defaultId);
    }
}

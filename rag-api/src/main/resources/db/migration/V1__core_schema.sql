-- 核心 schema：知识库 / 文档 / 分块
-- 注意：kb_segment.embedding 的维度由 Flyway 占位符 embedding_dim 注入（application.yml 中与 rag.embedding.dim 同源）。
--       变更维度 = 需要全量重算向量，启动时会校验库内已存的 embedding_model 与配置是否一致。

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- zhparser 可能编译失败或不可用：只探测、不阻断，运行能力由 /api/health 报告
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'zhparser') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS zhparser';
        IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese') THEN
            EXECUTE 'CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser)';
            EXECUTE 'ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l,j,x WITH simple';
        END IF;
    END IF;
END $$;

CREATE TABLE knowledge_base (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             text         NOT NULL,
    description      text,
    embedding_model  text         NOT NULL,
    embedding_dim    integer      NOT NULL,
    chunk_config     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    retrieve_config  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    prompt_config    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    index_version    integer      NOT NULL DEFAULT 1,
    status           text         NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    deleted_at       timestamptz
);

CREATE TABLE kb_document (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kb_id          bigint        NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    name           text          NOT NULL,
    source_type    text          NOT NULL DEFAULT 'FILE',
    file_path      text,
    file_url       text,
    file_size      bigint,
    mime           text,
    sha256         text          NOT NULL,
    page_count     integer,
    char_count     integer,
    content        text,         -- 清洗后正文（保留 '\f' 分页符）：偏移量基准 + 重新分块不必再跑 Tika
    status         text          NOT NULL DEFAULT 'PENDING',
    progress       integer       NOT NULL DEFAULT 0,
    progress_msg   text,
    error_code     text,
    error_msg      text,
    retry_count    integer       NOT NULL DEFAULT 0,
    version        integer       NOT NULL DEFAULT 1,
    index_version  integer       NOT NULL DEFAULT 1,
    available      boolean       NOT NULL DEFAULT true,
    chunk_override jsonb,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    parsed_at      timestamptz,
    indexed_at     timestamptz,
    deleted_at     timestamptz
);

CREATE INDEX idx_doc_kb ON kb_document (kb_id, status);

-- 同一文档内容去重：同库同哈希视为同一文档（软删后允许重新上传）
CREATE UNIQUE INDEX uq_doc_kb_sha ON kb_document (kb_id, sha256) WHERE deleted_at IS NULL;

CREATE TABLE kb_segment (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id          bigint       NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    kb_id           bigint       NOT NULL,
    parent_id       bigint       REFERENCES kb_segment (id) ON DELETE SET NULL,
    chunk_no        integer      NOT NULL,
    doc_version     integer      NOT NULL DEFAULT 1,
    content         text         NOT NULL,
    content_tokens  integer,
    heading_path    text,
    page_start      integer,
    page_end        integer,
    char_start      integer,
    char_end        integer,
    embedding       vector(${embedding_dim}),
    embedding_model text,
    emb_status      text         NOT NULL DEFAULT 'PENDING',
    available       boolean      NOT NULL DEFAULT true,
    hit_count       integer      NOT NULL DEFAULT 0,
    questions       text[],
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_segment_chunk UNIQUE (doc_id, chunk_no, doc_version)
);

CREATE INDEX idx_segment_kb_doc ON kb_segment (kb_id, doc_id, doc_version);
CREATE INDEX idx_segment_parent ON kb_segment (parent_id);

-- HNSW：LangChain4j 的 store 只会建 IVFFlat，我们自建仓储所以手工建 HNSW
CREATE INDEX idx_segment_embedding_hnsw ON kb_segment USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 关键词路径：优先中文分词的 tsvector，zhparser 不可用时仅保留 pg_trgm 子串匹配
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese') THEN
        EXECUTE format(
            'ALTER TABLE kb_segment ADD COLUMN content_ts tsvector GENERATED ALWAYS AS (to_tsvector(%L::regconfig, content)) STORED',
            'chinese');
        EXECUTE 'CREATE INDEX idx_segment_content_ts ON kb_segment USING gin (content_ts)';
        RAISE NOTICE '关键词检索使用 chinese(zhparser) 分词 + GIN tsvector';
    ELSE
        RAISE NOTICE '关键词检索退化为 pg_trgm 子串匹配';
    END IF;
END $$;

CREATE INDEX idx_segment_content_trgm ON kb_segment USING gin (content gin_trgm_ops);

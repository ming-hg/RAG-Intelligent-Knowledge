-- 异步任务队列：用 PG 表 + FOR UPDATE SKIP LOCKED 实现，不引入 MQ
CREATE TABLE kb_task (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_type    text        NOT NULL,
    kb_id        bigint      NOT NULL,
    doc_id       bigint      REFERENCES kb_document (id) ON DELETE CASCADE,
    payload      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status       text        NOT NULL DEFAULT 'QUEUED',
    attempt      integer     NOT NULL DEFAULT 0,
    max_attempts integer     NOT NULL DEFAULT 3,
    next_retry_at timestamptz NOT NULL DEFAULT now(),
    lease_owner  text,
    lease_until  timestamptz,
    error_type   text,
    error_msg    text,
    started_at   timestamptz,
    finished_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Worker 领取队列任务
CREATE INDEX idx_task_pickup ON kb_task (status, next_retry_at) WHERE status = 'QUEUED';
CREATE INDEX idx_task_doc ON kb_task (doc_id, task_type);

-- 租约过期回收（进程崩溃后任务不会永久卡在 RUNNING）
CREATE INDEX idx_task_lease ON kb_task (lease_until) WHERE status = 'RUNNING';

CREATE TABLE chat_session (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title        text        NOT NULL DEFAULT '新会话',
    kb_ids       bigint[]    NOT NULL DEFAULT '{}',
    model_config jsonb       NOT NULL DEFAULT '{}'::jsonb,
    pinned       boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    deleted_at   timestamptz
);

CREATE TABLE chat_message (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       uuid         NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    role             text         NOT NULL,
    content          text         NOT NULL DEFAULT '',
    query_final      text,
    status           text         NOT NULL DEFAULT 'STREAMING',
    sources          jsonb        NOT NULL DEFAULT '[]'::jsonb,
    retrieval_meta   jsonb        NOT NULL DEFAULT '{}'::jsonb,
    prompt_tokens    integer,
    completion_tokens integer,
    model            text,
    rating           smallint,
    feedback_text    text,
    annotated_answer text,
    parent_msg_id    uuid         REFERENCES chat_message (id) ON DELETE SET NULL,
    trace_id         text,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    finished_at      timestamptz
);

CREATE INDEX idx_message_session ON chat_message (session_id, created_at);

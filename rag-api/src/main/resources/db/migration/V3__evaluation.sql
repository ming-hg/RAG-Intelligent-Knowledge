-- 评测集与跑分（M5e）。
-- 判据放在检索层：hit@k / MRR / 空召回率 / 拒答正确率这四件事，只取决于「召回了什么、装不装得下」，
-- 与模型答得好不好无关。把它们和答案质量分开，跑分才能在没有模型、没有 GPU 的机器上重复，
-- 也才敢拿来做调参的前后对比——混进模型随机性之后，同一套参数两次跑出不同分数，结论就废了。

-- 一个用例 = 一个问题 + 期望召回的那块证据 + 这个问题该不该答。
-- expect_marker 是判相关性的唯一依据：命中块正文里必须出现这段文字（忽略大小写与空白差异）。
-- 之所以用「一段必须出现的文字」而不是「某个 segment_id」：改分块尺寸、重算向量之后块的 id 会变，
-- 而评测要的正是「换参数之后还找不找得到」，锚在 id 上等于一调参就全废。
CREATE TABLE eval_case (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kb_id         bigint       NOT NULL REFERENCES knowledge_base (id) ON DELETE CASCADE,
    question      text         NOT NULL,
    expect_marker text,
    expect_doc    text,
    -- false = 库里没有答案，正确行为是拒答；这类用例是「幻觉抑制」那条硬边界的回归测试
    should_answer boolean      NOT NULL DEFAULT true,
    origin        text         NOT NULL DEFAULT 'manual',
    origin_msg_id uuid,
    note          text,
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

-- 跑分时只会读启用的用例，未标注的那批（from-feedback 生成）留在这里等人工补 marker
CREATE INDEX idx_eval_case_kb ON eval_case (kb_id, enabled);

CREATE TABLE eval_run (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    -- 本次生效的全部检索参数快照。两次跑分要能比较，前提是知道两次的参数差在哪；
    -- 参数只存这一份，不再另开 mode/top_k 列——多一处状态就多一处和实际跑分对不上的可能。
    settings    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    metrics     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    note        text
);

CREATE TABLE eval_result (
    run_id   bigint  NOT NULL REFERENCES eval_run (id) ON DELETE CASCADE,
    -- case_id 故意不建外键：删掉一条用例不该抹掉「当时的分数」。
    -- ON DELETE CASCADE 会让历史少一整列，RESTRICT 又让用例改主意都得先删跑分记录，两边都不对。
    -- 用例没了还能读这一行，靠的就是下面 question / expect_marker / reason 那三份那一刻的副本。
    case_id  bigint  NOT NULL,
    first_rank integer,
    hits     integer NOT NULL DEFAULT 0,
    -- 判「会不会拒答」走的是问答那条装配路径，不是简单看 hits 是否为空：
    -- 命中了但正文太短、预算装不下，一样会拒答。评测与线上必须是同一个判据。
    refused  boolean NOT NULL DEFAULT false,
    correct  boolean NOT NULL,
    took_ms  bigint  NOT NULL DEFAULT 0,
    -- 问题与判据在跑分那一刻的副本：用例后面被人改过，历史这一行也还能自解释
    question      text NOT NULL,
    expect_marker text,
    -- 错例的人话解释，判的那一刻就写下来：事后从 hits/refused 反推出来的原因会跟当时的判据脱节
    reason     text,
    detail    jsonb NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (run_id, case_id)
);

CREATE INDEX idx_eval_result_run ON eval_result (run_id, correct);

-- 首次初始化数据库时执行（仅对新创建的 database 生效）
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 中文全文检索：缺失时不阻断启动，由 /api/health 明确报告降级状态
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS zhparser;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'zhparser 扩展不可用，中文全文检索将降级：% ', SQLERRM;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'zhparser') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese') THEN
            CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
            ALTER TEXT SEARCH CONFIGURATION chinese
                ADD MAPPING FOR n,v,a,i,e,l,j,x WITH simple;
        END IF;
        RAISE NOTICE '中文 text search configuration [chinese] 就绪';
    ELSE
        RAISE NOTICE '未启用 zhparser，混合检索将退化为 pg_trgm 子串匹配';
    END IF;
END $$;

-- 自检（构建完成后手工验证用）：
--   SELECT * FROM get_tokens_as_table('chinese', '人工智能知识库问答系统重构方案');
--   SELECT to_tsvector('chinese', '知识库问答系统') @@ plainto_tsquery('chinese', '问答系统');

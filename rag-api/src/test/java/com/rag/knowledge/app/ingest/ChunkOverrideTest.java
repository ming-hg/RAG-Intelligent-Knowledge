package com.rag.knowledge.app.ingest;

import com.rag.knowledge.common.RagException;
import com.rag.knowledge.config.RagProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖列的三段逻辑：读进来（坏数据要退化）、逐键合并（别让一个参数把其余四个悄悄重置）、写回去（只留差异）。
 */
class ChunkOverrideTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final RagProperties.Chunk global = RagProperties.Chunk.defaults();

    @Test
    void nothingStoredReadsAsNoOverride() {
        for (String raw : new String[] { null, "", "  ", "{}" }) {
            assertThat(ChunkOverride.read(json, raw)).isEqualTo(ChunkOverride.EMPTY);
        }
    }

    @Test
    void corruptStoredJsonReadsAsNoOverrideInsteadOfThrowing() {
        assertThatCode(() -> ChunkOverride.read(json, "{这不是 json"))
                .as("手工改坏的 jsonb 不该让整篇文档入库失败")
                .doesNotThrowAnyException();
        assertThat(ChunkOverride.read(json, "{这不是 json")).isEqualTo(ChunkOverride.EMPTY);
    }

    @Test
    void onlyTheOverriddenKeysRoundTrip() {
        ChunkOverride override = new ChunkOverride(260, null, null, null, null);

        String raw = override.write(json);
        assertThat(raw).isEqualTo("{\"childChars\":260}");
        assertThat(ChunkOverride.read(json, raw)).isEqualTo(override);
    }

    @Test
    void anEmptyOverrideIsStoredAsNullRatherThanAnEmptyObject() {
        assertThat(ChunkOverride.EMPTY.write(json)).isNull();
    }

    @Test
    void mergingFallsBackPerKeyNotWholesale() {
        ChunkOverride override = new ChunkOverride(260, null, null, 900, false);

        ChineseSemanticSplitter.ChunkConfig config = override.merged(global);

        // childMinChars/overlapChars 覆盖里没写，必须继续用全局的，而不是掉成 0
        assertThat(config).isEqualTo(new ChineseSemanticSplitter.ChunkConfig(260, 120, 60, 900, false));
    }

    @Test
    void requestParamsWinOverTheStoredOverrideKeyByKey() {
        ChunkOverride requested = new ChunkOverride(null, 80, null, null, null);
        ChunkOverride stored = new ChunkOverride(260, 100, 30, null, null);

        assertThat(requested.orElse(stored)).isEqualTo(new ChunkOverride(260, 80, 30, null, null));
    }

    @Test
    void aMissingRequestLayerLeavesTheStoredOverrideAlone() {
        ChunkOverride stored = new ChunkOverride(260, null, null, null, null);

        assertThat(stored.orElse(null)).isEqualTo(stored);
        assertThat(stored.orElse(ChunkOverride.EMPTY)).isEqualTo(stored);
    }

    @Test
    void keysThatMatchGlobalAreDroppedBeforeStoring() {
        ChunkOverride requested = new ChunkOverride(400, 100, 60, 1200, true);

        ChunkOverride deltas = requested.deltasAgainst(global);

        assertThat(deltas).isEqualTo(new ChunkOverride(null, 100, null, null, null));
        assertThat(deltas.write(json)).isEqualTo("{\"childMinChars\":100}");
    }

    @Test
    void anOverrideEqualToGlobalEverywhereIsStoredAsNull() {
        ChunkOverride requested = new ChunkOverride(400, 120, 60, 1200, true);

        assertThat(requested.deltasAgainst(global)).isEqualTo(ChunkOverride.EMPTY);
    }

    @Test
    void impossibleCombinationsAreRejectedBeforeReachingTheSplitter() {
        assertRejected(new ChunkOverride(40, null, null, null, null));
        assertRejected(new ChunkOverride(5000, null, null, null, null));
        assertRejected(new ChunkOverride(400, 0, null, null, null));
        assertRejected(new ChunkOverride(400, 500, null, null, null));
        assertRejected(new ChunkOverride(400, 120, 400, null, null));
        assertRejected(new ChunkOverride(400, 120, null, 300, null));
    }

    @Test
    void anOverrideThatOnlyBreaksAfterMergingWithGlobalIsStillRejected() {
        // 覆盖里只写了 overlap=600，单看合法，配上全局 childChars=400 就不可能了
        assertRejected(new ChunkOverride(null, null, 600, null, null));
    }

    private void assertRejected(ChunkOverride override) {
        assertThatThrownBy(() -> override.merged(global))
                .isInstanceOfSatisfying(RagException.class, ex -> assertThat(ex.code()).isEqualTo(400));
    }
}

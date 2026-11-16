package com.rag.knowledge.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机的钉死测试：进度条和「重试」按钮都读这个字段，
 * 少一条边就会出现「已就绪但没有块」这类前端无法自愈的中间态。
 */
class DocStatusTest {

    private static final List<DocStatus> PIPELINE =
            List.of(DocStatus.PENDING, DocStatus.PARSING, DocStatus.SPLITTING, DocStatus.EMBEDDING,
                    DocStatus.INDEXING, DocStatus.READY);

    @Test
    void happyPathWalksThePipelineInOrder() {
        DocStatus previous = null;
        for (DocStatus next : PIPELINE) {
            assertThat(next.canFollowFrom(previous)).as("%s 应该能从 %s 进入", next, previous).isTrue();
            previous = next;
        }
    }

    @Test
    void skippingAStageIsRejected() {
        assertThat(DocStatus.EMBEDDING.canFollowFrom(DocStatus.PENDING)).isFalse();
        assertThat(DocStatus.READY.canFollowFrom(DocStatus.SPLITTING)).isFalse();
        assertThat(DocStatus.SPLITTING.canFollowFrom(DocStatus.EMBEDDING)).isFalse();
    }

    @Test
    void failureIsReachableFromEveryStateAndOnlyRestartsFromParsing() {
        for (DocStatus previous : DocStatus.values()) {
            assertThat(DocStatus.FAILED.canFollowFrom(previous)).as("从 %s 失败", previous).isTrue();
        }
        assertThat(DocStatus.PARSING.canFollowFrom(DocStatus.FAILED)).isTrue();
        assertThat(DocStatus.PARSING.canFollowFrom(DocStatus.READY)).isTrue();
        assertThat(DocStatus.INDEXING.canFollowFrom(DocStatus.FAILED)).isFalse();
    }

    @Test
    void onlyTerminalOrPendingStatesCanStartANewRun() {
        assertThat(Arrays.stream(DocStatus.values()).filter(DocStatus::isRerunnable).toList())
                .containsExactlyInAnyOrder(DocStatus.PENDING, DocStatus.FAILED, DocStatus.READY);
    }

    @Test
    void runningStatesCannotBeRequeued() {
        assertThat(DocStatus.PARSING.isRerunnable()).isFalse();
        assertThat(DocStatus.EMBEDDING.isRerunnable()).isFalse();
    }
}

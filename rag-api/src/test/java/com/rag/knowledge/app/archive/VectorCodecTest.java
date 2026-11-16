package com.rag.knowledge.app.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量编解码是归档包里唯一「看不见」的一段：写坏了不会报错，只会在导入后表现为相似度整体偏低，
 * 而那已经被无数种别的原因解释过了。所以这里钉的是字节级往返，而不是「能编能解」。
 */
class VectorCodecTest {

    @Test
    void roundTripKeepsEveryComponent() {
        float[] vector = {0f, 1f, -1f, 0.5f, -0.125f, Float.MAX_VALUE, Float.MIN_VALUE, 3.14159f};

        float[] decoded = VectorCodec.decode(VectorCodec.encode(vector));

        assertThat(decoded).isEqualTo(vector);
    }

    @Test
    void nullStaysNullBecauseNoVectorIsNotTheSameAsAZeroVector() {
        assertThat(VectorCodec.encode(null)).isNull();
        assertThat(VectorCodec.decode(null)).isNull();
        assertThat(VectorCodec.decode("")).isNull();
        assertThat(VectorCodec.decode("   ")).isNull();
    }

    /** 维度是导入校验的依据，所以长度必须精确等于 float32 的整数倍，多一个字节都得报出来。 */
    @Test
    void aTruncatedVectorFailsInsteadOfSilentlyLosingTheLastDimension() {
        String encoded = VectorCodec.encode(new float[] {1f, 2f, 3f});

        assertThatThrownBy(() -> VectorCodec.decode(encoded.substring(0, encoded.length() - 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 float32 的整数倍");
    }

    @Test
    void encodingIsCompactEnoughToBeWorthTheCodec() {
        float[] vector = new float[768];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = i / 1000f;
        }

        String encoded = VectorCodec.encode(vector);

        // 同样的向量写成 JSON 十进制文本要 10KB 以上，base64 固定 4 字节/维
        assertThat(encoded).hasSize(768 * Integer.BYTES * 4 / 3);
        assertThat(VectorCodec.decode(encoded)).isEqualTo(vector);
    }
}

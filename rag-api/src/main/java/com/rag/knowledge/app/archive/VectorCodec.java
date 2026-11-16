package com.rag.knowledge.app.archive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * 向量在归档包里的编码：小端 float32 再 base64。
 * <p>
 * 不用 JSON 数组是因为体积：768 维写成十进制文本约 15 KB，base64 只要 4 KB，一个库导出来差出一个数量级。
 * 也不担心精度——pgvector 的向量本来就是 float4，库里读回来的文本字面量已经是这个精度。
 */
final class VectorCodec {

    private VectorCodec() {
    }

    static String encode(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] raw = Base64.getDecoder().decode(encoded);
        if (raw.length % Integer.BYTES != 0) {
            throw new IllegalArgumentException("向量长度不是 float32 的整数倍：" + raw.length + " 字节");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[raw.length / Integer.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}

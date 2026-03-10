package com.memo.docrank.core.embedding;

import java.util.List;
import java.util.Random;

/**
 * 随机向量 Embedding（仅用于演示 / 测试）。
 *
 * <p>不需要 ONNX 模型文件，生成随机归一化向量，不具备语义检索能力。
 * 通过 {@code docrank.embedding.type: random} 启用。
 */
public class RandomEmbeddingProvider implements EmbeddingProvider {

    private final int dim;
    private final Random rng = new Random();

    public RandomEmbeddingProvider(int dim) {
        this.dim = dim;
    }

    @Override
    public List<float[]> encode(List<String> texts) {
        return texts.stream().map(t -> randomUnit()).toList();
    }

    @Override
    public int dimension() {
        return dim;
    }

    private float[] randomUnit() {
        float[] v = new float[dim];
        double norm = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = (float) rng.nextGaussian();
            norm += v[i] * v[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < dim; i++) v[i] /= (float) norm;
        return v;
    }
}

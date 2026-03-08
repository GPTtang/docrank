package com.memo.docrank.core.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 ONNX Runtime + DJL 的本地 BGE-M3 Embedding
 * 支持中文、日文、英文，完全离线运行
 *
 * 模型目录结构：
 *   {modelPath}/
 *     model.onnx
 *     tokenizer.json
 *     tokenizer_config.json
 */
@Slf4j
public class OnnxEmbeddingProvider implements EmbeddingProvider {

    private final HuggingFaceTokenizer tokenizer;
    private final ai.djl.inference.Predictor<NDList, NDList> predictor;
    private final NDManager manager;
    private final int dim;
    private final int batchSize;

    public OnnxEmbeddingProvider(String modelPath, int batchSize) {
        try {
            Path path = Path.of(modelPath);
            this.tokenizer = HuggingFaceTokenizer.newInstance(path.resolve("tokenizer.json"));
            this.manager   = NDManager.newBaseManager();
            this.batchSize = batchSize;

            ai.djl.Model model = ai.djl.Model.newInstance("bge-m3", "OnnxRuntime");
            model.load(path, "model");

            ai.djl.translate.Translator<NDList, NDList> translator =
                    new ai.djl.translate.NoopTranslator();
            this.predictor = model.newPredictor(translator);

            // BGE-M3 输出维度为 1024
            this.dim = 1024;
            log.info("OnnxEmbeddingProvider 加载完成，模型路径: {}, 维度: {}", modelPath, dim);
        } catch (Exception e) {
            throw new IllegalStateException("BGE-M3 模型加载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<float[]> encode(List<String> texts) {
        List<float[]> results = new ArrayList<>(texts.size());
        // 按批次处理
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            results.addAll(encodeBatch(batch));
        }
        return results;
    }

    @Override
    public int dimension() {
        return dim;
    }

    private List<float[]> encodeBatch(List<String> texts) {
        try {
            Encoding[] encodings = tokenizer.batchEncode(texts);

            long batchSz = encodings.length;
            long seqLen  = encodings[0].getIds().length;

            long[] inputIds      = new long[(int)(batchSz * seqLen)];
            long[] attentionMask = new long[(int)(batchSz * seqLen)];
            long[] tokenTypeIds  = new long[(int)(batchSz * seqLen)];

            for (int b = 0; b < batchSz; b++) {
                long[] ids  = encodings[b].getIds();
                long[] mask = encodings[b].getAttentionMask();
                long[] type = encodings[b].getTypeIds();
                System.arraycopy(ids,  0, inputIds,      (int)(b * seqLen), ids.length);
                System.arraycopy(mask, 0, attentionMask, (int)(b * seqLen), mask.length);
                System.arraycopy(type, 0, tokenTypeIds,  (int)(b * seqLen), type.length);
            }

            Shape shape = new Shape(batchSz, seqLen);
            NDArray idsTensor   = manager.create(inputIds, shape);
            NDArray maskTensor  = manager.create(attentionMask, shape);
            NDArray typeTensor  = manager.create(tokenTypeIds, shape);

            idsTensor.setName("input_ids");
            maskTensor.setName("attention_mask");
            typeTensor.setName("token_type_ids");

            NDList output = predictor.predict(new NDList(idsTensor, maskTensor, typeTensor));
            // 取 [CLS] token 表示（index 0）
            NDArray embeddings = output.get(0).get(":, 0, :");

            List<float[]> result = new ArrayList<>();
            for (int b = 0; b < batchSz; b++) {
                float[] vec = embeddings.get(b).toFloatArray();
                result.add(normalize(vec));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 推理失败: " + e.getMessage(), e);
        }
    }

    private float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        float[] normed = new float[vec.length];
        for (int i = 0; i < vec.length; i++) normed[i] = (float)(vec[i] / norm);
        return normed;
    }
}

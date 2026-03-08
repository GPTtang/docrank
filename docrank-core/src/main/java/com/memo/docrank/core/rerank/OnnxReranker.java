package com.memo.docrank.core.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import com.memo.docrank.core.model.FusedCandidate;
import com.memo.docrank.core.model.SearchResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 ONNX Runtime 的本地 bge-reranker-v2-m3 重排序
 * 支持中文、日文、英文，完全离线运行
 *
 * 模型目录结构：
 *   {modelPath}/
 *     model.onnx
 *     tokenizer.json
 */
@Slf4j
public class OnnxReranker implements Reranker {

    private final HuggingFaceTokenizer tokenizer;
    private final ai.djl.inference.Predictor<NDList, NDList> predictor;
    private final NDManager manager;

    public OnnxReranker(String modelPath) {
        try {
            Path path = Path.of(modelPath);
            this.tokenizer = HuggingFaceTokenizer.newInstance(path.resolve("tokenizer.json"));
            this.manager   = NDManager.newBaseManager();

            ai.djl.Model model = ai.djl.Model.newInstance("bge-reranker-v2-m3", "OnnxRuntime");
            model.load(path, "model");

            this.predictor = model.newPredictor(new ai.djl.translate.NoopTranslator());
            log.info("OnnxReranker 加载完成，模型路径: {}", modelPath);
        } catch (Exception e) {
            throw new IllegalStateException("Reranker 模型加载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SearchResult> rerank(String query, List<FusedCandidate> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();

        List<ScoredCandidate> scored = new ArrayList<>();
        for (FusedCandidate candidate : candidates) {
            double score = scoreOnePair(query, candidate.getChunk().getChunkText());
            scored.add(new ScoredCandidate(candidate, score));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::rerankScore).reversed())
                .limit(topN)
                .map(sc -> SearchResult.builder()
                        .chunk(sc.candidate().getChunk())
                        .score(sc.rerankScore() * 0.6 + sc.candidate().getFusedScore() * 0.4)
                        .sparseScore(sc.candidate().getSparseScore())
                        .vectorScore(sc.candidate().getVectorScore())
                        .rerankScore(sc.rerankScore())
                        .build())
                .toList();
    }

    private double scoreOnePair(String query, String passage) {
        try {
            Encoding encoding = tokenizer.encode(query, passage);
            long seqLen = encoding.getIds().length;

            NDArray inputIds      = manager.create(encoding.getIds(), new Shape(1, seqLen));
            NDArray attentionMask = manager.create(encoding.getAttentionMask(), new Shape(1, seqLen));
            NDArray tokenTypeIds  = manager.create(encoding.getTypeIds(), new Shape(1, seqLen));

            inputIds.setName("input_ids");
            attentionMask.setName("attention_mask");
            tokenTypeIds.setName("token_type_ids");

            NDList output = predictor.predict(new NDList(inputIds, attentionMask, tokenTypeIds));
            // logits → sigmoid → relevance score
            float logit = output.get(0).toFloatArray()[0];
            return sigmoid(logit);
        } catch (Exception e) {
            log.warn("Reranker 推理失败，降级使用融合分数: {}", e.getMessage());
            return 0.0;
        }
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private record ScoredCandidate(FusedCandidate candidate, double rerankScore) {}
}

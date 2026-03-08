package com.memo.docrank.eval;

import com.memo.docrank.eval.model.EvalDataset;
import com.memo.docrank.eval.model.EvalQuery;
import com.memo.docrank.eval.model.EvalReport;
import com.memo.docrank.eval.model.EvalResult;
import com.memo.docrank.memory.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索评估服务。
 *
 * <p>使用示例：
 * <pre>{@code
 * EvalDataset dataset = EvalDataset.fromJsonFile(Path.of("bench.json"));
 * EvaluationService eval = new EvaluationService(knowledgeBaseService);
 * EvalReport report = eval.evaluate(dataset);
 * report.print();
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
public class EvaluationService {

    /** 默认评估截断深度 */
    private static final int DEFAULT_RETRIEVE_K = 20;

    private final KnowledgeBaseService kb;

    // ──────────────────────────────────────── public API ─────────────────

    /**
     * 对整个数据集运行评估，返回聚合报告。
     */
    public EvalReport evaluate(EvalDataset dataset) {
        return evaluate(dataset, DEFAULT_RETRIEVE_K);
    }

    /**
     * 对整个数据集运行评估，指定检索深度。
     *
     * @param dataset   评估数据集（包含查询 + ground-truth）
     * @param retrieveK 检索时最多返回的结果数（≥ 20 以保证指标准确性）
     */
    public EvalReport evaluate(EvalDataset dataset, int retrieveK) {
        if (dataset == null || dataset.getQueries() == null || dataset.getQueries().isEmpty()) {
            throw new IllegalArgumentException("评估数据集不能为空");
        }
        int k = Math.max(retrieveK, 20);

        List<EvalResult> results = new ArrayList<>(dataset.size());

        for (EvalQuery query : dataset.getQueries()) {
            EvalResult result = evaluateSingle(query, k);
            results.add(result);
            log.debug("eval query='{}' ndcg@10={:.4f} recall@10={:.4f}",
                    query.getQuery(), result.getNdcgAt10(), result.getRecallAt10());
        }

        return aggregate(dataset.getName(), results);
    }

    // ──────────────────────────────────────── single query ───────────────

    private EvalResult evaluateSingle(EvalQuery query, int retrieveK) {
        // 执行检索（带 scope 过滤）
        List<String> docIds = retrieve(query, retrieveK);

        return EvalResult.builder()
                .queryId(query.getId())
                .queryText(query.getQuery())
                .retrievedDocIds(docIds)
                .totalRelevant(query.relevantCount())
                // NDCG
                .ndcgAt5 (EvalMetrics.ndcg(docIds, query,  5))
                .ndcgAt10(EvalMetrics.ndcg(docIds, query, 10))
                .ndcgAt20(EvalMetrics.ndcg(docIds, query, 20))
                // Recall
                .recallAt5 (EvalMetrics.recall(docIds, query,  5))
                .recallAt10(EvalMetrics.recall(docIds, query, 10))
                .recallAt20(EvalMetrics.recall(docIds, query, 20))
                // Precision
                .precisionAt5 (EvalMetrics.precision(docIds, query,  5))
                .precisionAt10(EvalMetrics.precision(docIds, query, 10))
                // RR / AP
                .reciprocalRank  (EvalMetrics.reciprocalRank(docIds, query))
                .averagePrecision(EvalMetrics.averagePrecision(docIds, query, 10))
                .build();
    }

    private List<String> retrieve(EvalQuery query, int k) {
        try {
            return kb.search(query.getQuery(), k, Map.of()).stream()
                    .map(r -> r.getChunk().getDocId())
                    .toList();
        } catch (Exception e) {
            log.error("检索失败 query='{}': {}", query.getQuery(), e.getMessage());
            return List.of();
        }
    }

    // ──────────────────────────────────────── aggregation ────────────────

    private EvalReport aggregate(String datasetName, List<EvalResult> results) {
        return EvalReport.builder()
                .datasetName(datasetName != null ? datasetName : "unknown")
                .totalQueries(results.size())
                .ndcgAt5 (mean(results, r -> r.getNdcgAt5()))
                .ndcgAt10(mean(results, r -> r.getNdcgAt10()))
                .ndcgAt20(mean(results, r -> r.getNdcgAt20()))
                .recallAt5 (mean(results, r -> r.getRecallAt5()))
                .recallAt10(mean(results, r -> r.getRecallAt10()))
                .recallAt20(mean(results, r -> r.getRecallAt20()))
                .precisionAt5 (mean(results, r -> r.getPrecisionAt5()))
                .precisionAt10(mean(results, r -> r.getPrecisionAt10()))
                .mrr   (mean(results, r -> r.getReciprocalRank()))
                .mapAt10(mean(results, r -> r.getAveragePrecision()))
                .perQueryResults(results)
                .build();
    }

    private double mean(List<EvalResult> results,
                        java.util.function.ToDoubleFunction<EvalResult> extractor) {
        return results.stream().mapToDouble(extractor).average().orElse(0.0);
    }
}

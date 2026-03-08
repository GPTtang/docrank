package com.memo.docrank.eval.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Locale;

/**
 * 整个数据集的聚合评估报告。
 */
@Data
@Builder
public class EvalReport {

    private String datasetName;
    private int    totalQueries;

    // ──── 平均指标 ────────────────────────────────────────────────

    private double ndcgAt5;
    private double ndcgAt10;
    private double ndcgAt20;

    private double recallAt5;
    private double recallAt10;
    private double recallAt20;

    private double precisionAt5;
    private double precisionAt10;

    /** MRR — Mean Reciprocal Rank */
    private double mrr;

    /** MAP@10 — Mean Average Precision */
    private double mapAt10;

    /** 每条查询的详细结果 */
    private List<EvalResult> perQueryResults;

    // ──── 输出工具 ────────────────────────────────────────────────

    /**
     * 打印格式化报告到 stdout。
     */
    public void print() {
        System.out.println(toText());
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        String sep = "─".repeat(56);
        sb.append("\n").append(sep).append("\n");
        sb.append(String.format("  DocRank Evaluation Report — %s%n", datasetName));
        sb.append(String.format("  Queries: %d%n", totalQueries));
        sb.append(sep).append("\n");
        sb.append(String.format("  %-20s %8s %8s %8s%n", "Metric", "@5", "@10", "@20"));
        sb.append("  " + "─".repeat(53) + "\n");
        sb.append(String.format("  %-20s %8.4f %8.4f %8.4f%n", "NDCG",
                ndcgAt5, ndcgAt10, ndcgAt20));
        sb.append(String.format("  %-20s %8.4f %8.4f %8.4f%n", "Recall",
                recallAt5, recallAt10, recallAt20));
        sb.append(String.format("  %-20s %8.4f %8.4f %8s%n", "Precision",
                precisionAt5, precisionAt10, "—"));
        sb.append("  " + "─".repeat(53) + "\n");
        sb.append(String.format("  %-20s %8.4f%n", "MRR",    mrr));
        sb.append(String.format("  %-20s %8.4f%n", "MAP@10", mapAt10));
        sb.append(sep).append("\n");
        return sb.toString();
    }

    /**
     * CSV 格式（适合写入文件）
     */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("dataset,queries,ndcg@5,ndcg@10,ndcg@20,recall@5,recall@10,recall@20,p@5,p@10,mrr,map@10\n");
        sb.append(String.format(Locale.ROOT,
                "%s,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                datasetName, totalQueries,
                ndcgAt5, ndcgAt10, ndcgAt20,
                recallAt5, recallAt10, recallAt20,
                precisionAt5, precisionAt10,
                mrr, mapAt10));
        return sb.toString();
    }
}

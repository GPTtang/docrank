package com.memo.docrank.eval.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 单条评估查询。
 *
 * <p>JSON 格式示例：
 * <pre>{@code
 * {
 *   "id": "q1",
 *   "query": "Spring Boot 自动配置原理",
 *   "relevant": {
 *     "doc-abc": 3,
 *     "doc-xyz": 1
 *   }
 * }
 * }</pre>
 *
 * <p>relevance 评级（graded relevance）：
 * <ul>
 *   <li>3 — 完全相关（perfect）
 *   <li>2 — 高度相关（excellent）
 *   <li>1 — 部分相关（fair）
 *   <li>0 — 不相关（bad）
 * </ul>
 * 二元相关性（binary）：相关文档统一使用 1，其余省略。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalQuery {

    /** 查询 ID，用于报告溯源 */
    @JsonProperty("id")
    private String id;

    /** 自然语言查询文本 */
    @JsonProperty("query")
    private String query;

    /**
     * 相关文档 Map：doc_id → relevance grade (0‥3)
     * 不在 map 中的文档默认 grade = 0
     */
    @JsonProperty("relevant")
    private Map<String, Integer> relevant;

    /** 返回该查询的相关文档数（grade > 0） */
    public int relevantCount() {
        if (relevant == null) return 0;
        return (int) relevant.values().stream().filter(g -> g > 0).count();
    }

    /** 获取某文档的 relevance grade，不存在则 0 */
    public int gradeOf(String docId) {
        if (relevant == null || docId == null) return 0;
        return relevant.getOrDefault(docId, 0);
    }
}

package com.memo.docrank.eval.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 评估数据集，包含一组查询及其 ground-truth 相关性标注。
 *
 * <p>JSON 格式：
 * <pre>{@code
 * {
 *   "name": "docrank-bench-v1",
 *   "description": "中文技术文档检索基准",
 *   "queries": [
 *     { "id": "q1", "query": "Spring Boot 配置", "relevant": { "doc-1": 3, "doc-2": 1 } },
 *     { "id": "q2", "query": "Docker 部署",       "relevant": { "doc-3": 2 } }
 *   ]
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalDataset {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("queries")
    private List<EvalQuery> queries;

    // ----------------------------------------------------------------- factory

    public static EvalDataset fromJsonFile(Path path) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(path), EvalDataset.class);
    }

    public static EvalDataset fromJsonString(String json) throws IOException {
        return MAPPER.readValue(json, EvalDataset.class);
    }

    public static EvalDataset fromInputStream(InputStream is) throws IOException {
        return MAPPER.readValue(is, EvalDataset.class);
    }

    /**
     * 快速构建小型数据集（适合单元测试）
     */
    public static EvalDataset of(String name, List<EvalQuery> queries) {
        return EvalDataset.builder().name(name).queries(queries).build();
    }

    /**
     * 构建单条查询的最小数据集
     */
    public static EvalDataset single(String query, Map<String, Integer> relevant) {
        EvalQuery eq = EvalQuery.builder()
                .id("q1").query(query).relevant(relevant).build();
        return of("single", List.of(eq));
    }

    public int size() { return queries == null ? 0 : queries.size(); }
}

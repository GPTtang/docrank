package com.memo.docrank.core.rerank;

import com.memo.docrank.core.model.FusedCandidate;
import com.memo.docrank.core.model.SearchResult;

import java.util.List;

public interface Reranker {

    List<SearchResult> rerank(String query, List<FusedCandidate> candidates, int topN);
}

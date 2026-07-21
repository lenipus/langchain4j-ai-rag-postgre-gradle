package com.example.chat.config;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 짧은 청크(예: PDF 페이지가 캡션 한 줄뿐인 경우)를 검색 결과에서 걸러내는 후필터.
 *
 * <p>짧은 청크는 벡터가 좁고 뾰족해서 키워드만 겹치면 코사인 유사도가 스퓨리어스하게
 * 높게 나오는 경우가 있다(실측 0.7 이상). 그렇다고 색인 단계에서 미리 제외하면 그
 * 청크가 실제로 정답인 질의에서도 영영 검색되지 않으므로, 이 클래스는 delegate가
 * top-k보다 넉넉히(overfetch) 가져온 결과에서 길이 미달 항목만 제거하고 최종
 * top-k로 자른다.</p>
 */
@Slf4j
public class EgovLengthFilteringContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final int minChunkLength;
    private final int finalTopK;

    public EgovLengthFilteringContentRetriever(ContentRetriever delegate, int minChunkLength, int finalTopK) {
        this.delegate = delegate;
        this.minChunkLength = minChunkLength;
        this.finalTopK = finalTopK;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> fetched = delegate.retrieve(query);
        List<Content> filtered = new ArrayList<>(Math.min(fetched.size(), finalTopK));
        int skipped = 0;
        for (Content content : fetched) {
            String text = content.textSegment().text();
            if (text == null || text.trim().length() < minChunkLength) {
                skipped++;
                continue;
            }
            filtered.add(content);
            if (filtered.size() == finalTopK) {
                break;
            }
        }
        if (skipped > 0) {
            log.debug("길이 필터 - 오버페치 {}개 중 {}개를 최소 길이({}자) 미만으로 제외, 최종 {}개 반환",
                    fetched.size(), skipped, minChunkLength, filtered.size());
        }
        return filtered;
    }
}

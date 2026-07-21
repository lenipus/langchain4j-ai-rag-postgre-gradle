package com.example.chat.config;

import com.example.chat.entity.RagRetrievalLogEntity;
import com.example.chat.repository.RagRetrievalLogRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link EgovLoggingContentRetriever}는 delegate의 검색 결과를 그대로 통과시키며
 * (순수 pass-through) 로그와 감사 테이블({@code rag_retrieval_logs}) 양쪽에 남긴다.
 *
 * <p>이 감사 테이블은 {@code chat_memory}와 달리 LLM에 재전송되지 않으므로, 세션이
 * 길어져도 컨텍스트 윈도우와 무관하게 "이 질문 때 뭐가 검색됐는지"를 나중에 조회할
 * 수 있게 해준다. 생성자의 sessionId/turnId는 이 인스턴스가 담당하는 요청/질의(턴)
 * 하나의 키로, 저장되는 모든 행에 그대로 찍힌다. {@code query.metadata().chatMemoryId()}는
 * 쓰지 않는다 - langchain4j가 {@code @MemoryId} 파라미터 없는 서비스 메서드에는
 * 이 값을 무조건 "default"로 채우는 동작이 있어서다.</p>
 */
class EgovLoggingContentRetrieverTest {

    private final RagRetrievalLogRepository repository = mock(RagRetrievalLogRepository.class);

    @Test
    @DisplayName("검색 결과가 있으면 그대로 반환한다")
    void passesThroughNonEmptyResults() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("본부장 연봉 상한액이 얼마야??");
        List<Content> expected = List.of(Content.from(TextSegment.from("부 칙 제1조...")));
        when(delegate.retrieve(query)).thenReturn(expected);

        EgovLoggingContentRetriever retriever = new EgovLoggingContentRetriever(delegate, repository, "session-1", "turn-1");
        List<Content> result = retriever.retrieve(query);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 리스트를 그대로 반환하고 감사 로그도 안 남긴다")
    void passesThroughEmptyResults() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("겸직허가 규정 좀 알려줘");
        when(delegate.retrieve(query)).thenReturn(List.of());

        EgovLoggingContentRetriever retriever = new EgovLoggingContentRetriever(delegate, repository, "session-1", "turn-1");
        List<Content> result = retriever.retrieve(query);

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("검색 결과마다 세션ID·질의·파일명·점수·본문을 감사 테이블에 저장한다")
    void persistsEachResultToAuditLog() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        dev.langchain4j.data.document.Metadata segmentMetadata =
                dev.langchain4j.data.document.Metadata.from("file_name", "결재라인.txt");
        Content content = Content.from(TextSegment.from("복무 결재라인 안내...", segmentMetadata));
        Query query = Query.from("휴가 결재선이 어떻게 돼??");
        when(delegate.retrieve(query)).thenReturn(List.of(content));

        EgovLoggingContentRetriever retriever = new EgovLoggingContentRetriever(delegate, repository, "session-abc", "turn-1");
        retriever.retrieve(query);

        ArgumentCaptor<RagRetrievalLogEntity> captor = ArgumentCaptor.forClass(RagRetrievalLogEntity.class);
        verify(repository).save(captor.capture());
        RagRetrievalLogEntity saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo("session-abc");
        assertThat(saved.getTurnId()).isEqualTo("turn-1");
        assertThat(saved.getQueryText()).isEqualTo("휴가 결재선이 어떻게 돼??");
        assertThat(saved.getFileName()).isEqualTo("결재라인.txt");
        assertThat(saved.getChunkText()).isEqualTo("복무 결재라인 안내...");
    }

    @Test
    @DisplayName("감사 로그 저장이 실패해도 검색 결과 반환에는 영향이 없다")
    void ignoresAuditLogFailure() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("겸직허가 규정 좀 알려줘");
        List<Content> expected = List.of(Content.from(TextSegment.from("제5조...")));
        when(delegate.retrieve(query)).thenReturn(expected);
        when(repository.save(any())).thenThrow(new RuntimeException("DB 연결 끊김"));

        EgovLoggingContentRetriever retriever = new EgovLoggingContentRetriever(delegate, repository, "session-1", "turn-1");
        List<Content> result = retriever.retrieve(query);

        assertThat(result).isEqualTo(expected);
    }
}

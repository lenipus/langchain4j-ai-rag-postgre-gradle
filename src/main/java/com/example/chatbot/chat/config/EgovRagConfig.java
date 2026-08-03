package com.example.chatbot.chat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

/**
 * RAG 설정 클래스
 * ContentRetriever를 통해 벡터 저장소에서 관련 문서를 검색
 */
@Slf4j
@Configuration
public class EgovRagConfig {

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.similarity.threshold:0.20}")
    private double similarityThreshold;

    @Value("${pgvector.table-name:document_embeddings}")
    private String tableName;

    /** 이웃 청크 확장의 잘림 판단 기준({@code EgovNeighborChunkExpander} 참고). */
    @Value("${document.chunk-size}")
    private int chunkSize;

    /** 이웃 청크에서 실제로 덧붙일 최대 길이. 인덱싱 시 chunk-overlap과는 별개 값이다. */
    @Value("${rag.retrieval.neighbor-expansion-chars:400}")
    private int neighborExpansionChars;

    /** 검색 결과 중 이 길이(문자) 미만인 청크는 스퓨리어스 매칭으로 간주해 제외한다. */
    @Value("${rag.retrieval.min-chunk-length:50}")
    private int minChunkLength;

    /** 길이 필터링으로 줄어들 것을 감안해 top-k보다 이 배수만큼 더 가져온다. */
    @Value("${rag.retrieval.overfetch-multiplier:3}")
    private int overfetchMultiplier;

    @Value("${rag.retrieval.hybrid.weight.dense:1.0}")
    private double hybridDenseWeight;

    @Value("${rag.retrieval.hybrid.weight.lexical:1.0}")
    private double hybridLexicalWeight;

    @Value("${rag.retrieval.hybrid.top-k:#{null}}")
    private Integer hybridTopK;

    // lexical(pg_trgm word_similarity) 임계값. `%>` 연산자가 참조하는 GUC
    // pg_trgm.word_similarity_threshold(기본 0.6)는 한국어 긴 청크에서 너무 엄격하다.
    // 실제 문서(runtime README, 4000자 청크) 측정상 0.30 부근이 recall@3~0.8로 최적이라 기본값을
    // 0.30으로 둔다. 코퍼스 언어·문서 특성에 따라 프로퍼티로 조정한다.
    @Value("${rag.retrieval.hybrid.lexical.word-similarity-threshold:0.30}")
    private double hybridLexicalWordSimilarityThreshold;

    @Value("${rag.reranker.enabled:false}")
    private boolean rerankerEnabled;

    @Value("${rag.reranker.base-url:}")
    private String rerankerBaseUrl;

    @Value("${rag.reranker.timeout:10s}")
    private Duration rerankerTimeout;

    /**
     * 리랭커가 켜져 있으면, 검색 단계는 최종 top-k가 아니라 이 개수만큼 넉넉히 후보를
     * 남겨서 리랭커에게 넘긴다(순수 임베딩 유사도만으로 미리 잘라내면 진짜 정답이 이
     * 단계에서 걸러질 수 있음 - 리랭커를 쓰는 이유 자체가 이걸 보완하기 위해서다).
     * 리랭커가 이 후보들을 다시 정확히 채점해서 진짜 top-k({@code rag.top-k})만 추린다.
     */
    @Value("${rag.reranker.candidate-pool:20}")
    private int rerankerCandidatePool;

    /** 검색 단계(길이 필터/RRF 융합)가 최종적으로 남기는 후보 개수 - 리랭커 켜짐 여부에 따라 다르다. */
    private int candidateCount() {
        return rerankerEnabled ? rerankerCandidatePool : topK;
    }

    /**
     * ContentRetriever 빈 생성
     * EmbeddingStoreContentRetriever를 사용하여 벡터 검색 수행
     *
     * @param embeddingStore 벡터 저장소
     * @param embeddingModel 임베딩 모델
     * @param jdbcTemplate   이웃 청크 확장(id/index 기반 앞뒤 청크 조회)용
     * @return ContentRetriever
     */
    @Bean
    public ContentRetriever contentRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate) {

        int candidateCount = candidateCount();
        int overfetchResults = candidateCount * overfetchMultiplier;
        log.info("ContentRetriever 초기화 - topK: {}, 리랭커: {}, candidateCount: {}, minScore: {}, overfetch: {}, minChunkLength: {}",
                topK, rerankerEnabled, candidateCount, similarityThreshold, overfetchResults, minChunkLength);

        ContentRetriever embeddingRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(overfetchResults)
                .minScore(similarityThreshold)
                .build();

        // candidateCount(리랭커 꺼짐: topK 그대로, 켜짐: 더 넉넉한 후보 풀)보다 넉넉히 가져온 뒤,
        // 짧은 청크(스퓨리어스 매칭 위험)를 걸러내고 candidateCount로 자른다.
        ContentRetriever lengthFiltered = new EgovLengthFilteringContentRetriever(embeddingRetriever, minChunkLength, candidateCount);

        // candidateCount로 추려진 뒤에 이웃 청크를 붙인다 - overfetch 단계(candidateCount*overfetchMultiplier개)가
        // 아니라 이미 candidateCount로 줄어든 결과에만 앞/뒤 청크 조회 쿼리를 실행해 DB 왕복을 최소화한다.
        // 리랭커가 켜져 있으면 candidateCount가 topK보다 커서(기본 20 vs 5) 그만큼 조회가 늘어나지만,
        // 리랭커 자체가 가벼운 연산이라 감수할 만하다.
        return new EgovNeighborChunkExpander(lengthFiltered, jdbcTemplate, tableName, chunkSize, neighborExpansionChars);
    }

    /**
     * 하이브리드 ContentRetriever 빈 생성
     *
     * <p>{@code rag.retrieval.hybrid.enabled=true} 일 때만 등록한다. off(기본) 상태에서는
     * 빈이 만들어지지 않으므로 dense {@code contentRetriever} 빈만 존재하여 빈 모호성이
     * 발생하지 않는다. dense 검색({@link #contentRetriever})과 lexical 검색(pg_trgm)을
     * RRF로 융합한다.</p>
     *
     * @param denseContentRetriever dense 벡터 검색 빈
     * @param jdbcTemplate          lexical 검색용 JdbcTemplate(자동 구성)
     * @param transactionManager    lexical 임계값을 트랜잭션 스코프로 적용하기 위한 트랜잭션 매니저
     * @return 하이브리드 ContentRetriever
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.retrieval.hybrid", name = "enabled", havingValue = "true")
    public ContentRetriever hybridContentRetriever(
            @Qualifier("contentRetriever") ContentRetriever denseContentRetriever,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        int effectiveTopK = (hybridTopK != null) ? hybridTopK : candidateCount();
        log.info("HybridContentRetriever 초기화 - topK: {}, 리랭커: {}, weight(dense/lexical): {}/{}, lexical word_similarity 임계값: {}",
                effectiveTopK, rerankerEnabled, hybridDenseWeight, hybridLexicalWeight, hybridLexicalWordSimilarityThreshold);

        return new EgovHybridContentRetriever(
                denseContentRetriever, jdbcTemplate, transactionManager, tableName,
                hybridDenseWeight, hybridLexicalWeight, hybridLexicalWordSimilarityThreshold, effectiveTopK,
                minChunkLength, overfetchMultiplier);
    }

    /**
     * 리랭커(llama.cpp {@code --reranking} 서버) 호출용 {@link ScoringModel} 빈.
     * {@code rag.reranker.enabled=true}일 때만 등록된다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.reranker", name = "enabled", havingValue = "true")
    public ScoringModel rerankerScoringModel() {
        log.info("리랭커 ScoringModel 초기화 - baseUrl: {}, timeout: {}, candidatePool: {}",
                rerankerBaseUrl, rerankerTimeout, rerankerCandidatePool);
        return new EgovLlamaCppScoringModel(rerankerBaseUrl, rerankerTimeout);
    }

    /**
     * 검색 후보(candidateCount개)를 리랭커로 다시 채점해 최종 top-k만 추리는 {@link ContentAggregator} 빈.
     * 리랭커 호출 실패 시 폴백, {@link EgovKeywordBoostContentRetriever}가 강제 포함시킨 문서 보존은
     * {@link EgovResilientRerankingAggregator}가 담당한다. minScore는 일부러 안 건다 - bge-reranker
     * 점수는 0~1이 아니라 원시 로짓(logit)이라 절대 임계값을 잘못 잡으면 정답을 걸러낼 위험이 있다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.reranker", name = "enabled", havingValue = "true")
    public ContentAggregator contentAggregator(ScoringModel rerankerScoringModel) {
        ContentAggregator reRanking = ReRankingContentAggregator.builder()
                .scoringModel(rerankerScoringModel)
                .maxResults(topK)
                .build();
        return new EgovResilientRerankingAggregator(
                reRanking, new DefaultContentAggregator(), EgovKeywordBoostContentRetriever.protectedFileNames(), topK);
    }
}

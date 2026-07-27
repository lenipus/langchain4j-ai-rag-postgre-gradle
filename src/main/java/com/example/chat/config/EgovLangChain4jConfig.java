package com.example.chat.config;

import com.example.chat.service.EmbeddingModelGateway;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * LangChain4j 설정 클래스
 * - 채팅 모델 연결/생성은 {@link com.example.chat.service.ChatModelGateway}, 임베딩 모델
 *   연결/생성은 {@link EmbeddingModelGateway}가 전담한다. 이 클래스는 그 결과물을 스프링
 *   빈으로 등록하는 것과, 임베딩 모델과 무관한 PGVector 저장소(테이블/차원/DataSource)
 *   설정만 담당한다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EgovLangChain4jConfig {

    private final DataSource dataSource;

    // ===== PGVector 설정 =====

    @Value("${pgvector.table-name:document_embeddings}")
    private String pgvectorTableName;

    @Value("${pgvector.dimension:768}")
    private Integer pgvectorDimension;

    @Value("${pgvector.create-table:true}")
    private Boolean createTable;

    /**
     * 임베딩 모델 빈. 실제 생성 로직은 {@link EmbeddingModelGateway}가 담당하고, 여기서는
     * 그 결과를 스프링 싱글톤 빈으로만 등록한다({@code EgovRagConfig}, {@code EgovVectorStoreWriter}가
     * 이 빈 타입으로 주입받아 씀).
     */
    @Bean
    public EmbeddingModel embeddingModel(EmbeddingModelGateway embeddingModelGateway) {
        return embeddingModelGateway.getEmbeddingModel();
    }

    /**
     * PGVector 임베딩 저장소 빈
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("Initializing PGVector Embedding Store (Spring DataSource 재사용)...");
        log.info("Table name: {}", pgvectorTableName);
        log.info("Dimension: {}", pgvectorDimension);
        log.info("Create table: {}", createTable);

        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(pgvectorTableName)
                .dimension(pgvectorDimension)
                .createTable(createTable)
                .build();
    }
}

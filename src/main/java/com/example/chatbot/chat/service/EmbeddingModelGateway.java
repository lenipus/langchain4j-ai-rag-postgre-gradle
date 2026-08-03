package com.example.chatbot.chat.service;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 임베딩 모델(LLM)과의 연결을 전담하는 게이트웨이.
 *
 * <p>연결 설정(base-url/api-key/api-type/model-name)과 실제 {@link EmbeddingModel} 인스턴스
 * 생성을 이 클래스가 담당한다. 채팅 모델과 달리 임베딩은 요청마다 다른 모델을 선택하는
 * 기능이 없어(항상 {@code langchain4j.embedding.ollama.model-name} 하나만 씀)
 * {@link ChatModelGateway}보다 훨씬 단순하다.</p>
 */
@Slf4j
@Component
public class EmbeddingModelGateway {

    @Value("${langchain4j.embedding.ollama.base-url:}")
    private String embeddingModelBaseUrl;

    /** 인증이 필요할 때만 설정. api-type과는 별개 값 (있다고 무조건 openai는 아님) */
    @Value("${langchain4j.embedding.ollama.api-key:}")
    private String embeddingModelApiKey;

    /** ollama(네이티브, 기본값) | openai(OpenAI 호환) */
    @Value("${langchain4j.embedding.ollama.api-type:ollama}")
    private String embeddingModelApiType;

    @Value("${langchain4j.embedding.ollama.model-name:embeddinggemma:300m}")
    private String embeddingModelName;

    /**
     * OpenAI 호환 빌더에 넘길 API 키. 인증이 필요 없는 서버라 비어있으면
     * OpenAI 클라이언트가 요구하는 자리 채움 값("not-needed")으로 대체한다.
     */
    private static String resolveApiKey(String apiKey) {
        return (apiKey == null || apiKey.isBlank()) ? "not-needed" : apiKey;
    }

    /**
     * 임베딩 모델을 생성한다.
     * embedding-model.api-type이 openai면 OpenAI 호환 서버, 아니면(기본값 ollama) 로컬/원격 Ollama 네이티브를 사용한다.
     */
    public EmbeddingModel getEmbeddingModel() {
        boolean useOpenAi = "openai".equalsIgnoreCase(embeddingModelApiType);
        log.info("Initializing Embedding Model... (apiType={})", embeddingModelApiType);
        log.info("Base URL: {}", embeddingModelBaseUrl);
        log.info("Embedding model name: {}", embeddingModelName);

        if (useOpenAi) {
            return OpenAiEmbeddingModel.builder()
                    .baseUrl(embeddingModelBaseUrl)
                    .apiKey(resolveApiKey(embeddingModelApiKey))
                    .modelName(embeddingModelName)
                    .build();
        }
        return OllamaEmbeddingModel.builder()
                .baseUrl(embeddingModelBaseUrl)
                .modelName(embeddingModelName)
                .build();
    }
}

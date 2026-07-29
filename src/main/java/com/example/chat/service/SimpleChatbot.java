package com.example.chat.service;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 일반 챗봇 인터페이스 (RAG 없음)
 * LangChain4j AiServices를 통해 동적 프록시로 구현됨
 * - RAG 검색 없이 LLM의 일반 지식으로 응답
 * - ChatMemory가 자동으로 대화 히스토리 관리
 * - langchain4j-reactor를 통해 Flux<String> 네이티브 지원
 */
public interface SimpleChatbot {

    // 시스템 메시지는 @SystemMessage 정적 어노테이션 대신 ChatbotFactory가
    // AiServices.systemMessageProvider(...)로 매 요청마다 동적으로 넣는다 - 오늘 날짜/요일을
    // 붙여서 상대 날짜 질문도 계산할 수 있게 하기 위함(RagChatbot과 동일한 이유).
    String SIMPLE_SYSTEM_PROMPT = """
            당신은 도움이 되는 AI 어시스턴트입니다.
            사용자의 질문에 대해 친절하고 정확한 답변을 제공하세요.
            답변은 한국어로, 격식 있고 공식적인 문어체로 작성하세요.
            사용자의 질문에 구어체, 줄임말, 속어(예: "땡겨쓰다")가 있어도 답변에서는 그 표현을
            그대로 따라 쓰지 말고 표준어와 격식체(예: "미리 사용하다", "선지급받다")로 바꾸어 표현하세요.
            """;

    /**
     * 일반 스트리밍 채팅 응답 생성
     * ChatMemory가 자동으로 대화 히스토리를 관리
     * langchain4j-reactor가 Flux 변환을 자동 처리
     *
     * @param query 사용자 질문
     * @return Flux<String> (리액티브 스트리밍 응답)
     */
    Flux<String> streamChat(@UserMessage String query);

    /**
     * 이미지가 첨부된 질문에 대한 스트리밍 채팅 응답 생성 (비전 지원 모델 전용)
     *
     * @param query 사용자 질문
     * @param image 첨부 이미지
     * @return Flux<String> (리액티브 스트리밍 응답)
     */
    Flux<String> streamChat(@UserMessage String query, @UserMessage ImageContent image);

    /**
     * 일반 채팅 응답 생성 (비스트리밍)
     *
     * @param query 사용자 질문
     * @return AI 응답
     */
    String chat(@UserMessage String query);
}

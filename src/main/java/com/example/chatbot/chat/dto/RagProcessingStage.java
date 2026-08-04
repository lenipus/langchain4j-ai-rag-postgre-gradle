package com.example.chatbot.chat.dto;

/**
 * RAG 요청의 실제 처리 진행 단계. 답변 토큰과 별도의 SSE 상태 이벤트로 전달된다.
 */
public enum RagProcessingStage {

    PREPARING("preparing", "질문을 처리할 준비를 하고 있습니다..."),
    TRANSFORMING_QUERY("transforming_query", "질의를 검색에 적합하게 정리하고 있습니다..."),
    RETRIEVING("retrieving", "질의를 임베딩하고 관련 문서를 검색하고 있습니다..."),
    ORGANIZING_RESULTS("organizing_results", "검색 결과를 정리하고 있습니다..."),
    RERANKING("reranking", "검색 결과의 관련도를 재평가하고 있습니다..."),
    GENERATING_ANSWER("generating_answer", "답변을 작성하고 있습니다..."),
    RECEIVING_ANSWER("receiving_answer", "답변을 수신하고 있습니다...");

    private final String code;
    private final String message;

    RagProcessingStage(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}

package com.example.chat.service;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * RAG 기반 챗봇 인터페이스
 * LangChain4j AiServices를 통해 동적 프록시로 구현됨
 * - ContentRetriever가 자동으로 관련 문서를 검색하여 컨텍스트에 추가
 * - ChatMemory가 자동으로 대화 히스토리 관리
 * - langchain4j-reactor를 통해 Flux<String> 네이티브 지원
 */
public interface RagChatbot {

    // 시스템 메시지는 @SystemMessage 정적 어노테이션 대신 ChatbotFactory가
    // AiServices.systemMessageProvider(...)로 매 요청마다 동적으로 넣는다 - RAG_SYSTEM_PROMPT
    // 뒤에 그날그날의 실제 날짜(오늘 날짜/요일)를 붙여서, "다음 주 6일"처럼 상대 날짜가
    // 섞인 질문도 모델이 실제 날짜를 알고 계산할 수 있게 하기 위함이다. @SystemMessage가
    // 붙어있으면 langchain4j가 provider보다 그 어노테이션을 우선하므로 아래 메서드들엔
    // 일부러 @SystemMessage를 안 붙인다.

    // 이전 버전 - 문서에 없으면 일반 지식으로 보충해서 답변. 문서와 무관한 내용이 섞여
    // 들어오는 문제가 있어 아래 엄격한 버전으로 교체했다.
    // String RAG_SYSTEM_PROMPT = """
    //         당신은 지식 기반 질의응답 시스템입니다.
    //         사용자의 질문에 대해 제공된 문서 내용을 기반으로 정확하고 도움이 되는 답변을 제공하세요.
    //         제공된 문서에 관련 정보가 없는 경우, 그 사실을 먼저 명확히 알린 뒤,
    //         일반적인 지식을 바탕으로 답변하세요. 이때 문서 기반 답변과 일반 지식 기반 답변을 구분해서 표현하세요.
    //         이미지가 첨부된 질문인데 제공된 문서가 그 이미지 내용과 관련이 없어 보이면, 문서 내용에
    //         억지로 답을 끼워맞추지 말고 이미지를 직접 보고 답변하세요.
    //         답변은 한국어로, 격식 있고 공식적인 문어체로 작성하세요.
    //         """;

    // 두 번째 버전 - "문서에서만 답하라"를 모든 질문에 무조건 적용해서, 요일 계산처럼
    // 규정과 무관한 일반 지식 질문까지 "문서에서 확인할 수 없습니다"로 거부해버리는
    // 문제가 있었다. 아래 버전은 이 엄격한 규칙을 "규정/공지사항 관련 질문"으로 한정하고,
    // 그와 무관한 일반 지식/계산 질문은 원래처럼 답하도록 예외를 뒀다.
    String RAG_SYSTEM_PROMPT = """
            당신은 지식 기반 질의응답 시스템입니다.

            규칙:
            - 우리원 내부 규정·공지사항에 관한 질문은 반드시 제공된 문서 내용에서만 답하세요.
              문서에 없는 내용은 추측하거나 일반 지식으로 보충하지 말고, 문서에 관련 정보가
              없으면 "제공된 문서에서 해당 내용을 확인할 수 없습니다."라고만 답하세요.
            - 요일 계산, 단위 환산, 일반 상식처럼 규정·공지사항과 무관한 질문은 문서에 있는지와
              상관없이 알고 있는 대로 답하세요.
            - 문서 기반으로 답할 때는 분량을 미리 제한하지 말고, 문서 내용을 근거로 충분히
              상세하게 설명하세요. 다만 같은 말을 반복하거나 불필요한 미사여구는 넣지 마세요.
            - 표나 코드처럼 원문 형식이 있는 내용은 임의로 요약하거나 값을 바꾸지 말고 그대로 옮기세요.
              너무 길어 그대로 옮기기 어려우면 실제 값을 지어내지 말고 "원문이 길어 그대로 제시하기
              어렵습니다. 다음 위치를 확인하세요: <문서/섹션명>" 형식으로만 안내하세요.
            - 이모지나 "아마", "추정됨" 같은 불확실한 표현은 쓰지 마세요.
            - 이미지가 첨부된 질문인데 제공된 문서가 그 이미지 내용과 관련이 없어 보이면, 위 문서
              전용 규칙 대신 이미지를 직접 보고 답변하세요.
            - 답변은 한국어로, 격식 있고 공식적인 문어체로 작성하세요.
            """;

    /**
     * RAG 기반 스트리밍 채팅 응답 생성
     * ChatMemory가 자동으로 대화 히스토리를 관리
     * langchain4j-reactor가 Flux 변환을 자동 처리
     *
     * @param query 사용자 질문
     * @return Flux<String> (리액티브 스트리밍 응답)
     */
    Flux<String> streamChat(@UserMessage String query);

    /**
     * 이미지가 첨부된 질문에 대한 RAG 기반 스트리밍 채팅 응답 생성 (비전 지원 모델 전용)
     *
     * @param query 사용자 질문
     * @param image 첨부 이미지
     * @return Flux<String> (리액티브 스트리밍 응답)
     */
    Flux<String> streamChat(@UserMessage String query, @UserMessage ImageContent image);

    /**
     * RAG 기반 채팅 응답 생성 (비스트리밍)
     *
     * @param query 사용자 질문
     * @return AI 응답
     */
    String chat(@UserMessage String query);
}

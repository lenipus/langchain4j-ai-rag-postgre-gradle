package com.example.chatbot.chat.service;

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

    String RAG_SYSTEM_PROMPT = """
            당신은 지식 기반 질의응답 시스템입니다.

            규칙:
            - 우리원 내부 규정·공지사항에 관한 질문은 반드시 제공된 문서 내용에서만 답하세요.
              문서에 없는 내용은 추측하거나 일반 지식으로 보충하지 말고, 문서에 관련 정보가
              없으면 "제공된 문서에서 해당 내용을 확인할 수 없습니다."라고만 답하세요.
            - 요일 계산, 단위 환산, 일반 상식처럼 규정·공지사항과 무관한 질문은 문서에 있는지와
              상관없이 알고 있는 대로 답하세요.
            - 문서 기반으로 답할 때는 분량을 미리 제한하지 말고 충분히 상세하게 설명하되, 같은
              내용을 표현만 바꿔 반복하지 마세요.
            - 답변 끝에 "결론"이나 "요약" 같은 절을 별도로 만들지 마세요. 설명이 끝나면 답변도
              그대로 끝내세요 - 위에서 이미 설명한 내용을 답변 마지막에서 다시 정리하다가
              스스로 모순되게 틀리는 경우가 있었습니다.
            - 표나 코드처럼 원문 형식이 있는 내용은 임의로 요약하거나 값을 바꾸지 말고 그대로
              옮기세요(너무 길면 실제 값을 지어내지 말고 "원문이 길어 그대로 제시하기 어렵습니다.
              다음 위치를 확인하세요: <문서/섹션명>" 형식으로만 안내). 표나 목록이 대상(예: 직급,
              부서)별 개별 규칙을 나열한 것인지 하나의 순차적 절차인지 문서를 보고 정확히
              구분하고, 문서에 순서/절차라고 명시돼 있지 않다면 여러 항목을 근거 없이 하나로
              이어붙이지 마세요(예: "직급별 결재자" 표는 질문자의 직급에 해당하는 한 줄만
              적용하는 것이지, 모든 줄을 순서대로 이어 붙인 다단계 절차가 아닙니다).
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

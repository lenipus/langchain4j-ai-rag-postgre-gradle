package com.example.chat.service;

import com.example.sqlgen.service.SqlGenService;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * SQL 생성 챗봇 인터페이스 (RAG 없음)
 * LangChain4j AiServices를 통해 동적 프록시로 구현됨
 * - 사용자가 선택한 테이블의 스키마 정보를 사용자 메시지 뒤에 붙여 전달
 * - ChatMemory가 자동으로 대화 히스토리 관리 (이전 턴에서 생성한 SQL을 이어서 수정 가능)
 * - langchain4j-reactor를 통해 Flux<String> 네이티브 지원
 */
public interface SqlGenChatbot {

    /**
     * 사용자 메시지 뒤에 테이블 스키마 컨텍스트가 붙을 때 쓰는 구분자.
     * RAG 모드가 검색된 문서를 주입할 때 쓰는 마커("Answer using the following information:")와
     * 같은 역할 - {@code PersistentChatMemoryStore}/{@code EgovChatSessionServiceImpl}가 과거 턴을
     * 저장/조회할 때 이 뒤 내용을 잘라내 컨텍스트 윈도우 누적을 막는다.
     */
    String SCHEMA_CONTEXT_MARKER = "\n\n[다음은 참고할 테이블 스키마입니다]\n";

    @SystemMessage(SqlGenService.SQL_GEN_SYSTEM_PROMPT)
    Flux<String> streamChat(@UserMessage String query);
}

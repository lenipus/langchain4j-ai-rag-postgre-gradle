package com.example.sqlgen.service;

import com.example.sqlgen.dto.RegisterSqlGenConnectionRequest;
import com.example.sqlgen.dto.SqlGenConnectionDto;
import com.example.sqlgen.dto.TableInfoDto;
import com.example.sqlgen.dto.TableSchemaDto;

import java.util.List;

/**
 * SQL 생성용 DB 연결 등록/테이블·스키마 조회 기능. 등록된 JDBC 연결 정보에 접속해 테이블
 * 스키마를 조회하고, LLM에 전달할 스키마 컨텍스트 텍스트를 만든다.
 *
 * <p>실제 LLM 호출은 채팅 화면(SQL 생성 모드)의 {@code EgovChatServiceImpl}이
 * {@link com.example.chat.service.SqlGenChatbot}을 통해 수행한다 - 이 인터페이스는 그때 쓸
 * 스키마 컨텍스트만 만들어준다. 생성된 SQL은 절대 자동 실행하지 않는다 - 텍스트로만
 * 반환해 사용자가 직접 검토 후 사용하도록 한다.</p>
 */
public interface SqlGenService {

    /**
     * SQL 생성 시 항상 적용되는 공통 지시사항(System 메시지로 전달됨).
     * DBMS 이름/테이블 스키마처럼 매 요청마다 달라지는 내용은 여기 안 들어가고
     * {@code SqlGenServiceImpl.formatSchemaBlock()}이 만드는, 사용자 메시지 뒤에 붙는
     * 스키마 컨텍스트 쪽에 들어간다 (자세한 흐름은 {@link com.example.chat.service.SqlGenChatbot} 참고).
     */
    String SQL_GEN_SYSTEM_PROMPT = """
            당신은 SQL 전문가입니다. 사용자 메시지에 제공된 테이블 스키마를 참고하여 사용자의 요청에 맞는 SQL 쿼리를 작성하세요.
            먼저 쿼리를 어떻게 작성했는지, 어떤 테이블/컬럼을 왜 선택했는지 간단한 설명을 작성하세요.
            SQL 쿼리는 반드시 응답의 맨 마지막에, 설명이 모두 끝난 뒤에 작성하세요. 쿼리 코드블록 다음에는 어떤 텍스트도 추가하지 마세요.
            SQL 쿼리 부분은 반드시 ```sql 로 시작하는 코드블록으로 감싸서 작성하세요.
            SELECT/FROM/WHERE/GROUP BY/ORDER BY 등 절마다 줄을 바꾸고 들여쓰기를 사용해, 읽기 좋게 줄을 맞춰서 작성하세요.
            SQL 쿼리의 키워드/테이블명/컬럼명은 모두 대문자로 작성하세요 (SELECT, FROM, INNER JOIN, ON, WHERE, AND, OR, ORDER BY, ASC 등 포함).
            """;

    /** 저장하지 않고 접속 가능 여부만 확인한다. 실패하면 예외를 던진다. */
    void testConnection(String jdbcUrl, String username, String password);

    SqlGenConnectionDto registerConnection(RegisterSqlGenConnectionRequest request);

    List<SqlGenConnectionDto> listConnections();

    void deleteConnection(Long connectionId);

    List<TableInfoDto> listTables(Long connectionId);

    TableSchemaDto getTableSchema(Long connectionId, String tableName);

    /**
     * 자연어 요청 없이, 대상 DBMS/테이블 스키마만 텍스트로 만든다 (채팅 통합용).
     * 채팅 화면의 SQL 생성 모드가 사용자 메시지 뒤에 이 텍스트를 붙여 LLM에 전달한다.
     */
    String buildSchemaContext(Long connectionId, List<String> tableNames);
}

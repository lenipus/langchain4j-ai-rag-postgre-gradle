package com.example.sqlgen.service;

import com.example.sqlgen.dto.RegisterSqlGenConnectionRequest;
import com.example.sqlgen.dto.SqlGenConnectionDto;
import com.example.sqlgen.dto.TableInfoDto;
import com.example.sqlgen.dto.TableSchemaDto;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 자연어 요청으로 SQL 쿼리를 생성하는 기능. 등록된 JDBC 연결 정보에 접속해 테이블
 * 스키마를 조회하고, 그 스키마를 컨텍스트로 LLM에 SQL 생성을 요청한다.
 *
 * <p>생성된 SQL은 절대 자동 실행하지 않는다 - 텍스트로만 반환해 사용자가 직접
 * 검토 후 사용하도록 한다.</p>
 */
public interface SqlGenService {

    /**
     * SQL 생성 시 항상 적용되는 공통 지시사항(System 메시지로 전달됨).
     * DBMS 이름/테이블 스키마/사용자 요청처럼 매 요청마다 달라지는 내용은 여기 안 들어가고
     * {@code SqlGenServiceImpl.buildPrompt()}가 만드는 User 메시지 쪽에 들어간다.
     */
    String SQL_GEN_SYSTEM_PROMPT = """
            당신은 SQL 전문가입니다. 사용자 메시지에 제공된 테이블 스키마를 참고하여 사용자의 요청에 맞는 SQL 쿼리를 작성하세요.
            쿼리를 어떻게 작성했는지, 어떤 테이블/컬럼을 왜 선택했는지 간단한 설명도 함께 제공하세요.
            SQL 쿼리 부분은 반드시 ```sql 로 시작하는 코드블록으로 감싸서 작성하세요.
            SELECT/FROM/WHERE/GROUP BY/ORDER BY 등 절마다 줄을 바꾸고 들여쓰기를 사용해, 읽기 좋게 줄을 맞춰서 작성하세요.
            """;

    /** 저장하지 않고 접속 가능 여부만 확인한다. 실패하면 예외를 던진다. */
    void testConnection(String jdbcUrl, String username, String password);

    SqlGenConnectionDto registerConnection(RegisterSqlGenConnectionRequest request);

    List<SqlGenConnectionDto> listConnections();

    void deleteConnection(Long connectionId);

    List<TableInfoDto> listTables(Long connectionId);

    TableSchemaDto getTableSchema(Long connectionId, String tableName);

    /** 채팅처럼 토큰 단위로 응답을 스트리밍한다. LLM 원문(설명 포함)을 그대로 흘려보낸다 - SQL만 추출하는 건 클라이언트가 스트림이 끝난 뒤 처리한다. */
    Flux<String> generateSqlStream(Long connectionId, List<String> tableNames, String naturalLanguageRequest);
}

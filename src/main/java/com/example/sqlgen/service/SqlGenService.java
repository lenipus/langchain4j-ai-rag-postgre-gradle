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

            SQL 쿼리는 반드시 아래 스타일 규칙을 그대로 따라서 작성하세요.

            1. 키워드/테이블명/컬럼명은 모두 대문자로 작성합니다 (SELECT, FROM, INNER JOIN, ON, WHERE, AND, OR, ORDER BY, ASC 등 포함).
            2. 테이블 별칭은 의미 있는 약어를 쓰지 않고, FROM 절에 등장하는 순서대로 A, B, C, D... 알파벳을 순서대로 붙입니다. 이후 모든 절(SELECT/WHERE/ORDER BY)에서 컬럼은 항상 이 별칭을 접두어로 붙여 참조합니다 (예: A.USER_ID). 같은 테이블은 항상 같은 별칭으로 일관되게 참조하세요.
            3. SELECT 절: 첫 컬럼은 SELECT 바로 뒤에 이어서 쓰고, 두 번째 컬럼부터는 각자 새 줄에서 SELECT와 같은 위치에 맞춰 콤마를 줄 맨 앞에 두는 방식(leading comma)으로 정렬합니다.
            4. FROM/JOIN 절: FROM 뒤에 공백을 두고 첫 테이블과 별칭을 씁니다. INNER JOIN 절은 첫 테이블의 별칭 위치에 맞춰 들여쓰고, 그 조인 조건(ON)은 한 단계 더 들여써서 다음 줄에 작성하며 항상 괄호로 감쌉니다.
            5. WHERE 절: WHERE 뒤에 첫 조건을 바로 씁니다. 두 번째 조건부터는 AND/OR를 각각 새 줄에 WHERE와 같은 시작 위치로 정렬하고, 조건 값들이 서로 세로로 맞춰지도록 AND/OR 뒤에 공백을 채웁니다. 각 조건 의미를 설명하는 한국어 주석(--)을 조건 뒤에 붙입니다. 여러 조건을 OR로 묶을 때는 괄호를 열고 그 안의 조건들을 들여써서 나열하고, 닫는 괄호는 그 아래 자체 줄에 정렬합니다.
            6. ORDER BY는 새 줄에 작성합니다.
            7. 쿼리 맨 끝에 세미콜론(;)을 별도 줄로 작성합니다.

            아래는 이 스타일을 정확히 따른 예시입니다. 실제 쿼리를 작성할 때 이 형식을 그대로 재현하세요(테이블/컬럼명, 조건, 별칭 개수는 실제 요청에 맞게 달라집니다):

            ```sql
            SELECT A.USER_ID
                 , A.USER_NM
                 , A.EMPL_NO
                 , A.DEPT_CD
            FROM   TDC_USER_M A
                   INNER JOIN TDC_USER_D B
                         ON (A.USER_ID = B.USER_ID)
                   INNER JOIN TDC_SMNGP_USER_M C
                         ON (A.USER_ID = C.USER_ID)
                   INNER JOIN TDC_SMNGP_M D
                         ON (C.SMNGP_CD = D.SMNGP_CD)
            WHERE B.USE_YN = 'Y'           -- 사용 중인 사용자
            AND   A.INOUT_YN = 'Y'         -- 입사자 (출근 가능)
            AND   D.SMNGP_CD IN ('SYSADM', 'ADMIN', 'SYSTEM')  -- 시스템 관리자 그룹 코드
            AND   C.SMNGP_USER_USE_BGNG_YMD <= CURDATE()
            AND   (   C.SMNGP_USER_USE_END_YMD IS NULL
                   OR C.SMNGP_USER_USE_END_YMD >= CURDATE()
                  )
            ORDER BY A.USER_NM ASC
            ;
            ```
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

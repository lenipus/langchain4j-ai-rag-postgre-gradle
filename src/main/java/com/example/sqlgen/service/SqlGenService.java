package com.example.sqlgen.service;

import com.example.sqlgen.dto.RegisterSqlGenConnectionRequest;
import com.example.sqlgen.dto.SqlGenConnectionDto;
import com.example.sqlgen.dto.TableInfoDto;
import com.example.sqlgen.dto.TableSchemaDto;

import java.util.List;

/**
 * 자연어 요청으로 SQL 쿼리를 생성하는 기능. 등록된 JDBC 연결 정보에 접속해 테이블
 * 스키마를 조회하고, 그 스키마를 컨텍스트로 LLM에 SQL 생성을 요청한다.
 *
 * <p>생성된 SQL은 절대 자동 실행하지 않는다 - 텍스트로만 반환해 사용자가 직접
 * 검토 후 사용하도록 한다.</p>
 */
public interface SqlGenService {

    /** 저장하지 않고 접속 가능 여부만 확인한다. 실패하면 예외를 던진다. */
    void testConnection(String jdbcUrl, String username, String password);

    SqlGenConnectionDto registerConnection(RegisterSqlGenConnectionRequest request);

    List<SqlGenConnectionDto> listConnections();

    void deleteConnection(Long connectionId);

    List<TableInfoDto> listTables(Long connectionId);

    TableSchemaDto getTableSchema(Long connectionId, String tableName);

    String generateSql(Long connectionId, List<String> tableNames, String naturalLanguageRequest);
}

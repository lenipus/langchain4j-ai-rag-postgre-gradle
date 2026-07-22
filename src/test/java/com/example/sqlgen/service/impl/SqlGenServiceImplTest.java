package com.example.sqlgen.service.impl;

import com.example.sqlgen.dto.TableColumnDto;
import com.example.sqlgen.dto.TableSchemaDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SqlGenServiceImpl}의 순수 로직(대상 DBMS/테이블 스키마 텍스트 구성)을 검증한다.
 */
class SqlGenServiceImplTest {

    private final SqlGenServiceImpl service = new SqlGenServiceImpl(null, null);

    @Test
    @DisplayName("대상 DBMS/테이블명/컬럼(타입, PK, NULLABLE 여부)이 모두 텍스트에 들어간다")
    void formatsSchemaBlockWithTableAndColumnInfo() {
        TableSchemaDto schema = new TableSchemaDto("public.users", List.of(
                new TableColumnDto("id", "bigint", false, true),
                new TableColumnDto("name", "varchar", true, false)));

        String block = service.formatSchemaBlock(List.of(schema), "MariaDB");

        assertThat(block).contains("대상 DBMS는 MariaDB입니다");
        assertThat(block).contains("public.users");
        assertThat(block).contains("id (bigint, PK, NOT NULL)");
        assertThat(block).contains("name (varchar, NULL 허용)");
    }

    @Test
    @DisplayName("공통 절차적 지시사항(System 프롬프트)에는 형식 규칙이 한글로 들어있다 (영어와 실측 비교해 차이 없어 한글로 확정)")
    void systemPromptContainsProceduralInstructionsInKorean() {
        assertThat(com.example.sqlgen.service.SqlGenService.SQL_GEN_SYSTEM_PROMPT)
                .contains("SQL 전문가")
                .contains("```sql")
                .contains("간단한 설명");
    }
}

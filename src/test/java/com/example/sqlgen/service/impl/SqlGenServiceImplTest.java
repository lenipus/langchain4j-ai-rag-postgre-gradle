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
        TableSchemaDto schema = new TableSchemaDto("public.users", null, List.of(
                new TableColumnDto("id", "bigint", false, true, null),
                new TableColumnDto("name", "varchar", true, false, null)));

        String block = service.formatSchemaBlock(List.of(schema), "MariaDB");

        assertThat(block).contains("대상 DBMS는 MariaDB입니다");
        assertThat(block).contains("public.users");
        assertThat(block).contains("id (bigint, PK, NOT NULL)");
        assertThat(block).contains("name (varchar, NULL 허용)");
    }

    @Test
    @DisplayName("테이블/컬럼 코멘트가 있으면 함께 표시된다 (영문 이름만으로는 의미가 불분명해서 LLM에 같이 전달)")
    void formatsSchemaBlockWithComments() {
        TableSchemaDto schema = new TableSchemaDto("public.users", "회원 정보", List.of(
                new TableColumnDto("id", "bigint", false, true, "회원 번호"),
                new TableColumnDto("name", "varchar", true, false, null)));

        String block = service.formatSchemaBlock(List.of(schema), "MariaDB");

        assertThat(block).contains("[테이블: public.users - 회원 정보]");
        assertThat(block).contains("id (bigint, PK, NOT NULL) - 회원 번호");
        // 코멘트가 없는 컬럼은 " - " 접미사 없이 그대로 끝난다 (마지막 줄이라 stripTrailing으로 개행은 제거됨)
        assertThat(block).endsWith("name (varchar, NULL 허용)");
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

package com.example.sqlgen.service.impl;

import com.example.sqlgen.dto.TableColumnDto;
import com.example.sqlgen.dto.TableSchemaDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SqlGenServiceImpl}의 순수 로직(LLM 응답에서 SQL 추출, 프롬프트 구성)을 검증한다.
 *
 * <p>{@code extractSql}은 RAG 질의 압축에서 실제로 겪은 문제 - 로컬 모델이 "SQL만
 * 출력해"라는 지시를 안 따르고 추론 과정까지 같이 뱉는 경우 - 를 그대로 재현해 검증한다.</p>
 */
class SqlGenServiceImplTest {

    private final SqlGenServiceImpl service = new SqlGenServiceImpl(null, null, null);

    @Test
    @DisplayName("모델이 SQL만 깔끔하게 출력하면 그대로 반환한다")
    void returnsRawTextWhenAlreadyClean() {
        String response = "SELECT * FROM users WHERE created_at >= now() - interval '7 days';";
        assertThat(service.extractSql(response)).isEqualTo(response);
    }

    @Test
    @DisplayName("```sql 코드블록으로 감싸져 있으면 그 안쪽만 추출한다")
    void extractsSqlFromLanguageTaggedCodeFence() {
        String response = "```sql\nSELECT id FROM users;\n```";
        assertThat(service.extractSql(response)).isEqualTo("SELECT id FROM users;");
    }

    @Test
    @DisplayName("언어 태그 없는 ``` 코드블록도 안쪽만 추출한다")
    void extractsSqlFromPlainCodeFence() {
        String response = "```\nSELECT id FROM users;\n```";
        assertThat(service.extractSql(response)).isEqualTo("SELECT id FROM users;");
    }

    @Test
    @DisplayName("코드블록 없이 추론 과정 + 'SQL:' 표시가 있으면 그 뒤부터 취한다")
    void extractsSqlAfterMarkerWhenModelAddsReasoning() {
        String response = "사용자는 최근 가입자 수를 알고 싶어합니다. 이를 위해 users 테이블을 조회해야 합니다.\n\n"
                + "SQL: SELECT count(*) FROM users WHERE created_at >= now() - interval '7 days';";
        assertThat(service.extractSql(response))
                .isEqualTo("SELECT count(*) FROM users WHERE created_at >= now() - interval '7 days';");
    }

    @Test
    @DisplayName("null 응답은 빈 문자열로 처리한다")
    void returnsEmptyStringForNullResponse() {
        assertThat(service.extractSql(null)).isEmpty();
    }

    @Test
    @DisplayName("프롬프트에 테이블명과 컬럼(타입, PK, NULL 허용 여부)이 모두 들어간다")
    void buildsPromptWithTableAndColumnInfo() {
        TableSchemaDto schema = new TableSchemaDto("public.users", List.of(
                new TableColumnDto("id", "bigint", false, true),
                new TableColumnDto("name", "varchar", true, false)));

        String prompt = service.buildPrompt(List.of(schema), "최근 7일간 가입한 사용자 수를 보여줘");

        assertThat(prompt).contains("public.users");
        assertThat(prompt).contains("id (bigint, PK, NOT NULL)");
        assertThat(prompt).contains("name (varchar, NULL 허용)");
        assertThat(prompt).contains("최근 7일간 가입한 사용자 수를 보여줘");
        assertThat(prompt).contains("SQL 쿼리 하나만 출력");
        assertThat(prompt).contains("줄을 바꾸고 들여쓰기를 사용해");
    }
}

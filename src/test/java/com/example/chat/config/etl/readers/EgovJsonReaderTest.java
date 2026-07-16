package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovJsonReader}가 고정 스키마(제목/원본파일명/구축일자/게시일자/키워드/내용) JSON을
 * 임베딩 대상 본문 텍스트로 올바르게 펼치는지 검증한다.
 *
 * <p>특히 "내용" 하위 table.tb 필드는 HTML 표이므로, 태그를 그냥 지워버리면 셀 내용이
 * 공백 없이 붙어버려 검색 텍스트로서 의미가 왜곡된다. 행/셀 경계가 유지되는지 확인한다.</p>
 */
class EgovJsonReaderTest {

    // ByteArrayResource는 getFile()에서 IOException을 던지므로
    // DocumentIdUtil.uniquePathKey가 fallbackFilename으로 대체된다.
    private Document parse(String json, String filename) throws Exception {
        Method method = EgovJsonReader.class.getDeclaredMethod("parseJsonDocument", Resource.class);
        method.setAccessible(true);
        Resource resource = new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        return (Document) method.invoke(new EgovJsonReader(new DocumentIdUtil()), resource);
    }

    @Test
    @DisplayName("제목·키워드·본문 문단·표가 하나의 본문 텍스트로 펼쳐진다")
    void flattensFixedSchemaIntoBodyText() throws Exception {
        String json = """
                {
                    "제목": "위임전결규칙",
                    "원본파일명": "02 위임전결규칙 (2024-01-23).hwp",
                    "구축일자": "2024.11.28",
                    "게시일자": "2024.01.23",
                    "키워드": ["용어정의", "전결사항"],
                    "내용": [
                        {
                            "text": [
                                {
                                    "title": "위임전결규칙",
                                    "content": ["제1조(목적) 이 규칙은...", "제2조(적용) 정보원의..."]
                                }
                            ]
                        },
                        {
                            "table": [
                                {
                                    "tb_title": "위 임 전 결 사 항",
                                    "tb": "<table><tbody><tr><td>구분</td><td>팀장</td></tr><tr><td>인사</td><td>○</td></tr></tbody></table>",
                                    "tb_caption": []
                                }
                            ]
                        },
                        { "image": [] }
                    ]
                }
                """;

        Document document = parse(json, "위임전결규칙.json");

        assertThat(document.text()).contains("위임전결규칙");
        assertThat(document.text()).contains("키워드: 용어정의, 전결사항");
        assertThat(document.text()).contains("제1조(목적) 이 규칙은...");
        assertThat(document.text()).contains("위 임 전 결 사 항");
        // 표 셀은 태그만 지워지는 게 아니라 공백으로 분리돼야 "구분팀장"처럼 붙지 않는다
        assertThat(document.text()).contains("구분 팀장");
        assertThat(document.text()).doesNotContain("구분팀장");

        assertThat(document.metadata().getString("type")).isEqualTo("json");
        assertThat(document.metadata().getString("file_name")).isEqualTo("위임전결규칙.json");
        assertThat(document.metadata().getString("original_file_name"))
                .isEqualTo("02 위임전결규칙 (2024-01-23).hwp");
        assertThat(document.metadata().getString("build_date")).isEqualTo("2024.11.28");
        assertThat(document.metadata().getString("post_date")).isEqualTo("2024.01.23");
    }

    @Test
    @DisplayName("표 안 줄바꿈(br)은 공백으로, 행 끝은 줄바꿈으로 치환된다")
    void flattensTableRowsAndLineBreaks() throws Exception {
        String json = """
                {
                    "제목": "테스트",
                    "내용": [
                        {
                            "table": [
                                {
                                    "tb_title": "표",
                                    "tb": "<table><tr><td>가<br/>나</td><td>다</td></tr><tr><td>라</td><td>마</td></tr></table>"
                                }
                            ]
                        }
                    ]
                }
                """;

        Document document = parse(json, "test.json");

        assertThat(document.text()).contains("가 나 다");
        assertThat(document.text()).contains("라 마");
    }

    @Test
    @DisplayName("추출된 텍스트가 없으면 null을 반환한다")
    void returnsNullWhenNoExtractableText() throws Exception {
        String json = """
                {
                    "제목": "",
                    "키워드": [],
                    "내용": []
                }
                """;

        Document document = parse(json, "empty.json");

        assertThat(document).isNull();
    }
}

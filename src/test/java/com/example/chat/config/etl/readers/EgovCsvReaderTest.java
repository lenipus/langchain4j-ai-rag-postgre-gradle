package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovCsvReader}가 헤더/값을 "헤더: 값" 형태로 펼치는지, 그리고 엑셀이 저장하는
 * MS949(CP949) 인코딩 CSV도 한글 깨짐 없이 읽어내는지 검증한다.
 *
 * <p>엑셀에서 "CSV(쉼표로 분리)"로 저장한 파일은 기본적으로 MS949로 인코딩되는데, 이를
 * UTF-8로 강제 디코딩하면 한글이 깨지고 이후 특수문자 정리 단계에서 대부분 삭제되어 버린다.</p>
 */
class EgovCsvReaderTest {

    // ByteArrayResource는 getFile()에서 IOException을 던지므로
    // DocumentIdUtil.uniquePathKey가 fallbackFilename으로 대체된다.
    private Document parse(byte[] bytes, String filename) throws Exception {
        Method method = EgovCsvReader.class.getDeclaredMethod("parseCsvDocument", Resource.class);
        method.setAccessible(true);
        Resource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        return (Document) method.invoke(new EgovCsvReader(new DocumentIdUtil()), resource);
    }

    @Test
    @DisplayName("UTF-8 CSV는 헤더: 값 형태로 펼쳐진다")
    void parsesUtf8Csv() throws Exception {
        String csv = "제공처,혜택명\nYBM,OPIc 응시료 지원\n";
        Document document = parse(csv.getBytes(StandardCharsets.UTF_8), "복지.csv");

        assertThat(document.text()).contains("제공처: YBM");
        assertThat(document.text()).contains("혜택명: OPIc 응시료 지원");
        assertThat(document.metadata().getString("type")).isEqualTo("csv");
    }

    @Test
    @DisplayName("엑셀이 저장한 MS949(CP949) CSV도 한글 깨짐 없이 읽힌다")
    void parsesMs949Csv() throws Exception {
        Charset ms949 = Charset.forName("MS949");
        String csv = "제공처,혜택명\nYBM,OPIc 응시료 지원\n";
        Document document = parse(csv.getBytes(ms949), "복지.csv");

        assertThat(document.text()).contains("제공처: YBM");
        assertThat(document.text()).contains("혜택명: OPIc 응시료 지원");
    }

    @Test
    @DisplayName("추출된 텍스트가 없으면 null을 반환한다")
    void returnsNullWhenNoExtractableText() throws Exception {
        String csv = "제공처,혜택명\n,\n";
        Document document = parse(csv.getBytes(StandardCharsets.UTF_8), "empty.csv");

        assertThat(document).isNull();
    }
}

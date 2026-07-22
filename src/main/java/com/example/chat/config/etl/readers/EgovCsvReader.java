package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * CSV 문서 로더
 *
 * <p>첫 행을 헤더로 사용해 각 데이터 행을 "헤더: 값" 형태로 펼친다. 셀을 콤마로만
 * 이어붙이면 컬럼 의미가 사라져 검색 텍스트로서 가치가 떨어지므로, JSON 리더와 동일하게
 * 필드명을 함께 남긴다. commons-csv를 사용해 따옴표로 감싼 필드/내부 콤마 등 RFC 4180
 * 규칙을 정확히 처리한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovCsvReader implements EgovDocumentReader {

    // 엑셀에서 "CSV(쉼표로 분리)"로 저장하면 기본적으로 MS949(CP949)로 저장되어, UTF-8로
    // 강제 디코딩하면 한글 멀티바이트가 깨지고 이후 특수문자 정리 단계에서 대부분 삭제된다.
    // UTF-8 엄격 디코딩을 우선 시도하고 실패할 때만 MS949로 폴백한다.
    private static final Charset FALLBACK_CHARSET = Charset.forName("MS949");
    private static final char BOM = 0xFEFF;

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("csv");
    }

    @Override
    public List<Document> parse(Resource resource) throws Exception {
        Document document = parseCsvDocument(resource);
        return document == null ? List.of() : List.of(document);
    }

    private Document parseCsvDocument(Resource resource) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.csv";
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .build();

        String csvText = decodeCsvBytes(resource.getInputStream().readAllBytes());

        StringBuilder sb = new StringBuilder();
        try (Reader in = new StringReader(csvText);
             CSVParser parser = format.parse(in)) {

            List<String> headers = parser.getHeaderNames();
            for (CSVRecord record : parser) {
                boolean rowHasContent = false;
                for (String header : headers) {
                    if (header == null || header.isBlank() || !record.isMapped(header)) {
                        continue;
                    }
                    String value = record.get(header);
                    if (value != null && !value.isBlank()) {
                        sb.append(header).append(": ").append(value).append("\n");
                        rowHasContent = true;
                    }
                }
                if (rowHasContent) {
                    sb.append("\n");
                }
            }
        }

        String content = sb.toString().trim();
        if (content.isEmpty()) {
            log.warn("CSV 파일 '{}': 추출된 텍스트가 없습니다.", filename);
            return null;
        }

        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        String customId = String.format("csv-%s_1", safeFilename);

        Metadata metadata = Metadata.from("id", customId);
        metadata.put("file_name", filename);
        metadata.put("source", filename);
        metadata.put("type", "csv");
        metadata.put("content_length", String.valueOf(content.length()));
        metadata.put("page_number", "1");

        log.debug("CSV Document ID: {} (길이: {})", customId, content.length());

        return Document.from(content, metadata);
    }

    private String decodeCsvBytes(byte[] bytes) {
        CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String decoded;
        try {
            decoded = utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            log.debug("CSV UTF-8 디코딩 실패, {}(으)로 재시도합니다.", FALLBACK_CHARSET);
            decoded = new String(bytes, FALLBACK_CHARSET);
        }
        return !decoded.isEmpty() && decoded.charAt(0) == BOM ? decoded.substring(1) : decoded;
    }
}

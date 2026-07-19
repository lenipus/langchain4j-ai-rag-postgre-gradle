package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * HWP(한글) 문서 로더
 * hwplib 라이브러리를 사용하여 .hwp 파일에서 텍스트를 추출하고
 * LangChain4j Document 형태로 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovHwpReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("hwp");
    }

    @Override
    public List<Document> parse(Resource resource) throws Exception {
        Document document = parseHwpDocument(resource);
        return document == null ? List.of() : List.of(document);
    }

    private Document parseHwpDocument(Resource resource) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.hwp";
        }

        File file = resource.getFile();
        HWPFile hwpFile = HWPReader.fromFile(file);

        // 본문 + 표/각주 등 컨트롤 텍스트까지 포함하여 추출
        String content = TextExtractor.extract(hwpFile, TextExtractMethod.AppendControlTextAfterParagraphText);

        if (content == null || content.trim().isEmpty()) {
            log.warn("HWP 파일 '{}': 추출된 텍스트가 없습니다.", filename);
            return null;
        }

        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        String customId = String.format("hwp-%s_1", safeFilename);

        Metadata metadata = Metadata.from("id", customId);
        metadata.put("file_name", filename);
        metadata.put("source", filename);
        metadata.put("type", "hwp");
        metadata.put("content_length", String.valueOf(content.length()));
        metadata.put("page_number", "1");

        log.debug("HWP Document ID: {} (길이: {})", customId, content.length());

        return Document.from(content, metadata);
    }
}

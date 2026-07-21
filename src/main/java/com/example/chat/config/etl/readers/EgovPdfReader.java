package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * PDF 문서 로더
 * PDFBox로 페이지별 텍스트를 추출해 파일 하나를 문서 하나로 합친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovPdfReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("pdf");
    }

    @Override
    public List<Document> parse(Resource resource) throws IOException {
        return parsePdfDocument(resource);
    }

    /**
     * PDF 파일의 전체 페이지를 이어 붙여 파일당 하나의 문서로 생성한다.
     *
     * <p>예전에는 페이지마다 별도 문서(및 별도 id·해시)를 만들었다. 이러면 문장·표처럼
     * 페이지 경계를 넘어 이어지는 내용이 강제로 서로 다른 청크로 쪼개져 문맥이 끊기는
     * 문제가 있었다(청킹이 {@code EgovEnhancedDocumentTransformer}에서 이뤄지는데, 애초에
     * 리더 단계에서 페이지 단위로 문서가 나뉘어 있으니 청크가 페이지 경계를 넘을 수 없었다).
     * 다른 리더(HWP·DOCX·TXT·JSON)와 동일하게 파일 전체를 하나의 텍스트로 합쳐서 넘기면,
     * 청크 분할기가 페이지 경계와 무관하게 문맥이 이어지는 지점에서 자연스럽게 청크를
     * 나눈다. 텍스트가 비어 있는 페이지(스캔 이미지 등)는 건너뛴다.</p>
     */
    List<Document> parsePdfDocument(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.pdf";
        }
        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);

        StringBuilder combined = new StringBuilder();

        try (InputStream inputStream = resource.getInputStream();
             PDDocument pdf = PDDocument.load(inputStream)) {

            int pageCount = pdf.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String content = stripper.getText(pdf);

                if (content == null || content.isBlank()) {
                    log.debug("PDF '{}' {}페이지: 빈 내용으로 건너뜀", filename, page);
                    continue;
                }

                if (!combined.isEmpty()) {
                    combined.append("\n\n");
                }
                combined.append(content.strip());
            }
        }

        String text = combined.toString();
        if (text.isBlank()) {
            log.warn("PDF '{}': 추출된 텍스트가 없습니다.", filename);
            return List.of();
        }

        String customId = String.format("pdf-%s_1", safeFilename);
        Metadata metadata = Metadata.from("id", customId);
        metadata.put("file_name", filename);
        metadata.put("source", filename);
        metadata.put("type", "pdf");
        metadata.put("content_length", String.valueOf(text.length()));
        metadata.put("page_number", "1");

        log.debug("PDF '{}': 문서 1개로 병합 완료 (길이: {})", filename, text.length());

        return List.of(Document.from(text, metadata));
    }
}

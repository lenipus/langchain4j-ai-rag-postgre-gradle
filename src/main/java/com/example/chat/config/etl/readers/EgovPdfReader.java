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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDF 문서 로더
 * PDFBox로 페이지 단위 파싱하여 각 페이지에 실제 페이지 번호(page_number)를 부여한다.
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
     * PDF 파일을 페이지 단위로 파싱해 페이지당 하나의 문서를 생성한다.
     *
     * <p>PDFBox {@link PDFTextStripper}로 페이지별 텍스트를 추출하고, 각 문서에 실제 페이지
     * 번호({@code page_number})를 부여한다(출처 인용에 필요). 텍스트가 비어 있는 페이지(스캔
     * 이미지 등)는 색인에서 건너뛴다. 페이지 내 청킹은 {@code EgovEnhancedDocumentTransformer}가
     * 담당하며, 페이지 단위로 문서가 나뉘므로 청크가 페이지 경계를 넘지 않는다.</p>
     */
    List<Document> parsePdfDocument(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.pdf";
        }
        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);

        List<Document> documents = new ArrayList<>();

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

                String customId = String.format("pdf-%s_%d", safeFilename, page);
                Metadata metadata = Metadata.from("id", customId);
                metadata.put("file_name", filename);
                metadata.put("source", filename);
                metadata.put("type", "pdf");
                metadata.put("content_length", String.valueOf(content.length()));
                metadata.put("page_number", String.valueOf(page));

                documents.add(Document.from(content, metadata));
            }

            log.debug("PDF '{}': {}페이지 중 {}개 문서 생성", filename, pageCount, documents.size());
        }

        return documents;
    }
}

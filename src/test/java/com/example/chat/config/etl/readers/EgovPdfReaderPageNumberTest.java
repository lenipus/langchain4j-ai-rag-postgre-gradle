package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovPdfReader}가 PDF의 모든 페이지를 파일 하나의 문서로 합치는지 검증한다.
 *
 * <p>예전에는 페이지마다 별도 문서(별도 id·해시)를 만들었는데, 그러면 문장·표처럼 페이지
 * 경계를 넘어 이어지는 내용이 강제로 서로 다른 청크로 쪼개져 문맥이 끊기는 문제가 있었다.
 * PDFBox로 테스트용 다중 페이지 PDF를 즉석 생성해 외부 파일 의존 없이 결정적으로 확인한다.</p>
 */
class EgovPdfReaderPageNumberTest {

    /** page당 지정 텍스트를 담은 PDF 바이트를 생성한다. */
    private byte[] pdfWithPages(String... pageTexts) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(72, 720);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ByteArrayResource는 getFile()에서 IOException을 던지므로
    // DocumentIdUtil.uniquePathKey가 fallbackFilename(원본 파일명, 확장자 포함)으로 대체된다.
    private List<Document> parse(Resource resource) throws Exception {
        Method m = EgovPdfReader.class.getDeclaredMethod("parsePdfDocument", Resource.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Document> result = (List<Document>) m.invoke(new EgovPdfReader(new DocumentIdUtil()), resource);
        return result;
    }

    @Test
    @DisplayName("여러 페이지 PDF는 문서 하나로 합쳐지고 모든 페이지 내용을 포함한다")
    void mergesAllPagesIntoSingleDocument() throws Exception {
        byte[] pdf = pdfWithPages("PAGE ONE ALPHA", "PAGE TWO BRAVO");
        Resource resource = new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "guide.pdf";
            }
        };

        List<Document> docs = parse(resource);

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).text()).contains("ALPHA");
        assertThat(docs.get(0).text()).contains("BRAVO");
        assertThat(docs.get(0).metadata().getString("file_name")).isEqualTo("guide.pdf");
        // 파일 단위 id (다른 리더들과 동일한 규칙)
        assertThat(docs.get(0).metadata().getString("id")).isEqualTo("pdf-guide.pdf_1");
    }

    @Test
    @DisplayName("빈(텍스트 없는) 페이지는 건너뛰고 나머지 페이지 내용만 합쳐진다")
    void skipsBlankPagesWhenMerging() throws Exception {
        byte[] pdf = pdfWithPages("CONTENT PAGE", "   ", "LAST PAGE");
        Resource resource = new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "doc.pdf";
            }
        };

        List<Document> docs = parse(resource);

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).text()).contains("CONTENT PAGE");
        assertThat(docs.get(0).text()).contains("LAST PAGE");
    }

    @Test
    @DisplayName("모든 페이지가 비어 있으면 빈 리스트를 반환한다")
    void returnsEmptyWhenAllPagesBlank() throws Exception {
        byte[] pdf = pdfWithPages("   ", "  ");
        Resource resource = new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "blank.pdf";
            }
        };

        List<Document> docs = parse(resource);

        assertThat(docs).isEmpty();
    }
}

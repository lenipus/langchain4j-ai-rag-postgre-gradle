package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 엑셀(.xls/.xlsx) 문서 로더
 *
 * <p>Apache POI {@link WorkbookFactory}가 바이너리 시그니처로 두 포맷을 자동 인식하므로
 * 확장자별 리더를 나눌 필요가 없다. 시트마다 행 단위로 셀 값을 " | "로 구분한 평문으로
 * 펼쳐 표 구조가 검색 텍스트에서도 어느 정도 유지되도록 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovExcelReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("xls", "xlsx");
    }

    @Override
    public List<Document> parse(Resource resource) throws Exception {
        Document document = parseExcelDocument(resource);
        return document == null ? List.of() : List.of(document);
    }

    private Document parseExcelDocument(Resource resource) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.xlsx";
        }
        String extension = extensionOf(filename);

        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();

        try (InputStream inputStream = resource.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                StringBuilder sheetContent = new StringBuilder();

                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(formatter.formatCellValue(cell));
                    }
                    while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
                        cells.remove(cells.size() - 1);
                    }
                    if (!cells.isEmpty()) {
                        sheetContent.append(String.join(" | ", cells)).append("\n");
                    }
                }

                if (!sheetContent.isEmpty()) {
                    sb.append("## ").append(sheet.getSheetName()).append("\n")
                            .append(sheetContent).append("\n");
                }
            }
        }

        String content = sb.toString().trim();
        if (content.isEmpty()) {
            log.warn("엑셀 파일 '{}': 추출된 텍스트가 없습니다.", filename);
            return null;
        }

        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        String customId = String.format("%s-%s_1", extension, safeFilename);

        Metadata metadata = Metadata.from("id", customId);
        metadata.put("file_name", filename);
        metadata.put("source", filename);
        metadata.put("type", extension);
        metadata.put("content_length", String.valueOf(content.length()));
        metadata.put("page_number", "1");

        log.debug("엑셀 Document ID: {} (길이: {})", customId, content.length());

        return Document.from(content, metadata);
    }

    private String extensionOf(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex < 0 ? "xlsx" : filename.substring(dotIndex + 1).toLowerCase();
    }
}

package com.example.chat.config.etl.readers;

import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code document.upload-dir} 하나를 재귀적으로 스캔해, 파일 확장자에 맞는
 * {@link EgovDocumentReader}에게 파싱을 위임한다.
 *
 * <p>확장자별 경로 프로퍼티(예: {@code pdf-path}, {@code docx-path})나 서비스 계층의
 * 리더별 필드가 계속 늘어나는 것을 막기 위한 중앙 디스패처. 새 확장자를 지원하려면
 * {@link EgovDocumentReader} 구현체 하나만 추가하면 되고, 여기와 서비스 계층은
 * 수정할 필요가 없다.</p>
 */
@Slf4j
@Component
public class EgovDocumentScanner {

    private final Map<String, EgovDocumentReader> readersByExtension;

    @Value("${document.upload-dir}")
    private String uploadDir;

    @Value("${document.allowed-upload-extensions}")
    private String[] allowedUploadExtensions;

    public EgovDocumentScanner(List<EgovDocumentReader> readers) {
        Map<String, EgovDocumentReader> map = new HashMap<>();
        for (EgovDocumentReader reader : readers) {
            for (String extension : reader.supportedExtensions()) {
                map.put(extension.toLowerCase(), reader);
            }
        }
        this.readersByExtension = Map.copyOf(map);
    }

    /**
     * 업로드 디렉터리 전체를 스캔해 허용된 확장자의 파일을 각 리더로 파싱한다.
     */
    public List<Document> scanAll() {
        Set<String> allowedExtensions = normalizeExtensions(allowedUploadExtensions);

        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("file:" + uploadDir.replace(File.separatorChar, '/') + "/**/*");
        } catch (IOException e) {
            log.warn("문서 디렉터리를 스캔할 수 없습니다: {}", uploadDir);
            return List.of();
        }

        List<Document> allDocuments = new ArrayList<>();
        Map<String, Integer> countByExtension = new TreeMap<>();

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || !isRegularFile(resource)) {
                continue;
            }

            String extension = extensionOf(filename);
            if (extension.isEmpty() || !allowedExtensions.contains(extension)) {
                continue;
            }

            EgovDocumentReader reader = readersByExtension.get(extension);
            if (reader == null) {
                log.warn("허용된 확장자 '.{}'를 처리할 리더가 등록되어 있지 않습니다: {}", extension, filename);
                continue;
            }

            try {
                List<Document> documents = reader.parse(resource);
                if (!documents.isEmpty()) {
                    allDocuments.addAll(documents);
                    countByExtension.merge(extension, documents.size(), Integer::sum);
                }
            } catch (Exception e) {
                log.error("'{}' 파일 처리 중 오류 발생: {}", filename, e.getMessage());
            }
        }

        log.info("총 {}개의 문서를 로드했습니다. (확장자별: {})", allDocuments.size(), countByExtension);
        return allDocuments;
    }

    private boolean isRegularFile(Resource resource) {
        try {
            return resource.getFile().isFile();
        } catch (IOException e) {
            return false;
        }
    }

    private Set<String> normalizeExtensions(String[] extensions) {
        Set<String> normalized = new HashSet<>();
        for (String extension : extensions) {
            String trimmed = extension.trim();
            if (trimmed.startsWith(".")) {
                trimmed = trimmed.substring(1);
            }
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.toLowerCase());
            }
        }
        return normalized;
    }

    private String extensionOf(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex < 0 ? "" : filename.substring(dotIndex + 1).toLowerCase();
    }
}

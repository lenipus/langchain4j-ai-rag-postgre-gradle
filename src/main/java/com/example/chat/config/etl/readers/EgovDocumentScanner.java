package com.example.chat.config.etl.readers;

import com.example.chat.entity.DocumentHashEntity;
import com.example.chat.repository.DocumentHashRepository;
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
    private final DocumentHashRepository documentHashRepository;

    @Value("${document.upload-dir}")
    private String uploadDir;

    @Value("${document.allowed-upload-extensions}")
    private String[] allowedUploadExtensions;

    public EgovDocumentScanner(List<EgovDocumentReader> readers, DocumentHashRepository documentHashRepository) {
        Map<String, EgovDocumentReader> map = new HashMap<>();
        for (EgovDocumentReader reader : readers) {
            for (String extension : reader.supportedExtensions()) {
                map.put(extension.toLowerCase(), reader);
            }
        }
        this.readersByExtension = Map.copyOf(map);
        this.documentHashRepository = documentHashRepository;
    }

    /**
     * {@link #scanAll()}의 결과.
     *
     * @param documentsToProcess 새로 생겼거나 파일이 바뀐(mtime 불일치) 문서 - 이후 텍스트
     *                           해시 비교/청크 분할/임베딩 대상이 된다.
     * @param currentDocIds      현재 업로드 폴더에 실제로 존재하는 파일 전체의 ID(파싱을
     *                           건너뛴 안 바뀐 파일 포함) - 삭제된 파일 정리(cleanup)에 쓴다.
     */
    public record ScanResult(List<Document> documentsToProcess, Set<String> currentDocIds) {
    }

    /**
     * 업로드 디렉터리 전체를 스캔해 허용된 확장자의 파일을 각 리더로 파싱한다.
     *
     * <p>파일마다 파싱(예: PDF 텍스트 추출) 전에 {@link EgovDocumentReader#computeDocId}로
     * ID만 먼저 계산해, DB에 저장된 이전 수정 시각과 현재 파일의 수정 시각이 같으면 파싱
     * 자체를 건너뛴다 - 안 바뀐 파일도 매번 전체 파싱했던 낭비를 없앤다. mtime을 알 수
     * 없거나(테스트 리소스 등) DB에 기록이 없으면 안전하게 "바뀜"으로 보고 파싱한다.</p>
     */
    public ScanResult scanAll() {
        Set<String> allowedExtensions = normalizeExtensions(allowedUploadExtensions);

        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("file:" + uploadDir.replace(File.separatorChar, '/') + "/**/*");
        } catch (IOException e) {
            log.warn("문서 디렉터리를 스캔할 수 없습니다: {}", uploadDir);
            return new ScanResult(List.of(), Set.of());
        }

        List<Document> documentsToProcess = new ArrayList<>();
        Set<String> currentDocIds = new HashSet<>();
        Map<String, Integer> countByExtension = new TreeMap<>();
        int skippedUnchanged = 0;

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
                String docId = reader.computeDocId(resource, filename);
                currentDocIds.add(docId);

                if (isUnchangedSinceLastIndex(resource, docId)) {
                    skippedUnchanged++;
                    continue;
                }

                List<Document> documents = reader.parse(resource);
                if (!documents.isEmpty()) {
                    documentsToProcess.addAll(documents);
                    countByExtension.merge(extension, documents.size(), Integer::sum);
                }
            } catch (Exception e) {
                log.error("'{}' 파일 처리 중 오류 발생: {}", filename, e.getMessage());
            }
        }

        log.info("총 {}개 파일 스캔 - {}개 파싱 필요, {}개는 변경 없어 건너뜀 (확장자별 파싱: {})",
                currentDocIds.size(), documentsToProcess.size(), skippedUnchanged, countByExtension);
        return new ScanResult(documentsToProcess, currentDocIds);
    }

    /**
     * 파일의 수정 시각이 DB에 저장된 마지막 처리 시각과 같으면(=안 바뀜) true.
     * mtime을 못 구하거나(테스트용 리소스 등) 저장된 값이 없으면(신규 파일, 이 기능
     * 도입 전 레코드 등) false를 반환해 항상 파싱하도록 안전하게 폴백한다.
     */
    private boolean isUnchangedSinceLastIndex(Resource resource, String docId) {
        try {
            long currentModified = resource.getFile().lastModified();
            return documentHashRepository.findById(docId)
                    .map(DocumentHashEntity::getSourceLastModified)
                    .map(stored -> stored == currentModified)
                    .orElse(false);
        } catch (IOException e) {
            return false;
        }
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

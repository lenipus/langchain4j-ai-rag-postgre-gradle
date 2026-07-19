package com.example.chat.config.etl.readers;

import dev.langchain4j.data.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.Set;

/**
 * 확장자 하나를 담당하는 문서 리더 공통 인터페이스.
 *
 * <p>새 확장자를 지원하려면 이 인터페이스를 구현하는 {@code @Component}를 하나 추가하고
 * {@code document.allowed-upload-extensions}에 확장자를 추가하면 된다.
 * {@link EgovDocumentScanner}가 {@code document.upload-dir}를 한 번 스캔해 확장자별로
 * 알맞은 리더에 파일을 위임하므로, 리더별 경로 설정이나 서비스 계층 수정이 필요 없다.</p>
 */
public interface EgovDocumentReader {

    /** 이 리더가 처리하는 확장자(점 없이, 소문자). 예: {@code Set.of("pdf")} */
    Set<String> supportedExtensions();

    /** 파일 하나를 파싱해 문서(청크 이전 단위)를 생성한다. 추출할 내용이 없으면 빈 리스트를 반환한다. */
    List<Document> parse(Resource resource) throws Exception;
}

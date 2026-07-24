package com.example.chat.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 문서 ID 채번 유틸리티.
 *
 * <p>파일명만으로 ID를 만들면, 서로 다른 하위 폴더에 같은 이름의 파일이
 * 여러 개 있을 때 ID가 충돌하여 서로 다른 문서가 벡터스토어/해시 저장소에서
 * 서로를 덮어쓰는 문제가 있다. 이를 막기 위해 파일의 전체 경로를 기준으로
 * 고유한 키를 생성한다.</p>
 *
 * <p>절대경로를 그대로 쓰면 업로드 루트({@code document.upload-dir}) 위치가
 * 환경마다 달라질 때(로컬/서버) 같은 파일도 ID가 달라져 재인덱싱 시 중복 색인이
 * 발생한다. 업로드 루트 기준 상대경로로 채번해 이를 방지한다.</p>
 */
@Component
public class DocumentIdUtil {

    @Value("${document.upload-dir:}")
    private String documentUploadDir;

    public String uniquePathKey(Resource resource, String fallbackFilename) {
        String path;
        try {
            path = resource.getFile().getAbsolutePath();
            if (documentUploadDir != null && !documentUploadDir.isBlank()) {
                Path base = Paths.get(documentUploadDir).toAbsolutePath().normalize();
                Path target = Paths.get(path).toAbsolutePath().normalize();
                if (target.startsWith(base)) {
                    path = base.relativize(target).toString();
                }
            }
        } catch (IOException e) {
            path = fallbackFilename;
        }
        return path.replaceAll("[\\\\/:*?\"<>|]", "-").replaceAll("\\s+", "-");
    }

    /**
     * 파일의 마지막 수정 시각(epoch millis)을 반환한다. 실제 파일 기반이 아닌 리소스
     * (테스트용 {@code ByteArrayResource} 등)는 {@code getFile()}에서 IOException이 나므로
     * null을 반환한다 - 이 경우 재인덱싱 시 mtime 비교를 건너뛰고 항상 파싱하게 된다
     * (안전한 폴백: 못 판단하면 다시 처리).
     */
    public Long lastModifiedOrNull(Resource resource) {
        try {
            return resource.getFile().lastModified();
        } catch (IOException e) {
            return null;
        }
    }
}

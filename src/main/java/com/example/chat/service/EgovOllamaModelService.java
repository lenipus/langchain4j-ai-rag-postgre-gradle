package com.example.chat.service;

import java.util.List;
import java.util.Optional;

/**
 * Ollama 모델 관리 서비스 인터페이스
 */
public interface EgovOllamaModelService {

    /**
     * 설치된 Ollama 모델 목록 조회
     *
     * @return 모델명 리스트
     */
    List<String> getInstalledModels();

    /**
     * Ollama 서비스 사용 가능 여부 확인
     *
     * @return Ollama 서비스 사용 가능 여부
     */
    boolean isOllamaAvailable();

    /**
     * 해당 모델이 실제로 지원하는 최대 컨텍스트 길이(토큰 수)를 조회한다. 모델마다 실제
     * 한계가 다르므로(예: gemma2:2b는 8192, qwen3:4b는 40960), {@code num_ctx}를 모델별로
     * 적절히 잡아주기 위해 쓰인다. 조회 실패(원격 모드, 네트워크 오류, 알 수 없는 모델 등)
     * 시 {@link Optional#empty()}를 반환한다.
     *
     * @param modelName 조회할 모델명
     * @return 모델의 최대 컨텍스트 길이, 알 수 없으면 empty
     */
    Optional<Integer> getContextLength(String modelName);
}

package com.example.chat.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
@Slf4j
public class EgovWebController {

    // local/remote 등 여러 프로파일로 동시에 띄워놓고 브라우저 탭을 여러 개 열어두면
    // 어느 탭이 어느 서버인지 헷갈리길래, 탭 제목에 활성 프로파일을 표시하려고 주입한다.
    private final Environment environment;

    // 업로드 화면의 안내 문구/클라이언트 검증이 서버(spring.servlet.multipart) 설정과
    // 어긋나지 않도록, 같은 설정값을 뷰에 전달해 chat.html이 하드코딩하지 않도록 한다.
    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size}")
    private DataSize maxRequestSize;

    // 업로드 허용 확장자도 같은 이유로 서버 설정(document.allowed-upload-extensions)을
    // 그대로 뷰에 전달한다 - chat.html/EgovDocumentServiceImpl이 각자 하드코딩하지 않도록.
    @Value("${document.allowed-upload-extensions:.md,.pdf,.docx}")
    private String allowedUploadExtensions;

    // false면 화면에서 "SQL 생성" 탭 자체를 안 보여준다.
    @Value("${sqlgen.enabled:false}")
    private boolean sqlgenEnabled;

    /**
     * 채팅 페이지 제공
     */
    @GetMapping("/")
    public String chatPage(Model model) {
        model.addAttribute("maxFileSizeMb", maxFileSize.toMegabytes());
        model.addAttribute("maxRequestSizeMb", maxRequestSize.toMegabytes());
        model.addAttribute("allowedUploadExtensions", allowedUploadExtensions);
        model.addAttribute("activeProfile", resolveActiveProfile());
        model.addAttribute("sqlgenEnabled", sqlgenEnabled);
        return "chat";
    }

    /**
     * 탭 제목에 보여줄 프로파일 이름. 여러 개 활성화된 경우 첫 번째만 쓰고, 하나도 없으면
     * (application.yml의 spring.profiles.active 기본값과 동일하게) "local"로 표시한다.
     */
    private String resolveActiveProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0 ? activeProfiles[0] : "local";
    }
}

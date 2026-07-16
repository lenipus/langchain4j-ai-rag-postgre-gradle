package com.example.chat.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
@Slf4j
public class EgovWebController {

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

    /**
     * 채팅 페이지 제공
     */
    @GetMapping("/")
    public String chatPage(Model model) {
        model.addAttribute("maxFileSizeMb", maxFileSize.toMegabytes());
        model.addAttribute("maxRequestSizeMb", maxRequestSize.toMegabytes());
        model.addAttribute("allowedUploadExtensions", allowedUploadExtensions);
        return "chat";
    }
}

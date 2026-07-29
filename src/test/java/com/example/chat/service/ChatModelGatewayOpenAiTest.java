package com.example.chat.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatGPT(OpenAI 실제 API) 연결 - {@link ChatModelGateway#getOpenAiModelOption()}과
 * "chatgpt:" 접두사 모델명이 remote LLM 서버 설정과 무관하게 OpenAI 클라이언트로 빌드되는지 검증한다.
 */
class ChatModelGatewayOpenAiTest {

    private ChatModelGateway newGateway(boolean enabled, String apiKey) {
        ChatModelGateway gateway = new ChatModelGateway();
        ReflectionTestUtils.setField(gateway, "openAiEnabled", enabled);
        ReflectionTestUtils.setField(gateway, "openAiApiKey", apiKey);
        ReflectionTestUtils.setField(gateway, "openAiModelName", "gpt-4o");
        ReflectionTestUtils.setField(gateway, "openAiTemperature", 0.3);
        ReflectionTestUtils.setField(gateway, "openAiTimeout", Duration.ofSeconds(120));
        return gateway;
    }

    @Test
    @DisplayName("enabled=false면 목록에 노출하지 않는다")
    void hidesOptionWhenDisabled() {
        ChatModelGateway gateway = newGateway(false, "sk-test");

        assertThat(gateway.getOpenAiModelOption()).isEmpty();
    }

    @Test
    @DisplayName("api-key가 비어있으면 목록에 노출하지 않는다")
    void hidesOptionWhenApiKeyBlank() {
        ChatModelGateway gateway = newGateway(true, "");

        assertThat(gateway.getOpenAiModelOption()).isEmpty();
    }

    @Test
    @DisplayName("enabled=true이고 api-key가 있으면 chatgpt: 접두사가 붙은 모델 식별자를 반환한다")
    void exposesOptionWhenEnabledAndKeyPresent() {
        ChatModelGateway gateway = newGateway(true, "sk-test");

        assertThat(gateway.getOpenAiModelOption()).contains("chatgpt:gpt-4o");
    }

    @Test
    @DisplayName("getStreamingModel(\"chatgpt:...\")는 remote LLM 서버 설정과 무관하게 OpenAiStreamingChatModel을 만든다")
    void buildsOpenAiStreamingModelForChatgptPrefix() {
        ChatModelGateway gateway = newGateway(true, "sk-test");
        // remote LLM 서버 관련 필드는 일부러 설정 안 함(null) - chatgpt: 분기가 이 값들을
        // 건드리지 않아야 함을 함께 검증한다.

        StreamingChatModel model = gateway.getStreamingModel("chatgpt:gpt-4o");

        assertThat(model).isInstanceOf(OpenAiStreamingChatModel.class);
    }

    @Test
    @DisplayName("getChatModel(\"chatgpt:...\")도 동일하게 OpenAiChatModel을 만든다")
    void buildsOpenAiChatModelForChatgptPrefix() {
        ChatModelGateway gateway = newGateway(true, "sk-test");

        ChatModel model = gateway.getChatModel("chatgpt:gpt-4o");

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }
}

package com.example.chat;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.example.chat.config.EgovLangChain4jConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class Langchain4jRagApplicationTests {

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private ChatModel chatLanguageModel;

    @MockitoBean
    private StreamingChatModel streamingChatLanguageModel;

    @MockitoBean
    private EmbeddingStore<TextSegment> embeddingStore;

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public static BeanFactoryPostProcessor removeRealConfig() {
            return (ConfigurableListableBeanFactory beanFactory) -> {
                if (beanFactory instanceof BeanDefinitionRegistry registry) {
                    for (String name : beanFactory.getBeanNamesForType(EgovLangChain4jConfig.class, false, false)) {
                        registry.removeBeanDefinition(name);
                    }
                }
            };
        }
    }
}

package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 기본 컴포넌트/엔티티/리포지토리 스캔은 이 클래스가 속한 패키지({@code com.example.chat})
 * 이하만 대상으로 한다. SQL 생성 도구(com.example.sqlgen)는 별도 패키지라 자동으로는
 * 스캔되지 않으므로, 아래 세 애노테이션으로 명시적으로 범위를 넓혀준다.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.example.chat", "com.example.sqlgen"})
@EntityScan(basePackages = {"com.example.chat.entity", "com.example.sqlgen.entity"})
@EnableJpaRepositories(basePackages = {"com.example.chat.repository", "com.example.sqlgen.repository"})
public class Langchain4jRagApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(Langchain4jRagApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(Langchain4jRagApplication.class);
    }
}

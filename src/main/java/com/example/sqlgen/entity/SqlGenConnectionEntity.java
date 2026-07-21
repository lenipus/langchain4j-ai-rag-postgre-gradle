package com.example.sqlgen.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SQL 생성 기능에서 접속 대상으로 등록해두는 JDBC 연결 정보.
 *
 * <p>비밀번호는 {@code SqlGenPasswordEncryptor}로 AES/GCM 암호화한 뒤 저장한다.
 * 화면/응답(DTO)에는 절대 비밀번호를 내려주지 않는다.</p>
 */
@Entity
@Table(name = "sql_gen_connections")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SqlGenConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "jdbc_url", nullable = false, length = 500)
    private String jdbcUrl;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    /** AES/GCM 암호화 후 Base64 인코딩된 값 (IV 포함). */
    @Column(name = "password", nullable = false, length = 500)
    private String password;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public SqlGenConnectionEntity(String name, String jdbcUrl, String username, String password) {
        this.name = name;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }
}

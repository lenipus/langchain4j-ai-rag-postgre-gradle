package com.example.sqlgen.dto;

/**
 * 등록된 DB 연결 정보 응답용 DTO. 비밀번호는 절대 내려주지 않는다.
 */
public record SqlGenConnectionDto(Long id, String name, String jdbcUrl, String username) {
}

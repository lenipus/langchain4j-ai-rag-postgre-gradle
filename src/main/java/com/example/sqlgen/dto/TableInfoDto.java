package com.example.sqlgen.dto;

/**
 * 테이블 목록 조회 결과 하나. {@code comment}는 DB에 등록된 테이블 설명(코멘트)으로,
 * JDBC 드라이버/DBMS에 따라 지원되지 않으면 null일 수 있다.
 */
public record TableInfoDto(String name, String comment) {
}

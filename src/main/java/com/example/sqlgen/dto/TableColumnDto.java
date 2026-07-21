package com.example.sqlgen.dto;

/**
 * 테이블 스키마 조회 시 컬럼 하나의 정보. LLM에게 SQL 생성 컨텍스트로 넘길 때 쓰인다.
 */
public record TableColumnDto(String name, String dataType, boolean nullable, boolean primaryKey) {
}

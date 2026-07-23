package com.example.sqlgen.dto;

/**
 * 테이블 스키마 조회 시 컬럼 하나의 정보. LLM에게 SQL 생성 컨텍스트로 넘길 때 쓰인다.
 *
 * @param comment 컬럼 코멘트(DB에 등록돼 있으면). 영문 컬럼명만으로는 의미가 불분명해서
 *                LLM이 엉뚱한 컬럼을 고를 수 있어, 있으면 함께 넘긴다. 없으면 null.
 */
public record TableColumnDto(String name, String dataType, boolean nullable, boolean primaryKey, String comment) {
}

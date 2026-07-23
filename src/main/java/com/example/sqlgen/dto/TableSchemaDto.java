package com.example.sqlgen.dto;

import java.util.List;

/**
 * @param tableComment 테이블 코멘트(DB에 등록돼 있으면). 없으면 null.
 */
public record TableSchemaDto(String tableName, String tableComment, List<TableColumnDto> columns) {
}

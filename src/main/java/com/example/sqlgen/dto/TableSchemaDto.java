package com.example.sqlgen.dto;

import java.util.List;

public record TableSchemaDto(String tableName, List<TableColumnDto> columns) {
}

package com.example.sqlgen.dto;

import java.util.List;

public record GenerateSqlRequest(Long connectionId, List<String> tableNames, String naturalLanguageRequest) {
}

package com.example.sqlgen.dto;

public record RegisterSqlGenConnectionRequest(String name, String jdbcUrl, String username, String password) {
}

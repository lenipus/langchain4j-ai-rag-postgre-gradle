package com.example.sqlgen.controller;

import com.example.sqlgen.dto.RegisterSqlGenConnectionRequest;
import com.example.sqlgen.dto.SqlGenConnectionDto;
import com.example.sqlgen.dto.SqlStreamTokenDto;
import com.example.sqlgen.dto.TableSchemaDto;
import com.example.sqlgen.dto.TestConnectionRequest;
import com.example.sqlgen.service.SqlGenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sqlgen")
@RequiredArgsConstructor
public class SqlGenController {

    private final SqlGenService sqlGenService;

    @PostMapping("/test-connection")
    public ResponseEntity<?> testConnection(@RequestBody TestConnectionRequest request) {
        try {
            sqlGenService.testConnection(request.jdbcUrl(), request.username(), request.password());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/connections")
    public ResponseEntity<?> registerConnection(@RequestBody RegisterSqlGenConnectionRequest request) {
        try {
            SqlGenConnectionDto saved = sqlGenService.registerConnection(request);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/connections")
    public List<SqlGenConnectionDto> listConnections() {
        return sqlGenService.listConnections();
    }

    @DeleteMapping("/connections/{connectionId}")
    public ResponseEntity<Void> deleteConnection(@PathVariable Long connectionId) {
        sqlGenService.deleteConnection(connectionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/connections/{connectionId}/tables")
    public ResponseEntity<?> listTables(@PathVariable Long connectionId) {
        try {
            return ResponseEntity.ok(sqlGenService.listTables(connectionId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/connections/{connectionId}/tables/{tableName}/schema")
    public ResponseEntity<?> getTableSchema(@PathVariable Long connectionId, @PathVariable String tableName) {
        try {
            TableSchemaDto schema = sqlGenService.getTableSchema(connectionId, tableName);
            return ResponseEntity.ok(schema);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 채팅 화면과 동일한 SSE 스트리밍 방식. EventSource는 GET만 지원하므로 쿼리 파라미터로 받는다.
     * 에러가 나면 스트림 자체가 에러로 종료되고, 화면에서는 EventSource의 onerror로 감지한다
     * (이 프로젝트의 다른 스트리밍 엔드포인트(/ai/rag/stream)와 동일한 패턴).
     */
    @GetMapping(value = "/generate/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<SqlStreamTokenDto> generateSqlStream(
            @RequestParam Long connectionId,
            @RequestParam List<String> tableNames,
            @RequestParam String naturalLanguageRequest) {
        log.info("SQL 생성 스트리밍 요청 수신 - 연결: {}, 테이블: {}", connectionId, tableNames);
        return sqlGenService.generateSqlStream(connectionId, tableNames, naturalLanguageRequest)
                .map(SqlStreamTokenDto::new);
    }
}

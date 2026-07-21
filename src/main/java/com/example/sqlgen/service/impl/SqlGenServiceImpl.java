package com.example.sqlgen.service.impl;

import com.example.sqlgen.dto.RegisterSqlGenConnectionRequest;
import com.example.sqlgen.dto.SqlGenConnectionDto;
import com.example.sqlgen.dto.TableColumnDto;
import com.example.sqlgen.dto.TableInfoDto;
import com.example.sqlgen.dto.TableSchemaDto;
import com.example.sqlgen.entity.SqlGenConnectionEntity;
import com.example.sqlgen.repository.SqlGenConnectionRepository;
import com.example.sqlgen.service.SqlGenService;
import com.example.sqlgen.util.SqlGenPasswordEncryptor;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGenServiceImpl implements SqlGenService {

    /** getTables()/getColumns() 조회 시 노이즈만 되는 시스템 스키마 제외 (Postgres 기준). */
    private static final Set<String> EXCLUDED_SCHEMAS = Set.of("pg_catalog", "information_schema");

    private final SqlGenConnectionRepository connectionRepository;
    private final ChatModel queryCompressionChatModel;
    private final SqlGenPasswordEncryptor passwordEncryptor;

    @Override
    public void testConnection(String jdbcUrl, String username, String password) {
        try (Connection ignored = openConnection(jdbcUrl, username, password)) {
            log.info("SQL 생성용 DB 연결 테스트 성공 - {}", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalArgumentException("DB 접속에 실패했습니다: " + e.getMessage(), e);
        }
    }

    @Override
    public SqlGenConnectionDto registerConnection(RegisterSqlGenConnectionRequest request) {
        // 저장 전에 실제로 접속되는지 먼저 확인한다 - 잘못된 정보가 등록되면 나중에 쓸 때마다 헷갈리므로.
        testConnection(request.jdbcUrl(), request.username(), request.password());

        SqlGenConnectionEntity entity = new SqlGenConnectionEntity(
                request.name(), request.jdbcUrl(), request.username(), passwordEncryptor.encrypt(request.password()));
        entity = connectionRepository.save(entity);
        return toDto(entity);
    }

    @Override
    public List<SqlGenConnectionDto> listConnections() {
        return connectionRepository.findAllByOrderByNameAsc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteConnection(Long connectionId) {
        connectionRepository.deleteById(connectionId);
    }

    @Override
    public List<TableInfoDto> listTables(Long connectionId) {
        SqlGenConnectionEntity connectionInfo = getConnectionOrThrow(connectionId);

        try (Connection conn = openConnectionFor(connectionInfo)) {
            DatabaseMetaData metaData = conn.getMetaData();
            List<TableInfoDto> tables = new ArrayList<>();
            // REMARKS(테이블 코멘트)는 JDBC 드라이버/DBMS에 따라 지원 여부가 달라서
            // 안 나오면 null로 둔다 - 화면에서 없으면 그냥 이름만 보여준다.
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String table = rs.getString("TABLE_NAME");
                    if (schema != null && EXCLUDED_SCHEMAS.contains(schema)) {
                        continue;
                    }
                    String displayName = schema != null ? schema + "." + table : table;
                    String comment = rs.getString("REMARKS");
                    tables.add(new TableInfoDto(displayName, (comment != null && !comment.isBlank()) ? comment : null));
                }
            }
            return tables;
        } catch (SQLException e) {
            throw new IllegalStateException("테이블 목록 조회에 실패했습니다: " + e.getMessage(), e);
        }
    }

    @Override
    public TableSchemaDto getTableSchema(Long connectionId, String tableName) {
        SqlGenConnectionEntity connectionInfo = getConnectionOrThrow(connectionId);

        String schema = null;
        String table = tableName;
        int dotIndex = tableName.indexOf('.');
        if (dotIndex >= 0) {
            schema = tableName.substring(0, dotIndex);
            table = tableName.substring(dotIndex + 1);
        }

        try (Connection conn = openConnectionFor(connectionInfo)) {
            return fetchTableSchema(conn, schema, table, tableName);
        } catch (SQLException e) {
            throw new IllegalStateException("테이블 스키마 조회에 실패했습니다: " + e.getMessage(), e);
        }
    }

    @Override
    public String generateSql(Long connectionId, List<String> tableNames, String naturalLanguageRequest) {
        SqlGenConnectionEntity connectionInfo = getConnectionOrThrow(connectionId);

        List<TableSchemaDto> schemas = new ArrayList<>();
        try (Connection conn = openConnectionFor(connectionInfo)) {
            for (String tableName : tableNames) {
                String schema = null;
                String table = tableName;
                int dotIndex = tableName.indexOf('.');
                if (dotIndex >= 0) {
                    schema = tableName.substring(0, dotIndex);
                    table = tableName.substring(dotIndex + 1);
                }
                schemas.add(fetchTableSchema(conn, schema, table, tableName));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("테이블 스키마 조회에 실패했습니다: " + e.getMessage(), e);
        }

        String prompt = buildPrompt(schemas, naturalLanguageRequest);
        log.info("SQL 생성 요청 - 테이블: {}, 요청: {}", tableNames, naturalLanguageRequest);

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();
        String rawResponse = queryCompressionChatModel.chat(chatRequest).aiMessage().text();
        String sql = extractSql(rawResponse);

        log.info("SQL 생성 결과: {}", sql);
        return sql;
    }

    private SqlGenConnectionEntity getConnectionOrThrow(Long connectionId) {
        return connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 연결 정보입니다: " + connectionId));
    }

    private Connection openConnection(String jdbcUrl, String username, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /** 저장된 연결 정보로 접속한다 - 비밀번호는 암호화되어 저장돼 있으므로 복호화한 뒤 사용한다. */
    private Connection openConnectionFor(SqlGenConnectionEntity connectionInfo) throws SQLException {
        String password = passwordEncryptor.decrypt(connectionInfo.getPassword());
        return openConnection(connectionInfo.getJdbcUrl(), connectionInfo.getUsername(), password);
    }

    private TableSchemaDto fetchTableSchema(Connection conn, String schema, String table, String displayName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();

        Set<String> primaryKeyColumns = new HashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                primaryKeyColumns.add(rs.getString("COLUMN_NAME"));
            }
        }

        List<TableColumnDto> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                columns.add(new TableColumnDto(
                        columnName,
                        rs.getString("TYPE_NAME"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        primaryKeyColumns.contains(columnName)));
            }
        }

        return new TableSchemaDto(displayName, columns);
    }

    /**
     * 테이블 스키마들을 LLM이 이해할 수 있는 텍스트로 정리해 SQL 생성 프롬프트를 만든다.
     * 설명 없이 SQL만 출력하도록 명시적으로 지시한다 - 그렇지 않으면 로컬 모델이 추론
     * 과정을 같이 출력해버리는 경우가 있었다(RAG 질의 압축에서 실제로 겪은 문제).
     */
    String buildPrompt(List<TableSchemaDto> schemas, String naturalLanguageRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 SQL 전문가입니다. 아래 테이블 스키마를 참고하여 사용자의 요청에 맞는 SQL 쿼리를 작성하세요.\n");
        sb.append("다른 설명이나 추론 과정 없이 SQL 쿼리 하나만 출력하세요. 코드블록(```)도 쓰지 마세요.\n");
        sb.append("SELECT/FROM/WHERE/GROUP BY/ORDER BY 등 절마다 줄을 바꾸고 들여쓰기를 사용해, 읽기 좋게 줄을 맞춰서 작성하세요.\n\n");

        for (TableSchemaDto schema : schemas) {
            sb.append("[테이블: ").append(schema.tableName()).append("]\n");
            for (TableColumnDto column : schema.columns()) {
                sb.append("- ").append(column.name())
                        .append(" (").append(column.dataType())
                        .append(column.primaryKey() ? ", PK" : "")
                        .append(column.nullable() ? ", NULL 허용" : ", NOT NULL")
                        .append(")\n");
            }
            sb.append("\n");
        }

        sb.append("사용자 요청: ").append(naturalLanguageRequest).append("\n\n");
        sb.append("SQL:");
        return sb.toString();
    }

    /**
     * LLM 응답에서 SQL만 추출한다. 코드블록(```sql ... ``` 또는 ``` ... ```)으로 감쌌으면
     * 그 안쪽만, "SQL:" 같은 표시가 있으면 그 뒤부터 취한다. 둘 다 없으면 트림한 원문 그대로.
     */
    String extractSql(String rawResponse) {
        if (rawResponse == null) {
            return "";
        }
        String text = rawResponse.trim();

        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            int fenceEnd = text.indexOf("```", fenceStart + 3);
            if (contentStart >= 0 && fenceEnd > contentStart) {
                return text.substring(contentStart + 1, fenceEnd).trim();
            }
        }

        int sqlMarkerIndex = text.lastIndexOf("SQL:");
        if (sqlMarkerIndex >= 0) {
            return text.substring(sqlMarkerIndex + "SQL:".length()).trim();
        }

        return text;
    }

    private SqlGenConnectionDto toDto(SqlGenConnectionEntity entity) {
        return new SqlGenConnectionDto(entity.getId(), entity.getName(), entity.getJdbcUrl(), entity.getUsername());
    }
}

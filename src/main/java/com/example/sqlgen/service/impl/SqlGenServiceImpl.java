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
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
    private final StreamingChatModel sqlGenStreamingChatModel;
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
    public Flux<String> generateSqlStream(Long connectionId, List<String> tableNames, String naturalLanguageRequest) {
        // 스키마 조회 같은 블로킹 작업도 구독 시점에 실행되도록 defer로 감싼다 - 그래야
        // 여기서 던지는 예외(연결 없음, DB 접속 실패 등)도 동기적으로 튀지 않고 스트림의
        // 에러 시그널로 정상 전달된다.
        return Flux.defer(() -> {
            SqlGenConnectionEntity connectionInfo = getConnectionOrThrow(connectionId);

            String dbProductName;
            List<TableSchemaDto> schemas;
            try (Connection conn = openConnectionFor(connectionInfo)) {
                dbProductName = conn.getMetaData().getDatabaseProductName();
                schemas = fetchSchemas(conn, tableNames);
            } catch (SQLException e) {
                throw new IllegalStateException("테이블 스키마 조회에 실패했습니다: " + e.getMessage(), e);
            }

            String prompt = buildPrompt(schemas, naturalLanguageRequest, dbProductName);
            log.info("SQL 생성(스트리밍) 요청 - DBMS: {}, 테이블: {}, 요청: {}", dbProductName, tableNames, naturalLanguageRequest);

            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(SystemMessage.from(SqlGenService.SQL_GEN_SYSTEM_PROMPT), UserMessage.from(prompt))
                    .build();

            StringBuilder fullResponse = new StringBuilder();
            return Flux.<String>create(sink -> sqlGenStreamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    fullResponse.append(partialResponse);
                    sink.next(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    log.info("SQL 생성(스트리밍) 결과: {}", extractSql(fullResponse.toString()));
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    sink.error(error);
                }
            }));
        });
    }

    private List<TableSchemaDto> fetchSchemas(Connection conn, List<String> tableNames) throws SQLException {
        List<TableSchemaDto> schemas = new ArrayList<>();
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
        return schemas;
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
     * 매 요청마다 달라지는 부분(대상 DBMS, 테이블 스키마, 사용자 요청)만 User 메시지로 만든다.
     * 항상 같은 절차적 지시사항은 {@link SqlGenService#SQL_GEN_SYSTEM_PROMPT}(System 메시지)에
     * 이미 들어있으므로 여기서 반복하지 않는다.
     *
     * <p>대상 DBMS 이름을 명시적으로 알려준다 - 안 알려주면 모델이 어떤 DBMS인지 몰라서
     * 엉뚱한 방언(예: MariaDB 연결인데 MSSQL 문법)으로 SQL을 만들어버리는 문제가 있었다.
     * 이 값은 JDBC 연결의 {@code DatabaseMetaData.getDatabaseProductName()}에서 그대로
     * 가져온 실제 값이라 등록된 연결이 어떤 DB인지와 항상 일치한다.</p>
     *
     * <p>실측 테스트 결과 영어/한글 간 SQL 결과물 차이는 없었고, 사용자가 한국어
     * 사용자라 한글로 되돌림 (자세한 이유는 {@link SqlGenService#SQL_GEN_SYSTEM_PROMPT}
     * 참고).</p>
     */
    String buildPrompt(List<TableSchemaDto> schemas, String naturalLanguageRequest, String dbmsName) {
        StringBuilder sb = new StringBuilder();
        sb.append("대상 DBMS는 ").append(dbmsName).append("입니다. 반드시 이 DBMS의 SQL 문법과 함수만 사용하세요")
                .append(" (다른 DBMS의 문법이나 함수를 섞어 쓰지 마세요).\n\n");

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

        sb.append("사용자 요청: ").append(naturalLanguageRequest);
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

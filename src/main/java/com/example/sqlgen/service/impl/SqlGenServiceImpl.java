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

            try (ResultSet rs = metaData.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
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
    public String buildSchemaContext(Long connectionId, List<String> tableNames) {
        SqlGenConnectionEntity connectionInfo = getConnectionOrThrow(connectionId);

        String dbProductName;
        List<TableSchemaDto> schemas;
        try (Connection conn = openConnectionFor(connectionInfo)) {
            dbProductName = conn.getMetaData().getDatabaseProductName();
            schemas = fetchSchemas(conn, tableNames);
        } catch (SQLException e) {
            throw new IllegalStateException("테이블 스키마 조회에 실패했습니다: " + e.getMessage(), e);
        }

        log.info("SQL 생성 스키마 컨텍스트 조회 - DBMS: {}, 테이블: {}", dbProductName, tableNames);
        return formatSchemaBlock(schemas, dbProductName);
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

        String catalog = conn.getCatalog();

        Set<String> primaryKeyColumns = new HashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primaryKeyColumns.add(rs.getString("COLUMN_NAME"));
            }
        }

        String tableComment = null;
        try (ResultSet rs = metaData.getTables(catalog, schema, table, new String[]{"TABLE", "VIEW"})) {
            if (rs.next()) {
                String remarks = rs.getString("REMARKS");
                tableComment = (remarks != null && !remarks.isBlank()) ? remarks : null;
            }
        }

        List<TableColumnDto> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String remarks = rs.getString("REMARKS");
                columns.add(new TableColumnDto(
                        columnName,
                        rs.getString("TYPE_NAME"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        primaryKeyColumns.contains(columnName),
                        (remarks != null && !remarks.isBlank()) ? remarks : null));
            }
        }

        return new TableSchemaDto(displayName, tableComment, columns);
    }

    /**
     * 대상 DBMS + 테이블 스키마를 텍스트로 만든다. 항상 같은 절차적 지시사항은
     * {@link SqlGenService#SQL_GEN_SYSTEM_PROMPT}(System 메시지)에 이미 들어있으므로 여기서
     * 반복하지 않는다.
     */
    String formatSchemaBlock(List<TableSchemaDto> schemas, String dbmsName) {
        StringBuilder sb = new StringBuilder();
        sb.append("대상 DBMS는 ").append(dbmsName).append("입니다. 반드시 이 DBMS의 SQL 문법과 함수만 사용하세요")
                .append(" (다른 DBMS의 문법이나 함수를 섞어 쓰지 마세요).\n\n");

        for (TableSchemaDto schema : schemas) {
            sb.append("[테이블: ").append(schema.tableName());
            if (schema.tableComment() != null) {
                sb.append(" - ").append(schema.tableComment());
            }
            sb.append("]\n");
            for (TableColumnDto column : schema.columns()) {
                sb.append("- ").append(column.name())
                        .append(" (").append(column.dataType())
                        .append(column.primaryKey() ? ", PK" : "")
                        .append(column.nullable() ? ", NULL 허용" : ", NOT NULL")
                        .append(")");
                if (column.comment() != null) {
                    sb.append(" - ").append(column.comment());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    private SqlGenConnectionDto toDto(SqlGenConnectionEntity entity) {
        return new SqlGenConnectionDto(entity.getId(), entity.getName(), entity.getJdbcUrl(), entity.getUsername());
    }
}

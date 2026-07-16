package com.example.chat.config;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DynamicTableInspector implements StatementInspector {

    private static String targetTableName;

    // @Value는 static 변수에 직접 주입되지 않으므로 Setter 메서드를 통해 주입합니다.
    @Value("${pgvector.hash-table-name:document_hashes}")
    public void setTargetTableName(String tableName) {
        DynamicTableInspector.targetTableName = tableName;
    }

    @Override
    public String inspect(String sql) {
        // SQL문 안에 엔티티 기본 테이블명인 "document_hashes"가 포함되어 있다면
        if (sql != null && sql.contains("document_hashes") && targetTableName != null) {
            // yml에 정의한 테이블명(예: document_hashes_bgem3)으로 강제 변환하여 실행합니다.
            return sql.replace("document_hashes", targetTableName);
        }
        return sql;
    }
}


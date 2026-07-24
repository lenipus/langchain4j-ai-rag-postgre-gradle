package com.example.chat.config;

// 더 이상 쓰지 않음 - com.example.chat.config.DynamicTableNamingStrategy로 대체됨.
//
// 이 StatementInspector 방식은 Hibernate가 만든 SQL 문자열을 실행 직전에 문자열 치환하는
// 방식이라, DML(SELECT/INSERT 등)에는 적용되지만 ddl-auto(스키마 자동 생성/갱신)가 만드는
// DDL(CREATE/ALTER TABLE)에는 적용되지 않는다 - 그래서 document_hashes 엔티티에 컬럼을
// 추가했을 때, DDL은 엔티티에 고정된 이름(document_hashes)에만 적용되고 실제 조회는
// bgem3 프로파일의 물리 테이블(document_hashes_bgem3)로 나가면서 "컬럼이 없다"는 불일치가
// 발생했다. 나중에 비슷한 문제가 다시 필요해질 경우를 대비해 원본을 주석으로 남겨둔다.
//
// import org.hibernate.resource.jdbc.spi.StatementInspector;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.stereotype.Component;
//
// @Component
// public class DynamicTableInspector implements StatementInspector {
//
//     private static String targetTableName;
//
//     // @Value는 static 변수에 직접 주입되지 않으므로 Setter 메서드를 통해 주입합니다.
//     @Value("${pgvector.hash-table-name:document_hashes}")
//     public void setTargetTableName(String tableName) {
//         DynamicTableInspector.targetTableName = tableName;
//     }
//
//     @Override
//     public String inspect(String sql) {
//         // SQL문 안에 엔티티 기본 테이블명인 "document_hashes"가 포함되어 있다면
//         if (sql != null && sql.contains("document_hashes") && targetTableName != null) {
//             // yml에 정의한 테이블명(예: document_hashes_bgem3)으로 강제 변환하여 실행합니다.
//             return sql.replace("document_hashes", targetTableName);
//         }
//         return sql;
//     }
// }

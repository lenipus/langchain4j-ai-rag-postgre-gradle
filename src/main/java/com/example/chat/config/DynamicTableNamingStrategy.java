package com.example.chat.config;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link com.example.chat.entity.DocumentHashEntity}가 {@code @Table(name = "document_hashes")}로
 * 고정 매핑돼 있지만, 실제로는 활성 임베딩 프로파일(gemma/bgem3)에 따라 물리적으로 다른
 * 테이블({@code document_hashes} / {@code document_hashes_bgem3})을 써야 한다. 이 클래스가
 * Hibernate 메타데이터 빌드 시점에 논리 테이블 이름을 설정된 실제 테이블 이름으로 바꿔준다.
 *
 * <p>지금은 매핑이 하나(document_hashes)뿐이지만, 나중에 같은 이유로 다른 엔티티도 프로파일별
 * 물리 테이블이 필요해지면 {@link #tableNameOverrides} 맵에 한 줄만 추가하면 된다 - 생성자에
 * {@code @Value} 파라미터를 하나 더 받아서 맵에 넣어주면 된다.</p>
 */
@Component
public class DynamicTableNamingStrategy extends PhysicalNamingStrategyStandardImpl {

    /** 논리 테이블명(엔티티의 @Table 값) -> 실제 물리 테이블명. */
    private final Map<String, String> tableNameOverrides;

    public DynamicTableNamingStrategy(
            @Value("${pgvector.hash-table-name:document_hashes}") String actualHashTableName) {
        this.tableNameOverrides = Map.of(
                "document_hashes", actualHashTableName
        );
    }

    @Override
    public Identifier toPhysicalTableName(Identifier logicalName, JdbcEnvironment context) {
        if (logicalName != null) {
            String override = tableNameOverrides.get(logicalName.getText());
            if (override != null) {
                return Identifier.toIdentifier(override);
            }
        }
        return super.toPhysicalTableName(logicalName, context);
    }
}

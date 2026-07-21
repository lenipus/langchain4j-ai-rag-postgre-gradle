package com.example.sqlgen.repository;

import com.example.sqlgen.entity.SqlGenConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SqlGenConnectionRepository extends JpaRepository<SqlGenConnectionEntity, Long> {

    List<SqlGenConnectionEntity> findAllByOrderByNameAsc();
}

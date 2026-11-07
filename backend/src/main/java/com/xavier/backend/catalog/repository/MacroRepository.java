package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.Macro;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MacroRepository extends JpaRepository<Macro, UUID> {

    List<Macro> findAllByOrderByCode();
}

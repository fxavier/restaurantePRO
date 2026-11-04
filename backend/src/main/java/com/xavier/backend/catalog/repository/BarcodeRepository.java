package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.Barcode;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarcodeRepository extends JpaRepository<Barcode, UUID> {

    List<Barcode> findAllByItemIdOrderByCode(UUID itemId);

    List<Barcode> findAllByItemIdIn(Collection<UUID> itemIds);
}

package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.PriceList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    Optional<PriceList> findByStoreId(UUID storeId);

    List<PriceList> findAllByStoreId(UUID storeId);
}

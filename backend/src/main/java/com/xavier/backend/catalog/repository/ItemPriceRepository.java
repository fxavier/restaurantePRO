package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.ItemPrice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemPriceRepository extends JpaRepository<ItemPrice, UUID> {

    List<ItemPrice> findAllByPriceListId(UUID priceListId);

    Optional<ItemPrice> findByIdAndPriceListId(UUID id, UUID priceListId);
}

package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.ItemAvailability;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findAllByAvailabilityOrderByCode(ItemAvailability availability);
}

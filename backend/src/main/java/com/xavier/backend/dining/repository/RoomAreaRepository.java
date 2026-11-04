package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.RoomArea;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RoomAreaRepository extends JpaRepository<RoomArea, UUID> {

    Page<RoomArea> findByStoreId(UUID storeId, Pageable pageable);

    List<RoomArea> findByStoreIdAndActiveTrueOrderByCode(UUID storeId);

    boolean existsByStoreIdAndCode(UUID storeId, String code);

    boolean existsByStoreIdAndCodeAndIdNot(UUID storeId, String code, UUID id);

    /** {@code SELECT ... FOR SHARE}: an opening keeps the area from being deactivated until it commits. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select a from RoomArea a where a.id = :id")
    Optional<RoomArea> findForShareById(UUID id);
}

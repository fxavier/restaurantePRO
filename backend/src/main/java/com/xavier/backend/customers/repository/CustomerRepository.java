package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.Customer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** Phone suffix as a prefix scan of the reversed digits (text_pattern_ops), or an exact tax id (D72). */
    String SEARCH_BY_PHONE_SUFFIX = """
            SELECT c.* FROM customers.customer c
            JOIN (SELECT p.customer_id AS id FROM customers.customer_phone p
                  WHERE p.digits_reversed LIKE :reversedPrefix
                  UNION
                  SELECT t.id FROM customers.customer t WHERE t.tax_id = :taxId) m ON m.id = c.id
            ORDER BY c.name, c.id
            LIMIT :limit""";

    /** Name contains, on the folded name (pg_trgm GIN), or an exact tax id (D72). */
    String SEARCH_BY_NAME = """
            SELECT c.* FROM customers.customer c
            WHERE c.name_search LIKE '%' || customers.fold(:namePart) || '%' OR c.tax_id = :taxId
            ORDER BY c.name, c.id
            LIMIT :limit""";

    @Query(nativeQuery = true, value = SEARCH_BY_PHONE_SUFFIX)
    List<Customer> searchByPhoneSuffix(@Param("reversedPrefix") String reversedPrefix, @Param("taxId") String taxId,
                                       @Param("limit") int limit);

    @Query(nativeQuery = true, value = SEARCH_BY_NAME)
    List<Customer> searchByName(@Param("namePart") String namePart, @Param("taxId") String taxId,
                                @Param("limit") int limit);
}

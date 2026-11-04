package com.xavier.backend.shared.web;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable JSON shape of a page (D23): {@code {content, page, size, totalElements}}. Request side is a
 * Spring Data {@code Pageable} ({@code ?page=&size=&sort=}); size is capped at {@link #MAX_PAGE_SIZE}
 * by {@code spring.data.web.pageable.max-page-size}.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    public static final int MAX_PAGE_SIZE = 200;

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}

package com.xavier.backend.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PageResponseTest.ProbeController.class})
@WithMockUser
class PageResponseTest {

    @Autowired
    MockMvc mvc;

    @Test
    void exposesContentPageSizeAndTotal() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 7);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response).isEqualTo(new PageResponse<>(List.of("a", "b"), 1, 2, 7));
    }

    @Test
    void pageSizeIsCappedAt200() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-page-probe").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(PageResponse.MAX_PAGE_SIZE));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/backoffice/test-page-probe")
        PageResponse<String> list(Pageable pageable) {
            return PageResponse.from(new PageImpl<>(List.of(), pageable, 0));
        }
    }
}

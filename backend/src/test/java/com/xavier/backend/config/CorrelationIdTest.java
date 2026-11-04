package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CorrelationIdTest.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdTest {

    static final String HEADER = "X-Correlation-Id";

    @Autowired
    MockMvc mvc;

    @Test
    void aCallersCorrelationIdIsEchoedAndAvailableToLogs() throws Exception {
        mvc.perform(get("/api/v1/auth/correlation-probe").header(HEADER, "pos-7f3a-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, "pos-7f3a-0001"))
                .andExpect(content().string("pos-7f3a-0001"));
    }

    @Test
    void anIdIsGeneratedWhenTheCallerSendsNone() throws Exception {
        String generated = mvc.perform(get("/api/v1/auth/correlation-probe"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(generated).matches("[0-9a-f-]{36}");
    }

    @Test
    void anUnsafeIdIsReplacedSoItCannotForgeLogLines() throws Exception {
        String returned = mvc.perform(get("/api/v1/auth/correlation-probe").header(HEADER, "x\"}\n{\"level\":\"FAKE"))
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(returned).matches("[0-9a-f-]{36}");
    }

    @Test
    void evenRejectedRequestsCarryTheId() throws Exception {
        mvc.perform(get("/api/v1/backoffice/anything").header(HEADER, "trace-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HEADER, "trace-401"));
    }

    @Test
    void logLinesAreJsonWithTheCorrelationId(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/v1/auth/correlation-probe").header(HEADER, "json-log-check"));

        assertThat(output.getOut().lines().filter(line -> line.contains("probe handled")))
                .anySatisfy(line -> assertThat(line)
                        .startsWith("{").endsWith("}")
                        .contains("\"message\":\"probe handled\"")
                        .contains("\"correlationId\":\"json-log-check\""));
    }

    @RestController
    static class ProbeController {

        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

        @GetMapping("/api/v1/auth/correlation-probe")
        String probe() {
            log.info("probe handled");
            return MDC.get("correlationId");
        }
    }
}

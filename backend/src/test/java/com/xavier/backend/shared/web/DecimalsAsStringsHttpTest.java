package com.xavier.backend.shared.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FE-F33: JavaScript loses a JSON number beyond ~15 significant digits and amounts allow 17, so every BigDecimal is
 * written as a string holding the plain decimal (scale kept, no exponent); a request accepts a string or a number.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DecimalsAsStringsHttpTest.ProbeController.class})
@WithMockUser
class DecimalsAsStringsHttpTest {

    private static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Test
    void aMoneyAmountIsWrittenAsAStringWithItsScale() throws Exception {
        mvc.perform(get("/api/v1/backoffice/decimal-probe/thirty-cents"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"amount\":\"0.30\",\"currency\":\"MZN\"}"));
    }

    @Test
    void aDecimalIsWrittenPlainNeverWithAnExponent() throws Exception {
        mvc.perform(get("/api/v1/backoffice/decimal-probe/exponent"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"rate\":\"1600\"}"));
    }

    @Test
    void aSeventeenDigitAmountSentAsAStringRoundTripsExactly() throws Exception {
        mvc.perform(post("/api/v1/backoffice/decimal-probe/echo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"1234567890123456.78\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"amount\":\"1234567890123456.78\"")));
    }

    @Test
    void anAmountSentAsAJsonNumberIsStillAccepted() throws Exception {
        mvc.perform(post("/api/v1/backoffice/decimal-probe/echo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1234567890123456.78}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"amount\":\"1234567890123456.78\"")));
        mvc.perform(post("/api/v1/backoffice/decimal-probe/echo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":12.5}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"amount\":\"12.50\"")));
    }

    @Test
    void aStringThatIsNotADecimalIsA400() throws Exception {
        mvc.perform(post("/api/v1/backoffice/decimal-probe/echo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"12,50\"}"))
                .andExpect(status().isBadRequest());
    }

    record AmountRequest(@NotNull BigDecimal amount) {
    }

    record Rate(BigDecimal rate) {
    }

    @RestController
    @RequestMapping("/api/v1/backoffice/decimal-probe")
    static class ProbeController {

        @GetMapping("/thirty-cents")
        Money thirtyCents() {
            return Money.of("0.30", MZN);
        }

        @GetMapping("/exponent")
        Rate exponent() {
            return new Rate(new BigDecimal("1.6E+3"));
        }

        @PostMapping("/echo")
        Money echo(@Valid @RequestBody AmountRequest request) {
            return Money.of(request.amount(), MZN);
        }
    }
}

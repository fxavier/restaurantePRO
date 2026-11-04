package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.PaymentProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The {@link PaymentProvider} beans by code (D53). R2 has none in production; tests add a fake. */
@Component
class PaymentProviders {

    private final Map<String, PaymentProvider> byCode;

    PaymentProviders(List<PaymentProvider> providers) {
        this.byCode = providers.stream().collect(Collectors.toUnmodifiableMap(PaymentProvider::code,
                Function.identity()));
    }

    Optional<PaymentProvider> find(String code) {
        return Optional.ofNullable(code).map(byCode::get);
    }

    boolean exists(String code) {
        return find(code).isPresent();
    }
}

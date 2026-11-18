package com.xavier.backend.config;

import java.util.Currency;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.stereotype.Component;

/**
 * Jackson writes a {@link Currency} as its ISO 4217 code ("MZN"); springdoc would otherwise describe the bean's
 * getters as an object, and the frontend's generated types (ADR 0042) would be wrong for every Money.
 */
@Component
class CurrencyAsStringDocumentation {

    CurrencyAsStringDocumentation() {
        SpringDocUtils.getConfig().replaceWithClass(Currency.class, String.class);
    }
}

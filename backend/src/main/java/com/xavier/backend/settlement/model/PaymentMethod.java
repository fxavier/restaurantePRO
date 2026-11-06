package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.dto.PaymentMethodView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A company-wide payment method (D52): data, not code. {@code CASH} is seeded and is the only method of kind CASH.
 * The code and the kind never change once created (cash groups its expected amounts by the code); the name, the
 * provider of an integrated method and the {@code active} flag do.
 */
@Entity
@Table(schema = "settlement", name = "payment_method")
public class PaymentMethod {

    public static final String CASH = "CASH";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentMethodKind kind;

    @Column(length = 50)
    private String providerCode;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PaymentMethod() {
    }

    /**
     * @throws SettlementRuleException {@code INVALID_METHOD_KIND} for a cash kind on another code than CASH (or the
     *         reverse) or a provider on a non-integrated method; {@code PROVIDER_NOT_AVAILABLE} for an integrated
     *         method without a provider
     */
    public static PaymentMethod create(String code, String name, PaymentMethodKind kind, String providerCode,
                                       Instant now) {
        Objects.requireNonNull(kind, "kind");
        if ((kind == PaymentMethodKind.CASH) != CASH.equals(code)) {
            throw new SettlementRuleException(SettlementRule.INVALID_METHOD_KIND,
                    "Only the seeded CASH method is of kind CASH.");
        }
        PaymentMethod method = new PaymentMethod();
        method.code = Objects.requireNonNull(code, "code");
        method.kind = kind;
        method.name = Objects.requireNonNull(name, "name");
        method.providerCode = requireProvider(kind, providerCode);
        method.active = true;
        method.createdAt = now;
        method.updatedAt = now;
        return method;
    }

    public void update(String name, String providerCode, boolean active, Instant now) {
        this.name = Objects.requireNonNull(name, "name");
        this.providerCode = requireProvider(kind, providerCode);
        this.active = active;
        this.updatedAt = now;
    }

    private static String requireProvider(PaymentMethodKind kind, String providerCode) {
        boolean hasProvider = providerCode != null && !providerCode.isBlank();
        if (kind == PaymentMethodKind.INTEGRATED && !hasProvider) {
            throw new SettlementRuleException(SettlementRule.PROVIDER_NOT_AVAILABLE,
                    "An integrated payment method needs a provider.");
        }
        if (kind != PaymentMethodKind.INTEGRATED && hasProvider) {
            throw new SettlementRuleException(SettlementRule.INVALID_METHOD_KIND,
                    "Only an integrated payment method has a provider.");
        }
        return hasProvider ? providerCode : null;
    }

    public PaymentMethodView toView() {
        return new PaymentMethodView(id, code, name, kind.name(), providerCode, active, version);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    /** The display name, printed on documents (D97: the seeded CASH method is "Numerário"). */
    public String getName() {
        return name;
    }

    public PaymentMethodKind getKind() {
        return kind;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }
}

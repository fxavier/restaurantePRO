package com.xavier.backend.inventory.consumption.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Whether the sales of a catalog family ({@code null} = every other family) consume stock, and when (D116). */
@Entity
@Table(schema = "inventory", name = "consumption_policy_rule")
public class ConsumptionPolicyRule {

    /** The only trigger in R4: consumption when the round is confirmed. */
    public static final String ROUND_CONFIRMED = "ROUND_CONFIRMED";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "policy_version_id", nullable = false, updatable = false)
    private ConsumptionPolicyVersion policyVersion;

    @Column(updatable = false)
    private UUID familyId;

    @Column(nullable = false, updatable = false, length = 20)
    private String trigger;

    @Column(nullable = false, updatable = false)
    private boolean tracked;

    protected ConsumptionPolicyRule() {
        // JPA
    }

    static ConsumptionPolicyRule of(ConsumptionPolicyVersion version, UUID familyId, boolean tracked) {
        ConsumptionPolicyRule rule = new ConsumptionPolicyRule();
        rule.policyVersion = version;
        rule.familyId = familyId;
        rule.trigger = ROUND_CONFIRMED;
        rule.tracked = tracked;
        return rule;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTrigger() {
        return trigger;
    }

    public boolean isTracked() {
        return tracked;
    }
}

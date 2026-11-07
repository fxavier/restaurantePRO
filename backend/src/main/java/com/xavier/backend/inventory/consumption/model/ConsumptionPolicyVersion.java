package com.xavier.backend.inventory.consumption.model;

import com.xavier.backend.inventory.consumption.model.dto.PolicyRuleView;
import com.xavier.backend.inventory.consumption.model.dto.PolicyVersionView;
import com.xavier.backend.inventory.recipe.model.EffectivePeriod;
import com.xavier.backend.inventory.recipe.model.VersionStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * A version of the consumption policy (D116), company-wide, versioned like recipes ({@link EffectivePeriod}): a line
 * uses the version in force at its confirmation. A family with no rule of its own follows the default rule; with
 * no default rule at all, it is tracked.
 */
@Entity
@Table(schema = "inventory", name = "consumption_policy_version")
public class ConsumptionPolicyVersion {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private int versionNo;

    @Embedded
    private EffectivePeriod period;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant activatedAt;

    @OneToMany(mappedBy = "policyVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConsumptionPolicyRule> rules = new ArrayList<>();

    @Version
    private Long version;

    protected ConsumptionPolicyVersion() {
        // JPA
    }

    public static ConsumptionPolicyVersion draft(int versionNo, List<PolicyRuleLine> rules, Instant now) {
        ConsumptionPolicyVersion version = new ConsumptionPolicyVersion();
        version.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        version.versionNo = versionNo;
        version.period = EffectivePeriod.draft();
        version.createdAt = now;
        version.write(rules);
        return version;
    }

    public void revise(List<PolicyRuleLine> lines) {
        period.requireDraft(id, "be edited");
        write(lines);
    }

    public void activate(Instant from, Instant now) {
        period.activate(id, from);
        activatedAt = now;
    }

    public void supersedeAt(Instant to) {
        period.supersedeAt(id, to);
    }

    /** Whether a sale of {@code familyId} consumes stock under this version. */
    public boolean tracks(UUID familyId) {
        return rules.stream().filter(rule -> familyId != null && familyId.equals(rule.getFamilyId())).findFirst()
                .or(() -> rules.stream().filter(rule -> rule.getFamilyId() == null).findFirst())
                .map(ConsumptionPolicyRule::isTracked).orElse(true);
    }

    public PolicyVersionView toView() {
        return new PolicyVersionView(id, versionNo, period.status().name(), period.effectiveFrom(),
                period.effectiveTo(), activatedAt, getVersion(), rules.stream()
                .map(r -> new PolicyRuleView(r.getFamilyId(), r.getTrigger(), r.isTracked())).toList());
    }

    private void write(List<PolicyRuleLine> lines) {
        rules.clear();
        lines.forEach(line -> rules.add(ConsumptionPolicyRule.of(this, line.familyId(), line.tracked())));
    }

    public UUID getId() {
        return id;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public VersionStatus getStatus() {
        return period.status();
    }

    public Instant getEffectiveFrom() {
        return period.effectiveFrom();
    }

    public long getVersion() {
        return Objects.requireNonNullElse(version, 0L);
    }
}

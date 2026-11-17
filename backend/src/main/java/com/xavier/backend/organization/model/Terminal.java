package com.xavier.backend.organization.model;

import com.xavier.backend.organization.exception.IllegalTerminalStateException;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.annotations.UuidGenerator;

/**
 * A registered device of a store (spec §8.2, D182, D187): a POS terminal or a print bridge ({@link DeviceKind}). It is
 * enrolled with a one-time code into its own Keycloak client of the devices realm (private_key_jwt, no shared secret)
 * and acts through that client's membership. Only the SHA-256 of the code's secret is kept. Five wrong secrets burn
 * the code; the first right one pins the device's public key, so a second device holding the code cannot finish the
 * enrolment. An enrolled device may be enrolled again with a new code (its key was lost); a revoked one never.
 */
@Entity
@Table(schema = "organization", name = "terminal")
public class Terminal {

    /** Wrong secrets that burn a code (D182 as amended). */
    public static final int MAX_FAILURES = 5;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DeviceKind kind;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TerminalStatus status;

    @Column(length = 64)
    private String enrollmentCodeHash;

    private Instant enrollmentCodeExpiresAt;

    @Column(nullable = false)
    private int enrollmentFailures;

    @Column(length = 43)
    private String enrollmentKeyThumbprint;

    private UUID enrollmentMembershipId;

    private UUID membershipId;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Terminal() {
        // JPA
    }

    public static Terminal register(UUID storeId, DeviceKind kind, String code, String name, Instant now) {
        Terminal terminal = new Terminal();
        terminal.storeId = storeId;
        terminal.kind = kind;
        terminal.code = code.strip();
        terminal.name = name.strip();
        terminal.status = TerminalStatus.REGISTERED;
        terminal.createdAt = now;
        terminal.updatedAt = now;
        return terminal;
    }

    /** What a code presentation found (the caller commits the count before it answers). */
    public enum Presentation {
        /** The right secret, in time, with the key this enrolment pinned (or the first key). */
        ACCEPTED,
        /** Wrong, expired, no code, or another key than the pinned one. */
        REFUSED,
        /** The fifth wrong secret: the code is gone. */
        BURNED
    }

    /**
     * A new one-time code (replacing any earlier one, whose pending membership the caller revokes), valid until
     * {@code expiresAt}. A revoked device gets none.
     *
     * @param pendingMembershipId the membership that resolves the code's selector to this device's tenant
     * @return the earlier code's pending membership, if one was still open
     */
    public Optional<UUID> issueEnrollmentCode(String secretHash, Instant expiresAt, UUID pendingMembershipId,
                                              Instant now) {
        requireNotRevoked("get an enrolment code");
        Optional<UUID> replaced = pendingMembership();
        enrollmentCodeHash = secretHash;
        enrollmentCodeExpiresAt = expiresAt;
        enrollmentMembershipId = pendingMembershipId;
        enrollmentFailures = 0;
        enrollmentKeyThumbprint = null;
        updatedAt = now;
        return replaced;
    }

    /**
     * A device presents the code's secret with its public key. The right secret pins the key the first time; a wrong
     * secret, or the right one with another key, counts as a failure, and the {@value #MAX_FAILURES}th burns the code.
     */
    public Presentation presentCode(String secretHash, String keyThumbprint, Instant now) {
        if (!holdsLiveCode(now)) {
            return Presentation.REFUSED;
        }
        if (!enrollmentCodeHash.equals(secretHash)
                || enrollmentKeyThumbprint != null && !enrollmentKeyThumbprint.equals(keyThumbprint)) {
            return fail(now);
        }
        enrollmentKeyThumbprint = keyThumbprint;
        updatedAt = now;
        return Presentation.ACCEPTED;
    }

    /**
     * The enrolment ends: the key the code pinned is registered on the device's client, whose membership is
     * {@code membershipId}. The code is consumed.
     *
     * @return the code's pending membership, for the caller to revoke; empty when the code is no longer this key's
     *         (consumed by a concurrent attempt, replaced, or the device revoked meanwhile)
     */
    public Optional<UUID> completeEnrolment(String keyThumbprint, UUID membershipId, Instant now) {
        if (!holdsLiveCode(now) || !keyThumbprint.equals(enrollmentKeyThumbprint)) {
            return Optional.empty();
        }
        Optional<UUID> pending = pendingMembership();
        clearCode();
        this.membershipId = membershipId;
        status = TerminalStatus.ACTIVE;
        updatedAt = now;
        return pending;
    }

    /**
     * The device can no longer act. Final: register a new device instead.
     *
     * @return the memberships to revoke with it: its own and an open code's pending one
     */
    public List<UUID> revoke(Instant now) {
        requireNotRevoked("be revoked again");
        List<UUID> memberships = Stream.of(membershipId, enrollmentMembershipId).filter(Objects::nonNull).toList();
        status = TerminalStatus.REVOKED;
        clearCode();
        updatedAt = now;
        return memberships;
    }

    /** Whether the device ever completed an enrolment (it has, or had, a Keycloak client with a key). */
    public boolean wasEnrolled() {
        return membershipId != null;
    }

    /** The open code's pending membership. */
    public Optional<UUID> pendingMembership() {
        return Optional.ofNullable(enrollmentMembershipId);
    }

    /** Never carries the code's hash, the pinned key or a membership. */
    public TerminalSummary toSummary() {
        return new TerminalSummary(id, storeId, code, name, status.name(), kind.name());
    }

    /** The device's Keycloak client id. */
    public String clientId() {
        return kind.clientId(id);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public DeviceKind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public TerminalStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    private boolean holdsLiveCode(Instant now) {
        return status != TerminalStatus.REVOKED && enrollmentCodeHash != null
                && now.isBefore(enrollmentCodeExpiresAt);
    }

    private Presentation fail(Instant now) {
        updatedAt = now;
        if (++enrollmentFailures < MAX_FAILURES) {
            return Presentation.REFUSED;
        }
        clearCode();
        return Presentation.BURNED;
    }

    private void clearCode() {
        enrollmentCodeHash = null;
        enrollmentCodeExpiresAt = null;
        enrollmentMembershipId = null;
        enrollmentFailures = 0;
        enrollmentKeyThumbprint = null;
    }

    private void requireNotRevoked(String action) {
        if (status == TerminalStatus.REVOKED) {
            throw new IllegalTerminalStateException("A revoked device cannot " + action);
        }
    }
}

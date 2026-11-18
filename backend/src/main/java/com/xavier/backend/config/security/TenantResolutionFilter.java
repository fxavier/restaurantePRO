package com.xavier.backend.config.security;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorAuthentication;
import com.xavier.backend.access.ActorDirectory;
import com.xavier.backend.access.Assurance;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.tenancy.MembershipSelectionRequiredException;
import com.xavier.backend.tenancy.TenancyApi;
import com.xavier.backend.tenancy.model.dto.Membership;
import com.xavier.backend.tenancy.model.dto.TenantStatus;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * D177: the tenant of a request comes from an authorised membership of the token's {@code (iss, sub)}, never from the
 * request alone. After the bearer token is valid (D176): {@code X-Tenant-Id}, when present, only selects among the
 * caller's memberships (a device has exactly one and never selects, D182) (malformed → 400 {@code TENANT_SELECTION_INVALID}); the membership is read on every request
 * ({@code tenancy.resolve_membership}, P73 as amended: no cache), with the kinds of the token's realm (tenant realm:
 * {@code ACCOUNT}; ops realm: {@code SUPPORT} only; devices realm: {@code TERMINAL} on POS routes, {@code BRIDGE} on
 * bridge routes). None → 403 {@code TENANT_FORBIDDEN}, one body for a foreign
 * and a random tenant (no oracle); several and no selection → 400 {@code TENANT_SELECTION_REQUIRED}, listing nothing;
 * a suspended or closed tenant → 403 {@code TENANT_SUSPENDED}/{@code TENANT_CLOSED}. Support needs a second factor
 * on every request (D183). Then the {@link Actor} is built from the tenant's own rows inside the tenant's binding (RLS
 * applies to the lookup itself), the request runs as {@link ActorAuthentication}, and every log line after this
 * carries {@code tenant_id} (MDC, security rule 7).
 *
 * <p>{@code GET /auth/tenants} needs no tenant (it lists the caller's own memberships), and {@code /platform/**}
 * runs with no tenant at all: an ops-realm token with the {@code platform-operator} realm role, else 403
 * {@code PLATFORM_FORBIDDEN} (D181).
 */
final class TenantResolutionFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String POS_SESSION = "X-Pos-Session";
    static final String MDC_TENANT = "tenant_id";
    static final String PLATFORM_OPERATOR = "platform-operator";
    static final String ORGANIZATION_CLAIM = "organization";

    private static final Logger log = LoggerFactory.getLogger(TenantResolutionFilter.class);
    private static final Set<MembershipKind> ACCOUNT = Set.of(MembershipKind.ACCOUNT);
    private static final Set<MembershipKind> SUPPORT = Set.of(MembershipKind.SUPPORT);

    private final TrustedRealms realms;
    private final TenancyApi tenancy;
    private final TenantScopes tenants;
    private final ActorDirectory actors;
    private final ProblemDetailResponder problems;
    private final MeterRegistry meters;

    TenantResolutionFilter(TrustedRealms realms, TenancyApi tenancy, TenantScopes tenants, ActorDirectory actors,
                           ProblemDetailResponder problems, MeterRegistry meters) {
        this.realms = realms;
        this.tenancy = tenancy;
        this.tenants = tenants;
        this.actors = actors;
        this.problems = problems;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)
                || RouteFamily.TENANT_LIST.equals(RouteFamily.pathOf(request))) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        if (RouteFamily.of(request).orElse(RouteFamily.BACKOFFICE) == RouteFamily.PLATFORM) {
            platform(jwt, request, response, chain);
            return;
        }
        String issuer = jwt.getClaimAsString("iss");
        Realm realm = realms.realmOf(issuer).orElseThrow(); // the decoder accepted only a trusted issuer
        if (realm == Realm.DEVICES) {
            device(jwt, issuer, request, response, chain);
            return;
        }
        account(jwt, issuer, realm, request, response, chain);
    }

    /**
     * A user of the tenant realm (ACCOUNT) or support of the ops realm (SUPPORT, second factor on every request,
     * D183): the selected or only membership, its tenant ACTIVE, then the actor from the tenant's own rows.
     */
    private void account(Jwt jwt, String issuer, Realm realm, HttpServletRequest request, HttpServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        Optional<UUID> requested;
        try {
            requested = selection(request);
        } catch (IllegalArgumentException e) {
            problems.refuse(response, HttpStatus.BAD_REQUEST, "TENANT_SELECTION_INVALID",
                    "X-Tenant-Id is not a valid tenant id.");
            return;
        }
        Optional<Membership> membership = resolve(jwt, issuer, requested.orElse(null),
                realm == Realm.OPS ? SUPPORT : ACCOUNT, response);
        if (membership == null) {
            return;
        }
        if (membership.isEmpty()) {
            forbidden(response, jwt, requested);
            return;
        }
        Membership member = membership.get();
        if (member.tenantStatus() != TenantStatus.ACTIVE) {
            notActive(response, member);
            return;
        }
        checkOrganizationClaim(jwt, issuer, member);
        Assurance assurance = assuranceOf(jwt);
        if (member.kind() == MembershipKind.SUPPORT && !assurance.hasSecondFactor()) {
            problems.stepUpChallenge(response); // D183: support always works with a second factor
            return;
        }
        TenantContext context = new TenantContext(member.tenantId(), member.kind(), member.principalId(),
                realm.name().toLowerCase() + ":" + jwt.getSubject());
        Optional<Actor> actor = tenants.callIn(context, () -> member.kind() == MembershipKind.SUPPORT
                ? actors.support(member.principalId()) : actors.account(member.principalId()));
        if (actor.isEmpty()) {
            log.warn("Tenant refused: the membership's principal cannot act: sub={} tenant={} kind={}",
                    jwt.getSubject(), member.tenantId(), member.kind());
            forbidden(response, jwt, requested);
            return;
        }
        runAs(new ActorAuthentication(actor.get(), context, assurance), context, request, response, chain);
    }

    /**
     * The membership of {@code (issuer, sub)} of {@code kinds}, or {@code null} once a refusal is written (several
     * memberships and no selection, D177 step 5).
     */
    private Optional<Membership> resolve(Jwt jwt, String issuer, UUID requested, Set<MembershipKind> kinds,
                                         HttpServletResponse response) throws IOException {
        try {
            return tenancy.resolve(issuer, jwt.getSubject(), requested, kinds);
        } catch (MembershipSelectionRequiredException several) {
            problems.refuse(response, HttpStatus.BAD_REQUEST, "TENANT_SELECTION_REQUIRED",
                    "Choose a tenant with X-Tenant-Id; GET /api/v1/auth/tenants lists yours.");
            return null;
        }
    }

    /**
     * A terminal or a print bridge (D182, D187): the client's one membership of the route family's kind
     * ({@code TERMINAL} on POS routes, {@code BRIDGE} on bridge routes), never a selection ({@code X-Tenant-Id} is
     * ignored: a device belongs to one tenant). A bridge acts as itself. A terminal acts as itself only for the PIN
     * login; every other POS route runs as the employee of {@code X-Pos-Session}, which must be a live session opened
     * on this very terminal, else 401 {@code POS_SESSION_INVALID} (hand-off b).
     */
    private void device(Jwt jwt, String issuer, HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws IOException, ServletException {
        MembershipKind kind = RouteFamily.of(request).filter(family -> family == RouteFamily.BRIDGE).isPresent()
                ? MembershipKind.BRIDGE : MembershipKind.TERMINAL;
        Optional<Membership> membership = resolve(jwt, issuer, null, Set.of(kind), response);
        if (membership == null) {
            return;
        }
        if (membership.isEmpty()) {
            forbidden(response, jwt, Optional.empty());
            return;
        }
        Membership member = membership.get();
        if (member.tenantStatus() != TenantStatus.ACTIVE) {
            notActive(response, member);
            return;
        }
        TenantContext context = new TenantContext(member.tenantId(), kind, member.principalId(),
                "devices:" + jwt.getSubject());
        if (kind == MembershipKind.BRIDGE || RouteFamily.posSessionExempt(RouteFamily.pathOf(request))) {
            JwtAuthenticationToken device = new JwtAuthenticationToken(jwt, List.of());
            device.setDetails(context);
            runAs(device, context, request, response, chain);
            return;
        }
        String session = request.getHeader(POS_SESSION);
        Optional<Actor> employee = session == null || session.isBlank() ? Optional.empty()
                : tenants.callIn(context, () -> actors.posSession(session.strip(), member.principalId()));
        if (employee.isEmpty()) {
            problems.refuse(response, HttpStatus.UNAUTHORIZED, "POS_SESSION_INVALID", "Log in on the terminal again.");
            return;
        }
        runAs(new ActorAuthentication(employee.get(), context, Assurance.NONE), context, request, response, chain);
    }

    /** D177 step 8: a mismatch is logged and counted, never acted on (no tenant label on the metric, rule 7). */
    private void checkOrganizationClaim(Jwt jwt, String issuer, Membership member) {
        Object claim = jwt.getClaims().get(ORGANIZATION_CLAIM);
        if (claim == null) {
            return;
        }
        tenancy.membershipSummaries(issuer, jwt.getSubject()).stream()
                .filter(summary -> summary.tenantId().equals(member.tenantId()))
                .filter(summary -> OrganizationClaim.mismatches(claim, summary))
                .findFirst()
                .ifPresent(summary -> {
                    log.warn("The token's organization claim does not name its tenant's organization: sub={} "
                            + "tenant={}", jwt.getSubject(), member.tenantId());
                    meters.counter("tenant_claim_mismatch_total").increment();
                });
    }

    private void runAs(Authentication resolved, TenantContext tenant, HttpServletRequest request,
                       HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(resolved);
        SecurityContextHolder.setContext(context);
        MDC.put(MDC_TENANT, tenant.tenantId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TENANT);
            SecurityContextHolder.setContext(previous);
        }
    }

    private void platform(Jwt jwt, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (realms.realmOf(jwt.getClaimAsString("iss")).filter(realm -> realm == Realm.OPS).isEmpty()
                || !realmRoles(jwt).contains(PLATFORM_OPERATOR)) {
            problems.refuse(response, HttpStatus.FORBIDDEN, "PLATFORM_FORBIDDEN",
                    "The platform API needs a platform operator.");
            return;
        }
        // no tenant, explicitly: it outranks any fallback, so every transaction binds '' (the platform functions
        // refuse a bound tenant)
        try {
            tenants.callUnbound(SystemScope.PLATFORM, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof ServletException servlet) {
                throw servlet;
            }
            throw e;
        }
    }

    private static List<String> realmRoles(Jwt jwt) {
        Object access = jwt.getClaims().get("realm_access");
        if (access instanceof java.util.Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
            return roles.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private void forbidden(HttpServletResponse response, Jwt jwt, Optional<UUID> requested) throws IOException {
        if (requested.isPresent()) {
            log.warn("Tenant selection refused: sub={} requested={}", jwt.getSubject(), requested.get());
            meters.counter("tenant_selection_refused_total").increment();
        }
        problems.refuse(response, HttpStatus.FORBIDDEN, "TENANT_FORBIDDEN", "You cannot act in this tenant.");
    }

    private void notActive(HttpServletResponse response, Membership member) throws IOException {
        switch (member.tenantStatus()) {
            case SUSPENDED -> problems.refuse(response, HttpStatus.FORBIDDEN, "TENANT_SUSPENDED",
                    "This tenant is suspended.");
            case CLOSED -> problems.refuse(response, HttpStatus.FORBIDDEN, "TENANT_CLOSED", "This tenant is closed.");
            default -> problems.refuse(response, HttpStatus.FORBIDDEN, "TENANT_FORBIDDEN",
                    "You cannot act in this tenant.");
        }
    }

    /** {@code X-Tenant-Id}, a selection only (P72). */
    private static Optional<UUID> selection(HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        if (header == null) {
            return Optional.empty();
        }
        UUID id = UUID.fromString(header.strip());
        if (!id.toString().equalsIgnoreCase(header.strip())) {
            throw new IllegalArgumentException("not a canonical UUID");
        }
        return Optional.of(id);
    }

    /** The token's own {@code acr} and {@code auth_time} (RFC 9470 §6.1), never requested values. */
    static Assurance assuranceOf(Jwt jwt) {
        Object authTime = jwt.getClaims().get("auth_time");
        Instant at = authTime instanceof Instant instant ? instant
                : authTime instanceof Number seconds ? Instant.ofEpochSecond(seconds.longValue()) : null;
        return new Assurance(jwt.getClaimAsString("acr"), at);
    }
}

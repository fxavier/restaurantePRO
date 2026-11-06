package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.settlement.PaymentProvider;
import com.xavier.backend.settlement.model.PaymentMethod;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.reconciliation.StatementLine;
import com.xavier.backend.settlement.reconciliation.StatementSource;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationConflictException;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationNotFoundException;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationRuleException;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationUnavailableException;
import com.xavier.backend.settlement.reconciliation.model.BatchSource;
import com.xavier.backend.settlement.reconciliation.model.EntryKind;
import com.xavier.backend.settlement.reconciliation.model.Match;
import com.xavier.backend.settlement.reconciliation.model.PaymentCandidate;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationBatch;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationEntry;
import com.xavier.backend.settlement.reconciliation.model.ReconciliationResolution;
import com.xavier.backend.settlement.reconciliation.model.StatementMatcher;
import com.xavier.backend.settlement.reconciliation.model.StatementRow;
import com.xavier.backend.settlement.reconciliation.model.StatementRows;
import com.xavier.backend.settlement.reconciliation.model.dto.BatchView;
import com.xavier.backend.settlement.reconciliation.model.dto.FetchRequest;
import com.xavier.backend.settlement.reconciliation.model.dto.ImportRequest;
import com.xavier.backend.settlement.reconciliation.repository.CandidateRow;
import com.xavier.backend.settlement.reconciliation.repository.KindCount;
import com.xavier.backend.settlement.reconciliation.repository.PaymentReads;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationBatchRepository;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationEntryRepository;
import com.xavier.backend.settlement.reconciliation.repository.ReconciliationResolutionRepository;
import com.xavier.backend.settlement.repository.PaymentMethodRepository;
import com.xavier.backend.settlement.service.BoundedProviderCalls;
import com.xavier.backend.settlement.service.ProviderUnavailableException;
import com.xavier.backend.shared.ExternalCalls;
import com.xavier.backend.shared.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Importing a provider statement (D78, PRD S31), with {@code RECONCILIATION_MANAGE} in its store: from the canonical
 * CSV, or fetched from the provider when its adapter is a {@link StatementSource}. In one transaction it checks every
 * line, matches them ({@link StatementMatcher}) against the store's payments of the method, net of their refunds,
 * and records the batch, one entry per line and per payment the statement lacks, and AUTO_MATCHED for each MATCHED
 * entry. Imports of one method are serialized, so a statement always sees what an earlier one matched. Every line
 * is checked before the transaction and the lock.
 *
 * <p>It reads payments, refunds and attempts and writes none of them, nor any cash movement (D78).
 */
@Service
public class StatementImportService {

    private final AccessPolicy accessPolicy;
    private final OrganizationApi organization;
    private final PaymentMethodRepository methods;
    private final PaymentReads payments;
    private final ReconciliationBatchRepository batches;
    private final ReconciliationEntryRepository entries;
    private final ReconciliationResolutionRepository resolutions;
    private final ObjectProvider<PaymentProvider> providers;
    private final BoundedProviderCalls boundedCalls;
    private final AuditLog audit;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    StatementImportService(AccessPolicy accessPolicy, OrganizationApi organization, PaymentMethodRepository methods,
                           PaymentReads payments, ReconciliationBatchRepository batches,
                           ReconciliationEntryRepository entries, ReconciliationResolutionRepository resolutions,
                           ObjectProvider<PaymentProvider> providers, BoundedProviderCalls boundedCalls,
                           AuditLog audit,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.organization = organization;
        this.methods = methods;
        this.payments = payments;
        this.batches = batches;
        this.entries = entries;
        this.resolutions = resolutions;
        this.providers = providers;
        this.boundedCalls = boundedCalls;
        this.audit = audit;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /** A statement imported now ({@code created}), or the batch an earlier fetch of the same statement recorded. */
    public record Imported(BatchView batch, boolean created) {
    }

    /** A statement whose every line passed, ready to import: its target, source, digest and rows. */
    public record Statement(Target target, BatchSource source, String digest, List<StatementRow> rows) {
    }

    /**
     * Phase 1 of a CSV import, with no transaction and no lock: authorizes, reads the store and the method, and checks
     * every line (pure parsing: a bad file never holds the import lock).
     *
     * @throws ReconciliationRuleException 422 {@code STATEMENT_INVALID} with the file's or request's errors
     */
    public Statement readCsv(ImportRequest request, byte[] file) {
        ExternalCalls.requireNoTransaction();
        accessPolicy.require(Permission.RECONCILIATION_MANAGE, request.storeId());
        if (!Objects.equals(request.fileSha256(), sha256(file))) {
            throw new IllegalArgumentException("The request's digest is not the file's");
        }
        Target target = readOnly.execute(tx -> target(request.storeId(), request.methodCode(), request.periodFrom(),
                request.periodTo()));
        return checked(target, BatchSource.CSV, file,
                StatementRows.fromCsv(file, target.currency(), target.start(), target.end()));
    }

    /**
     * Phase 2: imports a statement {@link #readCsv} checked.
     *
     * @throws ReconciliationConflictException 409 {@code STATEMENT_ALREADY_IMPORTED} for a statement the store's method
     *         already has, for the same period
     */
    @Transactional
    public BatchView importCsv(Statement statement) {
        accessPolicy.require(Permission.RECONCILIATION_MANAGE, statement.target().storeId());
        return record(statement).batch();
    }

    /**
     * The D63 steps of a fetch; call with no transaction (from {@code executeSteps}). (s1) a read-only transaction
     * authorizes and finds the method's {@link StatementSource}; (s2) the provider's statement, with no transaction,
     * bounded by the provider timeout, then the lines' checks; (s3) a transaction imports it as a CSV is. A second
     * fetch of the same statement returns the recorded batch (each step is idempotent by the batches stored).
     *
     * @throws ReconciliationRuleException 422 {@code NO_STATEMENT_SOURCE} for a method whose provider cannot fetch
     * @throws ReconciliationUnavailableException 503 {@code STATEMENT_SOURCE_TIMEOUT} when the provider is too slow
     */
    public Imported fetch(FetchRequest request) {
        record Source(Target target, String providerCode, StatementSource source) {
        }
        Source found = readOnly.execute(tx -> {
            accessPolicy.require(Permission.RECONCILIATION_MANAGE, request.storeId());
            Target target = target(request.storeId(), request.methodCode(), request.from(), request.to());
            PaymentMethod method = methods.findByCode(target.methodCode()).orElseThrow();
            return new Source(target, method.getProviderCode(), sourceOf(method));
        });
        List<StatementLine> lines;
        try {
            lines = List.copyOf(boundedCalls.call(found.providerCode(), "statement",
                    () -> found.source().statement(request.methodCode(), request.from(), request.to())));
        } catch (ProviderUnavailableException e) {
            throw ReconciliationUnavailableException.sourceTimeout(request.methodCode());
        }
        Target target = found.target();
        Statement statement = checked(target, BatchSource.PROVIDER, StatementRows.canonicalCsv(lines),
                StatementRows.fromLines(lines, target.currency(), target.start(), target.end()));
        return inTransaction.execute(tx -> {
            accessPolicy.require(Permission.RECONCILIATION_MANAGE, request.storeId());
            return record(statement);
        });
    }

    private static Statement checked(Target target, BatchSource source, byte[] content, StatementRows.Result read) {
        if (!read.errors().isEmpty()) {
            throw ReconciliationRuleException.statementInvalid(read.errors());
        }
        return new Statement(target, source, digest(target, content), read.rows());
    }

    /**
     * What identifies a statement ({@code reconciliation_batch.file_sha256}): its store, period and method with its
     * content, so the same bytes for another store or period (an empty statement, say) are another statement.
     */
    static String digest(Target target, byte[] content) {
        byte[] identity = ("store:" + target.storeId() + "\nperiod:" + target.from() + ".." + target.to()
                + "\nmethod:" + target.methodCode() + "\n").getBytes(StandardCharsets.UTF_8);
        return sha256(identity, content);
    }

    private Imported record(Statement statement) {
        Target target = statement.target();
        batches.lockImportsOf(target.methodCode());
        var existing = batches.findByMethodCodeAndFileSha256(target.methodCode(), statement.digest());
        if (existing.isPresent()) {
            ReconciliationBatch batch = existing.get();
            if (!batch.getStoreId().equals(target.storeId())) {
                throw ReconciliationConflictException.alreadyImportedElsewhere();
            }
            if (statement.source() == BatchSource.CSV) {
                throw ReconciliationConflictException.alreadyImported(batch.getId());
            }
            return new Imported(ReconciliationViews.batch(batch, entries.countsOf(List.of(batch.getId()))), false);
        }
        List<StatementRow> rows = statement.rows();
        List<Match> matches = StatementMatcher.match(target.methodKind(), rows, candidates(target, rows),
                target.start(), target.end());
        Instant now = clock.instant();
        ReconciliationBatch batch = batches.save(ReconciliationBatch.of(target.storeId(), target.methodCode(),
                target.from(), target.to(), target.currency(), statement.digest(), statement.source(), rows.size(),
                accessPolicy.current().userId(), now));
        List<ReconciliationEntry> recorded = entries.saveAll(matches.stream()
                .map(match -> ReconciliationEntry.of(batch, match, now)).toList());
        resolutions.saveAll(recorded.stream().filter(entry -> entry.getKind() == EntryKind.MATCHED)
                .map(entry -> ReconciliationResolution.autoMatched(entry, now)).toList());
        List<KindCount> counts = ReconciliationViews.counted(batch, recorded);
        audit.recordAsCurrentActor("RECONCILIATION_BATCH_IMPORTED", "ReconciliationBatch", batch.getId().toString(),
                target.storeId(), null, null, Map.of("methodCode", target.methodCode(), "source",
                        statement.source().name(), "lineCount", rows.size(), "entries", counts.stream().collect(
                                Collectors.toMap(c -> c.kind().name(), KindCount::count))));
        return new Imported(ReconciliationViews.batch(batch, counts), true);
    }

    /**
     * The payments the statement may be about: those confirmed in its period (widened by the matching window), and,
     * for an integrated method, those its lines name by reference whenever they were confirmed.
     */
    private List<PaymentCandidate> candidates(Target target, List<StatementRow> rows) {
        String code = target.methodCode();
        Map<UUID, CandidateRow> found = new LinkedHashMap<>();
        payments.confirmedBetween(target.storeId(), code, target.start().minus(StatementMatcher.WINDOW),
                target.end().plus(StatementMatcher.WINDOW)).forEach(row -> found.put(row.paymentId(), row));
        Set<String> references = rows.stream().map(StatementRow::reference).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (target.methodKind() == PaymentMethodKind.INTEGRATED && !references.isEmpty()) {
            payments.withReferences(target.storeId(), code, references)
                    .forEach(row -> found.put(row.paymentId(), row));
        }
        if (found.isEmpty()) {
            return List.of();
        }
        Set<UUID> matchedEarlier = Set.copyOf(entries.matchedAmong(found.keySet()));
        return found.values().stream().map(row -> {
            Currency currency = Currency.getInstance(row.currency());
            return new PaymentCandidate(row.paymentId(),
                    Money.of(row.amount(), currency).minus(Money.of(row.refunded(), currency)), row.confirmedAt(),
                    row.providerReference(), row.providerTransactionRef(), row.authorizationCode(),
                    matchedEarlier.contains(row.paymentId()));
        }).toList();
    }

    private Target target(UUID storeId, String methodCode, LocalDate from, LocalDate to) {
        StoreSummary store = organization.findStore(storeId)
                .orElseThrow(() -> new ReconciliationNotFoundException("Store", storeId));
        if (from.isAfter(to)) {
            throw ReconciliationRuleException.statementInvalid("periodTo", "The period ends before it starts");
        }
        PaymentMethod method = methods.findByCode(methodCode).filter(m -> m.getKind() != PaymentMethodKind.CASH)
                .orElseThrow(() -> ReconciliationRuleException.statementInvalid("methodCode",
                        "No card, terminal or provider payment method has the code " + methodCode));
        ZoneId zone = store.timezone();
        LocalTime cutoff = store.businessDayCutoff();
        return new Target(storeId, method.getCode(), method.getKind(), store.currency(), from, to,
                BusinessDays.startOf(from, zone, cutoff), BusinessDays.startOf(to.plusDays(1), zone, cutoff));
    }

    private StatementSource sourceOf(PaymentMethod method) {
        return providers.orderedStream()
                .filter(provider -> provider.code().equals(method.getProviderCode()))
                .filter(StatementSource.class::isInstance).map(StatementSource.class::cast).findFirst()
                .orElseThrow(() -> ReconciliationRuleException.noStatementSource(method.getCode()));
    }

    /** The statement's store, method and business days, and those days as instants {@code [start, end)}. */
    public record Target(UUID storeId, String methodCode, PaymentMethodKind methodKind, Currency currency,
                         LocalDate from, LocalDate to, Instant start, Instant end) {
    }

    public static String sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }
}

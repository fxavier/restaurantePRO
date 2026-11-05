package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
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
import com.xavier.backend.shared.ExternalCalls;
import com.xavier.backend.shared.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 * entry. Imports of one method are serialized, so a statement always sees what an earlier one matched.
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
    private final AuditLog audit;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    StatementImportService(AccessPolicy accessPolicy, OrganizationApi organization, PaymentMethodRepository methods,
                           PaymentReads payments, ReconciliationBatchRepository batches,
                           ReconciliationEntryRepository entries, ReconciliationResolutionRepository resolutions,
                           ObjectProvider<PaymentProvider> providers, AuditLog audit,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.organization = organization;
        this.methods = methods;
        this.payments = payments;
        this.batches = batches;
        this.entries = entries;
        this.resolutions = resolutions;
        this.providers = providers;
        this.audit = audit;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /** A statement imported now ({@code created}), or the batch an earlier fetch of the same statement recorded. */
    public record Imported(BatchView batch, boolean created) {
    }

    /**
     * @throws ReconciliationRuleException 422 {@code STATEMENT_INVALID} with every error of the file or request
     * @throws ReconciliationConflictException 409 {@code STATEMENT_ALREADY_IMPORTED} for a file this method already has
     */
    @Transactional
    public BatchView importCsv(ImportRequest request, byte[] file) {
        accessPolicy.require(Permission.RECONCILIATION_MANAGE, request.storeId());
        Target target = target(request.storeId(), request.methodCode(), request.periodFrom(), request.periodTo());
        if (!Objects.equals(request.fileSha256(), sha256(file))) {
            throw new IllegalArgumentException("The request's digest is not the file's");
        }
        return record(target, BatchSource.CSV, request.fileSha256(),
                t -> StatementRows.fromCsv(file, t.currency(), t.start(), t.end())).batch();
    }

    /**
     * The D63 steps of a fetch; call with no transaction (from {@code executeSteps}). (s1) a read-only transaction
     * authorizes and finds the method's {@link StatementSource}; (s2) the provider's statement, with no transaction;
     * (s3) a transaction imports it as {@link #importCsv} does. A fetched statement is identified by its store,
     * period and canonical lines, and a second fetch of the same one returns the recorded batch (each step is
     * idempotent by the batches stored).
     *
     * @throws ReconciliationRuleException 422 {@code NO_STATEMENT_SOURCE} for a method whose provider cannot fetch
     */
    public Imported fetch(FetchRequest request) {
        StatementSource source = readOnly.execute(tx -> {
            accessPolicy.require(Permission.RECONCILIATION_MANAGE, request.storeId());
            return sourceOf(target(request.storeId(), request.methodCode(), request.from(), request.to()).method());
        });
        ExternalCalls.requireNoTransaction();
        List<StatementLine> lines = List.copyOf(source.statement(request.methodCode(), request.from(), request.to()));
        byte[] identity = ("store:" + request.storeId() + "\nperiod:" + request.from() + "/" + request.to() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String digest = sha256(identity, StatementRows.canonicalCsv(lines));
        return inTransaction.execute(tx -> {
            accessPolicy.require(Permission.RECONCILIATION_MANAGE, request.storeId());
            Target target = target(request.storeId(), request.methodCode(), request.from(), request.to());
            return record(target, BatchSource.PROVIDER, digest,
                    t -> StatementRows.fromLines(lines, t.currency(), t.start(), t.end()));
        });
    }

    private Imported record(Target target, BatchSource source, String digest,
                            Function<Target, StatementRows.Result> read) {
        PaymentMethod method = target.method();
        batches.lockImportsOf(method.getCode());
        var existing = batches.findByMethodCodeAndFileSha256(method.getCode(), digest);
        if (existing.isPresent()) {
            if (source == BatchSource.CSV) {
                throw ReconciliationConflictException.alreadyImported(existing.get().getId());
            }
            ReconciliationBatch batch = existing.get();
            return new Imported(ReconciliationViews.batch(batch, entries.countsOf(List.of(batch.getId()))), false);
        }
        StatementRows.Result statement = read.apply(target);
        if (!statement.errors().isEmpty()) {
            throw ReconciliationRuleException.statementInvalid(statement.errors());
        }
        List<Match> matches = StatementMatcher.match(method.getKind(), statement.rows(),
                candidates(target, statement.rows()), target.start(), target.end());
        Instant now = clock.instant();
        ReconciliationBatch batch = batches.save(ReconciliationBatch.of(target.storeId(), method.getCode(),
                target.from(), target.to(), target.currency(), digest, source, statement.rows().size(),
                accessPolicy.current().userId(), now));
        List<ReconciliationEntry> recorded = entries.saveAll(matches.stream()
                .map(match -> ReconciliationEntry.of(batch, match, now)).toList());
        resolutions.saveAll(recorded.stream().filter(entry -> entry.getKind() == EntryKind.MATCHED)
                .map(entry -> ReconciliationResolution.autoMatched(entry, now)).toList());
        List<KindCount> counts = ReconciliationViews.counted(batch, recorded);
        audit.recordAsCurrentActor("RECONCILIATION_BATCH_IMPORTED", "ReconciliationBatch", batch.getId().toString(),
                target.storeId(), null, null, Map.of("methodCode", method.getCode(), "source", source.name(),
                        "lineCount", statement.rows().size(), "entries", counts.stream().collect(
                                Collectors.toMap(c -> c.kind().name(), KindCount::count))));
        return new Imported(ReconciliationViews.batch(batch, counts), true);
    }

    /**
     * The payments the statement may be about: those confirmed in its period (widened by the matching window), and,
     * for an integrated method, those its lines name by reference whenever they were confirmed.
     */
    private List<PaymentCandidate> candidates(Target target, List<StatementRow> rows) {
        String code = target.method().getCode();
        Map<UUID, CandidateRow> found = new LinkedHashMap<>();
        payments.confirmedBetween(target.storeId(), code, target.start().minus(StatementMatcher.WINDOW),
                target.end().plus(StatementMatcher.WINDOW)).forEach(row -> found.put(row.paymentId(), row));
        Set<String> references = rows.stream().map(StatementRow::reference).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (target.method().getKind() == PaymentMethodKind.INTEGRATED && !references.isEmpty()) {
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
        return new Target(storeId, method, store.currency(), from, to, from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private StatementSource sourceOf(PaymentMethod method) {
        return providers.orderedStream()
                .filter(provider -> provider.code().equals(method.getProviderCode()))
                .filter(StatementSource.class::isInstance).map(StatementSource.class::cast).findFirst()
                .orElseThrow(() -> ReconciliationRuleException.noStatementSource(method.getCode()));
    }

    /** The statement's store, method and business days, and those days as instants {@code [start, end)}. */
    private record Target(UUID storeId, PaymentMethod method, Currency currency, LocalDate from, LocalDate to,
                          Instant start, Instant end) {
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

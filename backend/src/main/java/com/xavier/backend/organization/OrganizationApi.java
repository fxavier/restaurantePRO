package com.xavier.backend.organization;

import com.xavier.backend.organization.exception.CompanyNotBootstrappedException;
import com.xavier.backend.organization.exception.StoreNotFoundException;
import com.xavier.backend.organization.model.CompanyProfile;
import com.xavier.backend.organization.model.Store;
import com.xavier.backend.organization.model.Terminal;
import com.xavier.backend.organization.model.TerminalStatus;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.organization.repository.CompanyProfileRepository;
import com.xavier.backend.organization.repository.StoreRepository;
import com.xavier.backend.organization.repository.TerminalRepository;
import com.xavier.backend.organization.service.TerminalSecrets;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules may ask the organization module (spec §8.2: IDs and public APIs, no shared entities).
 * Read-only and without authorization: callers authorize their own use case first (D27), and POS login (S2)
 * looks a terminal up before anyone is authenticated.
 */
@Service
@Transactional(readOnly = true)
public class OrganizationApi {

    private final StoreRepository stores;
    private final TerminalRepository terminals;
    private final CompanyProfileRepository companies;

    OrganizationApi(StoreRepository stores, TerminalRepository terminals, CompanyProfileRepository companies) {
        this.stores = stores;
        this.terminals = terminals;
        this.companies = companies;
    }

    public Optional<StoreSummary> findStore(UUID storeId) {
        return stores.findById(storeId).map(store -> store.toSummary(company().getCurrency()));
    }

    public boolean storeExists(UUID storeId) {
        return stores.existsById(storeId);
    }

    /** @throws StoreNotFoundException if there is no such store */
    public ZoneId zoneOf(UUID storeId) {
        return stores.findById(storeId).map(Store::getTimezone).orElseThrow(() -> new StoreNotFoundException(storeId));
    }

    /** The enrolled, not revoked terminal that owns {@code deviceToken}; empty for anything else. */
    public Optional<TerminalSummary> findTerminalByDeviceToken(String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return Optional.empty();
        }
        return terminals.findByDeviceTokenHashAndStatus(TerminalSecrets.hashDeviceToken(deviceToken),
                TerminalStatus.ACTIVE).map(Terminal::toSummary);
    }

    /** @throws CompanyNotBootstrappedException if the company was never bootstrapped */
    public CompanyProfileView companyProfile() {
        return company().toView();
    }

    private CompanyProfile company() {
        return companies.findById(Boolean.TRUE).orElseThrow(CompanyNotBootstrappedException::new);
    }
}

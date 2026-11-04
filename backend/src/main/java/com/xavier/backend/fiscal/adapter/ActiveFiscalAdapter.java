package com.xavier.backend.fiscal.adapter;

import com.xavier.backend.fiscal.FiscalAdapter;
import com.xavier.backend.fiscal.IssueRequest;
import com.xavier.backend.fiscal.IssueResult;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.model.event.DocumentPrintout;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The adapter this installation issues with (D54). Built at startup, it is the startup guard: an unknown code, or a
 * non-certified adapter without {@code restaurantpro.fiscal.allow-non-certified=true}, stops the application. It is
 * also the one place that puts {@link #NON_FISCAL_MARK} on every printout of a non-certified adapter (B4).
 */
@Component
@EnableConfigurationProperties(FiscalProperties.class)
public class ActiveFiscalAdapter {

    public static final String NON_FISCAL_MARK = "DOCUMENTO NÃO FISCAL";

    private static final Logger log = LoggerFactory.getLogger(ActiveFiscalAdapter.class);

    private final FiscalAdapter adapter;

    ActiveFiscalAdapter(List<FiscalAdapter> adapters, FiscalProperties properties) {
        this.adapter = adapters.stream().filter(a -> a.code().equals(properties.adapter())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No fiscal adapter '" + properties.adapter()
                        + "' (restaurantpro.fiscal.adapter); available: "
                        + adapters.stream().map(FiscalAdapter::code).toList()));
        if (!adapter.certified()) {
            if (!properties.allowNonCertified()) {
                throw new IllegalStateException("The fiscal adapter '" + adapter.code() + "' is NOT certified and "
                        + "must not be used in production (B4). To run it anyway (development, tests, a pilot "
                        + "without fiscal obligations) set restaurantpro.fiscal.allow-non-certified=true.");
            }
            log.warn("Fiscal adapter '{}' is NOT certified: documents are marked '{}' (B4, D54)", adapter.code(),
                    NON_FISCAL_MARK);
        }
    }

    public String code() {
        return adapter.code();
    }

    public boolean certified() {
        return adapter.certified();
    }

    public IssueResult issue(IssueRequest request) {
        return adapter.issue(request);
    }

    public DocumentPrintout printout(IssuedDocument document, boolean copy) {
        DocumentPrintout printout = adapter.printout(document, copy);
        if (adapter.certified()) {
            return printout;
        }
        List<String> marked = new ArrayList<>(printout.lines().size() + 2);
        marked.add(NON_FISCAL_MARK);
        marked.addAll(printout.lines());
        marked.add(NON_FISCAL_MARK);
        return new DocumentPrintout(marked, printout.qrPayload());
    }
}

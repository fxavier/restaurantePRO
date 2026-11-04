package com.xavier.backend.fiscal.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.FiscalAdapter;
import com.xavier.backend.fiscal.IssueRequest;
import com.xavier.backend.fiscal.IssueResult;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapterTest;
import com.xavier.backend.fiscal.model.event.DocumentPrintout;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D54 / B4 / A13: the startup guard and the non-fiscal mark. */
class ActiveFiscalAdapterTest {

    @Test
    void aNonCertifiedAdapterIsRefusedUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> new ActiveFiscalAdapter(List.of(new InternalFiscalAdapter()),
                new FiscalProperties("internal", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restaurantpro.fiscal.allow-non-certified");
    }

    @Test
    void anUnknownAdapterIsRefused() {
        assertThatThrownBy(() -> new ActiveFiscalAdapter(List.of(new InternalFiscalAdapter()),
                new FiscalProperties("pt-saft", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pt-saft");
    }

    @Test
    void everyPrintoutOfANonCertifiedAdapterCarriesTheNonFiscalMark() {
        ActiveFiscalAdapter active = new ActiveFiscalAdapter(List.of(new InternalFiscalAdapter()),
                new FiscalProperties("internal", true));

        for (boolean copy : new boolean[]{false, true}) {
            List<String> lines = active.printout(InternalFiscalAdapterTest.document(), copy).lines();
            assertThat(lines.getFirst()).isEqualTo(ActiveFiscalAdapter.NON_FISCAL_MARK);
            assertThat(lines.getLast()).isEqualTo(ActiveFiscalAdapter.NON_FISCAL_MARK);
        }
        assertThat(ActiveFiscalAdapter.NON_FISCAL_MARK).isEqualTo("DOCUMENTO NÃO FISCAL");
        assertThat(active.certified()).isFalse();
        assertThat(active.code()).isEqualTo("internal");
    }

    @Test
    void aCertifiedAdapterStartsWithoutTheFlagAndPrintsUnmarked() {
        ActiveFiscalAdapter active = new ActiveFiscalAdapter(List.of(new InternalFiscalAdapter(), new Certified()),
                new FiscalProperties("certified", false));

        assertThat(active.printout(InternalFiscalAdapterTest.document(), false).lines()).containsExactly("ok");
    }

    static final class Certified implements FiscalAdapter {
        @Override
        public String code() {
            return "certified";
        }

        @Override
        public boolean certified() {
            return true;
        }

        @Override
        public IssueResult issue(IssueRequest request) {
            return new IssueResult("x", null);
        }

        @Override
        public DocumentPrintout printout(IssuedDocument document, boolean copy) {
            return new DocumentPrintout(List.of("ok"), null);
        }
    }
}

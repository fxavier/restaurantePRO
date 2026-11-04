package com.xavier.backend.catalog.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.catalog.exception.IllegalReleaseStateException;
import com.xavier.backend.shared.exception.ConflictException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigurationReleaseTest {

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void aNewReleaseIsADraftWithoutSnapshotOrVersion() {
        ConfigurationRelease release = ConfigurationRelease.draft(STORE, AUTHOR, T0);

        assertThat(release.getStatus()).isEqualTo(ReleaseStatus.DRAFT);
        assertThat(release.getCatalogVersion()).isNull();
        assertThat(release.getSnapshot()).isNull();
    }

    @Test
    void publishingAValidatedReleaseFreezesSnapshotVersionInstantAndAuthor() {
        ConfigurationRelease release = ConfigurationRelease.draft(STORE, AUTHOR, T0);
        release.markValidated(T0.plusSeconds(1));
        UUID publisher = UUID.randomUUID();

        release.publish(3, "{\"items\":[]}", publisher, T0.plusSeconds(2));

        assertThat(release.getStatus()).isEqualTo(ReleaseStatus.PUBLISHED);
        assertThat(release.getCatalogVersion()).isEqualTo(3);
        assertThat(release.getSnapshot()).isEqualTo("{\"items\":[]}");
        assertThat(release.getEffectiveAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(release.getPublishedBy()).isEqualTo(publisher);
    }

    @Test
    void aDraftCannotBePublished() {
        ConfigurationRelease release = ConfigurationRelease.draft(STORE, AUTHOR, T0);

        assertThatThrownBy(() -> release.publish(1, "{}", AUTHOR, T0))
                .isInstanceOf(IllegalReleaseStateException.class)
                .isInstanceOf(ConflictException.class);
        assertThat(release.getStatus()).isEqualTo(ReleaseStatus.DRAFT);
    }

    @Test
    void aValidatedReleaseCanBeValidatedAgainAfterTheWorkingCopyChanged() {
        ConfigurationRelease release = ConfigurationRelease.draft(STORE, AUTHOR, T0);
        release.markValidated(T0);

        release.markValidated(T0.plusSeconds(5));

        assertThat(release.getStatus()).isEqualTo(ReleaseStatus.VALIDATED);
    }

    @Test
    void onlyThePublishedReleaseCanBeRetiredAndThatIsFinal() {
        ConfigurationRelease release = published();

        release.retire(T0.plusSeconds(10));

        assertThat(release.getStatus()).isEqualTo(ReleaseStatus.RETIRED);
        assertThat(release.getSnapshot()).isEqualTo("{}"); // the frozen facts stay
        assertThatThrownBy(() -> release.retire(T0)).isInstanceOf(IllegalReleaseStateException.class);
        assertThatThrownBy(() -> release.markValidated(T0)).isInstanceOf(IllegalReleaseStateException.class);
        assertThatThrownBy(() -> release.publish(9, "{}", AUTHOR, T0)).isInstanceOf(IllegalReleaseStateException.class);
        assertThatThrownBy(() -> ConfigurationRelease.draft(STORE, AUTHOR, T0).retire(T0))
                .isInstanceOf(IllegalReleaseStateException.class);
    }

    @Test
    void aPublishedReleaseCannotBeValidatedOrPublishedAgain() {
        ConfigurationRelease release = published();

        assertThatThrownBy(() -> release.markValidated(T0)).isInstanceOf(IllegalReleaseStateException.class);
        assertThatThrownBy(() -> release.publish(2, "{\"other\":1}", AUTHOR, T0))
                .isInstanceOf(IllegalReleaseStateException.class);
        assertThat(release.getSnapshot()).isEqualTo("{}");
    }

    private static ConfigurationRelease published() {
        ConfigurationRelease release = ConfigurationRelease.draft(STORE, AUTHOR, T0);
        release.markValidated(T0);
        release.publish(1, "{}", AUTHOR, T0);
        return release;
    }
}

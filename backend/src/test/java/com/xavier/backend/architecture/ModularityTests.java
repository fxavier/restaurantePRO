package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.xavier.backend.BackendApplication;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;
import org.springframework.modulith.docs.Documenter;

/**
 * Module boundaries (spec §6, D12, D13): every module declares {@code allowedDependencies} in its
 * {@code package-info.java}; only a module's root package and {@code @NamedInterface}s are public;
 * no cycles. Also writes the module documentation to {@code docs/architecture/modules/}.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(BackendApplication.class);

    @Test
    void modulesRespectTheirDeclaredBoundaries() {
        MODULES.verify();
    }

    @Test
    void anIllegalModuleDependencyIsDetected() {
        // test fixture: an "orders" module that calls "settlement" although spec §6 forbids it
        Violations violations = ApplicationModules
                .of("com.xavier.archfixture.modules", new ImportOption.OnlyIncludeTests())
                .detectViolations();

        assertThat(violations.hasViolations()).isTrue();
        assertThat(violations.getMessages()).anySatisfy(message -> assertThat(message).contains("settlement"));
    }

    /** Generator, not a rule: refreshes docs/architecture/modules/ and checks every module was documented. */
    @Test
    void generatesModuleDocumentation() {
        Path folder = Path.of("../docs/architecture/modules");
        new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder(folder.toString()))
                .writeDocumentation()
                .writeAggregatingDocument();

        assertThat(folder.resolve("components.puml")).isNotEmptyFile();
        assertThat(folder.resolve("all-docs.adoc")).isNotEmptyFile();
        MODULES.forEach(module -> assertThat(folder.resolve("module-" + module.getIdentifier() + ".adoc"))
                .isNotEmptyFile());
    }
}

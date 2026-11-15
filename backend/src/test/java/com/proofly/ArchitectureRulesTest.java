package com.proofly;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.proofly");

    @Test
    void controllersMustNotAccessTenancyInternalsDirectly() {
        noClasses()
                .that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().haveSimpleName("DataSourceConfig")
                .check(classes);
    }

    @Test
    void commonModuleMustNotDependOnFeatureModules() {
        noClasses()
                .that().resideInAPackage("..common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..tenancy..", "..identity..", "..audit..", "..notification..", "..billing..",
                        "..organization..", "..site..", "..attendance..", "..leave..", "..reporting.."
                )
                .check(classes);
    }

    @Test
    void servicesMustBeInFeaturePackages() {
        classes()
                .that().haveSimpleNameEndingWith("Service")
                .should().resideInAnyPackage(
                        "..tenancy..", "..identity..", "..audit..", "..notification..", "..billing..",
                        "..organization..", "..site..", "..attendance..", "..leave..", "..reporting.."
                )
                .check(classes);
    }
}

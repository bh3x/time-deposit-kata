package org.ikigaidigital.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Architecture rules for the legacy root package (ADR-0008). Further rules are added in the step that introduces
 * the code they check, so no rule runs against an empty set of classes.
 */
@AnalyzeClasses(packages = "org.ikigaidigital", importOptions = ImportOption.DoNotIncludeTests.class)
class LegacyRootPackageArchitectureTest {

    // ADR-0008 rule 3: the root package holds only the legacy classes and the Spring Boot main class (added later).
    @ArchTest
    static final ArchRule rootPackageContainsOnlyPermittedClasses = classes()
            .that().resideInAPackage("org.ikigaidigital")
            .should().haveNameMatching("org\\.ikigaidigital\\.(TimeDeposit|TimeDepositCalculator|TimeDepositApplication)");

    // ADR-0008 rule 5: the legacy classes use no framework. Until the calculator delegates to the interest
    // policies (rule 1's named exception, added in the refactoring step), both depend only on the JDK and each other.
    @ArchTest
    static final ArchRule legacyClassesDependOnlyOnTheJdk = classes()
            .that().belongToAnyOf(TimeDeposit.class, TimeDepositCalculator.class)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.ikigaidigital");
}

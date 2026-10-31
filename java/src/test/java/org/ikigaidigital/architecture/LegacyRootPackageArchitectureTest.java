package org.ikigaidigital.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Architecture rules for the legacy root package (ADR-0008). Further rules are added in the step that introduces
 * the code they check: ArchUnit fails a rule whose set of classes is empty (default {@code failOnEmptyShould}).
 */
@AnalyzeClasses(packages = "org.ikigaidigital", importOptions = ImportOption.DoNotIncludeTests.class)
class LegacyRootPackageArchitectureTest {

    // ADR-0008 rule 3: the root package holds only the legacy classes and the Spring Boot main class (added later).
    // Nested and compiler-generated classes (e.g. TimeDepositCalculator$1) belong to their top-level class.
    @ArchTest
    static final ArchRule rootPackageContainsOnlyPermittedClasses = classes()
            .that().resideInAPackage("org.ikigaidigital")
            .and().areTopLevelClasses()
            .should().haveNameMatching("org\\.ikigaidigital\\.(TimeDeposit|TimeDepositCalculator|TimeDepositApplication)");

    // ADR-0008 rule 3: TimeDeposit is the shared kernel and depends only on the JDK.
    @ArchTest
    static final ArchRule timeDepositDependsOnlyOnTheJdk = classes()
            .that().belongToAnyOf(TimeDeposit.class)
            .should().onlyDependOnClassesThat().resideInAPackage("java..");

    // ADR-0008 rule 5: the calculator uses no framework. It is widened to timedeposit.domain (rule 1's named
    // exception) when it starts delegating to the interest policies in the refactoring step.
    @ArchTest
    static final ArchRule calculatorDependsOnlyOnTheJdkAndTimeDeposit = classes()
            .that().belongToAnyOf(TimeDepositCalculator.class)
            .should().onlyDependOnClassesThat(
                    resideInAPackage("java..").or(belongToAnyOf(TimeDeposit.class, TimeDepositCalculator.class)));
}

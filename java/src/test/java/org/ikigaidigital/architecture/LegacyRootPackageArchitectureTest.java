package org.ikigaidigital.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositApplication;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.ikigaidigital.timedeposit.domain.interest.InterestPolicies;
import org.ikigaidigital.timedeposit.domain.interest.InterestPolicy;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Architecture rules for the legacy root package (ADR-0008). Further rules are added in the step that introduces
 * the code they check: ArchUnit fails a rule whose set of classes is empty (default {@code failOnEmptyShould}).
 */
@AnalyzeClasses(packages = "org.ikigaidigital", importOptions = ImportOption.DoNotIncludeTests.class)
class LegacyRootPackageArchitectureTest {

    // ADR-0008 rule 3: the root package holds only the legacy classes and the Spring Boot main class.
    // Nested and compiler-generated classes (e.g. TimeDepositCalculator$1) belong to their top-level class.
    @ArchTest
    static final ArchRule rootPackageContainsOnlyPermittedClasses = classes()
            .that().resideInAPackage("org.ikigaidigital")
            .and().areTopLevelClasses()
            .should().haveNameMatching("org\\.ikigaidigital\\.(TimeDeposit|TimeDepositCalculator|TimeDepositApplication)");

    // ADR-0008 decision 9: one Spring Boot application, in the root package, so scanning covers every capability.
    @ArchTest
    static final ArchRule onlyTimeDepositApplicationIsASpringBootApplication = classes()
            .that().areAnnotatedWith(SpringBootApplication.class)
            .should().haveFullyQualifiedName(TimeDepositApplication.class.getName());

    // ADR-0008 rule 3: TimeDeposit is the shared kernel and depends only on the JDK.
    @ArchTest
    static final ArchRule timeDepositDependsOnlyOnTheJdk = classes()
            .that().belongToAnyOf(TimeDeposit.class)
            .should().onlyDependOnClassesThat().resideInAPackage("java..");

    // ADR-0008 rule 5 and ADR-0011: the calculator uses no framework and knows no plan. Beyond the JDK and the kernel
    // it sees only the registry, the policy interface and the read-only terms (rule 1's named exception); concrete
    // policies such as RateWindowPolicy are not on the list, so plan knowledge cannot creep back in.
    @ArchTest
    static final ArchRule calculatorDependsOnlyOnTheJdkTheKernelAndThePolicyRegistry = classes()
            .that().belongToAnyOf(TimeDepositCalculator.class)
            .should().onlyDependOnClassesThat(
                    resideInAPackage("java..").or(belongToAnyOf(TimeDeposit.class, TimeDepositCalculator.class,
                            InterestPolicies.class, InterestPolicy.class, ReadOnlyTimeDeposit.class,
                            TimeDepositTerms.class)));
}

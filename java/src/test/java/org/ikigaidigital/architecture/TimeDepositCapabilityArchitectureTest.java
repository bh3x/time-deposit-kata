package org.ikigaidigital.architecture;

import com.tngtech.archunit.core.domain.JavaModifier;
import jakarta.persistence.Entity;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.shared.application.UseCase;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.ANNOTATIONS;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.conditions.ArchConditions.be;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Architecture rules for the timedeposit capability and the shared package (ADR-0008, Rules). The dependency rules
 * are written per capability, as ADR-0008 rule 1 allows; a new capability gets its own. Rules for adapters are added
 * in the steps that introduce them: ArchUnit fails a rule whose set of classes is empty.
 */
@AnalyzeClasses(packages = "org.ikigaidigital", importOptions = ImportOption.DoNotIncludeTests.class)
class TimeDepositCapabilityArchitectureTest {

    // Rule 1: the domain depends only on the JDK, itself and the kernel (TimeDepositCalculator counts as part of
    // timedeposit.domain, ADR-0008's named exception).
    @ArchTest
    static final ArchRule timeDepositDomainDependsOnlyOnTheKernel = classes()
            .that().resideInAPackage("org.ikigaidigital.timedeposit.domain..")
            .should().onlyDependOnClassesThat(
                    resideInAnyPackage("java..", "org.ikigaidigital.timedeposit.domain..")
                            .or(belongToAnyOf(TimeDeposit.class, TimeDepositCalculator.class)));

    // Rules 1 and 5: the application depends on its domain, the kernel and shared.application; from Spring only the
    // stereotype and transaction annotations.
    @ArchTest
    static final ArchRule timeDepositApplicationDependsOnlyOnItsDomainKernelAndSharedApplication = classes()
            .that().resideInAPackage("org.ikigaidigital.timedeposit.application..")
            .should().onlyDependOnClassesThat(
                    resideInAnyPackage(
                            "java..",
                            "org.ikigaidigital.timedeposit.application..",
                            "org.ikigaidigital.timedeposit.domain..",
                            "org.ikigaidigital.shared.application..",
                            "org.springframework.stereotype..",
                            "org.springframework.transaction.annotation..")
                            .or(belongToAnyOf(TimeDeposit.class, TimeDepositCalculator.class)));

    // Rule 2: capabilities and shared form no cycles (the root package is not a slice).
    @ArchTest
    static final ArchRule capabilitiesAreFreeOfCycles = slices()
            .matching("org.ikigaidigital.(*)..")
            .should().beFreeOfCycles();

    // Rule 4: shared depends on no capability — nothing in the project except itself and the kernel.
    @ArchTest
    static final ArchRule sharedDependsOnNoCapability = noClasses()
            .that().resideInAPackage("org.ikigaidigital.shared..")
            .should().dependOnClassesThat(resideInAPackage("org.ikigaidigital..")
                    .and(not(resideInAPackage("org.ikigaidigital.shared..")))
                    .and(not(belongToAnyOf(TimeDeposit.class))));

    // Rule 4 ("no business logic and no business ports"), read strictly for shared.application: annotations only.
    @ArchTest
    static final ArchRule sharedApplicationContainsOnlyAnnotations = classes()
            .that().resideInAPackage("org.ikigaidigital.shared.application..")
            .should(be(ANNOTATIONS));

    // Decision 5: ports are named by purpose with the suffix of their side.
    @ArchTest
    static final ArchRule drivingPortsEndWithUseCase = classes()
            .that().resideInAPackage("..application.port.in..").and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("UseCase");

    @ArchTest
    static final ArchRule drivenPortsEndWithPort = classes()
            .that().resideInAPackage("..application.port.out..").and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("Port");

    // Rule 7: every driving-port implementation is a @UseCase service, and @Transactional appears nowhere else.
    @ArchTest
    static final ArchRule useCaseImplementationsAreUseCaseServices = classes()
            .that().implement(resideInAPackage("..application.port.in.."))
            .should().resideInAPackage("..application.service..")
            .andShould().beAnnotatedWith(UseCase.class);

    @ArchTest
    static final ArchRule transactionalOnlyOnUseCaseServices = noClasses()
            .that().resideOutsideOfPackage("..application.service..")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");

    @ArchTest
    static final ArchRule noTransactionalMethodsOutsideUseCaseServices = noMethods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage("..application.service..")
            .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");

    // Decision 6: @UseCase is a Spring @Service stereotype.
    @ArchTest
    static final ArchRule useCaseIsAServiceStereotype = classes()
            .that().belongToAnyOf(UseCase.class)
            .should().beAnnotatedWith(Service.class);

    // Rule 5: JPA, Spring Data and Hibernate appear only in persistence adapters, and so do JPA entities.
    @ArchTest
    static final ArchRule jpaOnlyInPersistenceAdapters = noClasses()
            .that().resideOutsideOfPackage("..adapter.out.persistence..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..", "org.hibernate..");

    @ArchTest
    static final ArchRule entitiesOnlyInPersistenceAdapters = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..adapter.out.persistence..");

    // Rule 1, stricter for driven adapters: within the project they use only driven ports, the read model, the
    // domain and the kernel — never use cases, services or other adapters.
    @ArchTest
    static final ArchRule drivenAdaptersUseOnlyPortsModelsAndDomain = classes()
            .that().resideInAPackage("org.ikigaidigital.timedeposit.adapter.out..")
            .should().onlyDependOnClassesThat(
                    not(resideInAPackage("org.ikigaidigital.."))
                            .or(resideInAnyPackage(
                                    "org.ikigaidigital.timedeposit.adapter.out..",
                                    "org.ikigaidigital.timedeposit.application.port.out..",
                                    "org.ikigaidigital.timedeposit.application.model..",
                                    "org.ikigaidigital.timedeposit.domain.."))
                            .or(belongToAnyOf(TimeDeposit.class, TimeDepositCalculator.class)));

    @ArchTest
    static final ArchRule drivenAdaptersDoNotDependOnDrivingAdapters = noClasses()
            .that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.in..");

    // Decision 4 (G6 groundwork): the read model never exposes the mutable legacy object or an internal key.
    @ArchTest
    static final ArchRule readOnlyTermsAreFinal = classes()
            .that().implement(TimeDepositTerms.class)
            .should().haveModifier(JavaModifier.FINAL);

    @ArchTest
    static final ArchRule readOnlyTermsExposeNoMutableDeposit = noMethods()
            .that().areDeclaredInClassesThat().implement(TimeDepositTerms.class)
            .and().arePublic()
            .should().haveRawReturnType(TimeDeposit.class);

    @ArchTest
    static final ArchRule timeDepositTermsOffersOnlyItsThreeGetters = methods()
            .that().areDeclaredIn(TimeDepositTerms.class)
            .should().haveNameMatching("getPlanType|getBalance|getDays");

    @ArchTest
    static final ArchRule timeDepositViewHasOnlyItsPublicComponents = fields()
            .that().areDeclaredIn(TimeDepositView.class).and().areNotStatic()
            .should().haveNameMatching("id|terms|withdrawals");

    @ArchTest
    static final ArchRule withdrawalViewHasOnlyItsPublicComponents = fields()
            .that().areDeclaredIn(WithdrawalView.class).and().areNotStatic()
            .should().haveNameMatching("id|amount|date");
}

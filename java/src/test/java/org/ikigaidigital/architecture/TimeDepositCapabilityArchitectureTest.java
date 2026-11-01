package org.ikigaidigital.architecture;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.shared.application.UseCase;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

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

    // ADR-0011: the interest policies see deposits only through timedeposit.domain (TimeDepositTerms). Stricter than
    // rule 1, so they never reach the legacy kernel or the calculator that uses them.
    @ArchTest
    static final ArchRule interestPoliciesDependOnlyOnTheJdkAndTheirDomain = classes()
            .that().resideInAPackage("org.ikigaidigital.timedeposit.domain.interest..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.ikigaidigital.timedeposit.domain..");

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

    // Decision 6 and ADR-0012, B: @UseCase marks only driving-port implementations; transactional helpers of a use
    // case are plain @Service beans.
    @ArchTest
    static final ArchRule useCaseServicesImplementADrivingPort = classes()
            .that().areAnnotatedWith(UseCase.class)
            .should().implement(resideInAPackage("..application.port.in.."));

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

    // Rule 5: the types generated from openapi.yaml appear only in REST adapters, so the contract's representation
    // never leaks into the application or the domain.
    @ArchTest
    static final ArchRule generatedOpenApiTypesOnlyInRestAdapters = noClasses()
            .that().resideOutsideOfPackage("..adapter.in.rest..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.ikigaidigital.shared.adapter.in.rest.api..",
                    "org.ikigaidigital.shared.adapter.in.rest.model..");

    // Implied by the JPA rule above (annotations are dependencies); kept for its clearer failure message.
    @ArchTest
    static final ArchRule entitiesOnlyInPersistenceAdapters = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..adapter.out.persistence..");

    // Rule 1, stricter for the persistence adapter: within the project it uses only driven ports, the read model, the
    // domain, the kernel and shared technical code — never use cases, services or other adapters (shared.adapter.in
    // is excluded by the rule below).
    @ArchTest
    static final ArchRule persistenceAdapterUsesOnlyPortsModelsAndDomain = classes()
            .that().resideInAPackage("org.ikigaidigital.timedeposit.adapter.out.persistence..")
            .should().onlyDependOnClassesThat(
                    not(resideInAPackage("org.ikigaidigital.."))
                            .or(resideInAnyPackage(
                                    "org.ikigaidigital.timedeposit.adapter.out.persistence..",
                                    "org.ikigaidigital.timedeposit.application.port.out..",
                                    "org.ikigaidigital.timedeposit.application.model..",
                                    "org.ikigaidigital.timedeposit.domain..",
                                    "org.ikigaidigital.shared.."))
                            .or(belongToAnyOf(TimeDeposit.class, TimeDepositCalculator.class)));

    @ArchTest
    static final ArchRule drivenAdaptersDoNotDependOnDrivingAdapters = noClasses()
            .that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.in..");

    @ArchTest
    static final ArchRule drivingAdaptersDoNotDependOnDrivenAdapters = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

    // Rule 1, stricter for the REST adapter: within the project it uses only use cases, the read model, the domain,
    // the kernel and shared technical code (incl. the generated API) — never services, driven ports or adapters, and
    // not the calculator either: interest is applied through a use case, not from a controller.
    @ArchTest
    static final ArchRule restAdapterUsesOnlyUseCasesModelsAndDomain = classes()
            .that().resideInAPackage("org.ikigaidigital.timedeposit.adapter.in.rest..")
            .should().onlyDependOnClassesThat(
                    not(resideInAPackage("org.ikigaidigital.."))
                            .or(resideInAnyPackage(
                                    "org.ikigaidigital.timedeposit.adapter.in.rest..",
                                    "org.ikigaidigital.timedeposit.application.port.in..",
                                    "org.ikigaidigital.timedeposit.application.model..",
                                    "org.ikigaidigital.timedeposit.domain..",
                                    "org.ikigaidigital.shared.."))
                            .or(belongToAnyOf(TimeDeposit.class)));

    // Rule 6: every controller is a REST adapter implementing a generated API interface.
    @ArchTest
    static final ArchRule controllersImplementTheGeneratedApi = classes()
            .that().areMetaAnnotatedWith(Controller.class)
            .should().resideInAPackage("..adapter.in.rest..")
            .andShould().implement(resideInAPackage("org.ikigaidigital.shared.adapter.in.rest.api.."));

    // ADR-0005: the application maps only the contract's operations, so request mappings exist only in the generated
    // API and no functional endpoints are declared. Which routes are actually reachable (incl. /error and static
    // resources) is checked in the running context by RouteScopeEndToEndTest and RouteInventoryTest.
    @ArchTest
    static final ArchRule requestMappingsOnlyInTheGeneratedApi = noMethods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage("org.ikigaidigital.shared.adapter.in.rest.api..")
            .should().beMetaAnnotatedWith(RequestMapping.class);

    @ArchTest
    static final ArchRule classLevelRequestMappingsOnlyInTheGeneratedApi = noClasses()
            .that().resideOutsideOfPackage("org.ikigaidigital.shared.adapter.in.rest.api..")
            .should().beMetaAnnotatedWith(RequestMapping.class);

    @ArchTest
    static final ArchRule noFunctionalEndpoints = noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web.servlet.function..");

    // ADR-0005: web configuration is the easiest place to add a route by accident (view controllers, resource
    // handlers), so it may not touch those registries.
    @ArchTest
    static final ArchRule webConfigurationAddsNoRoutes = noClasses()
            .should().dependOnClassesThat().belongToAnyOf(ViewControllerRegistry.class, ResourceHandlerRegistry.class);

    // ADR-0010: one CORS policy, the global mapping in configuration, so allowed origins have a single source.
    @ArchTest
    static final ArchRule noCrossOriginAnnotationsOnClasses = noClasses()
            .should().beAnnotatedWith(CrossOrigin.class);

    @ArchTest
    static final ArchRule noCrossOriginAnnotationsOnMethods = noMethods()
            .should().beAnnotatedWith(CrossOrigin.class);

    // Controller advices are part of the REST adapters (ADR-0003 Problem Details), never of the core.
    @ArchTest
    static final ArchRule controllerAdvicesOnlyInRestAdapters = classes()
            .that().areMetaAnnotatedWith(ControllerAdvice.class)
            .should().resideInAPackage("..adapter.in.rest..");

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

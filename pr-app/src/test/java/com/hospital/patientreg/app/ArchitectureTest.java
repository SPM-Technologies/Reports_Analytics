package com.hospital.patientreg.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module boundaries of the modular monolith (see Docs/Solution-Architecture.html, section 6).
 * Maven dependencies already prevent most violations; these rules also catch them inside the WAR.
 */
@AnalyzeClasses(packages = "com.hospital.patientreg", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.hospital.patientreg.";

    @ArchTest
    static final ArchRule registrationAndReportingAreIndependent = noClasses()
            .that().resideInAPackage(BASE + "registration..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "reporting..");

    @ArchTest
    static final ArchRule reportingDoesNotTouchRegistration = noClasses()
            .that().resideInAPackage(BASE + "reporting..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "registration..");

    /** Registration and reporting see the signed-in user only through shared.tenant, never the identity module. */
    @ArchTest
    static final ArchRule businessModulesDoNotDependOnIdentity = noClasses()
            .that().resideInAnyPackage(BASE + "registration..", BASE + "reporting..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "identity..");

    @ArchTest
    static final ArchRule identityDoesNotDependOnBusinessModules = noClasses()
            .that().resideInAPackage(BASE + "identity..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "registration..", BASE + "reporting..");

    @ArchTest
    static final ArchRule sharedKernelDependsOnNoModule = noClasses()
            .that().resideInAPackage(BASE + "shared..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "registration..", BASE + "reporting..", BASE + "identity..", BASE + "app..");

    @ArchTest
    static final ArchRule modulesDoNotDependOnTheAssembly = noClasses()
            .that().resideInAnyPackage(BASE + "registration..", BASE + "reporting..", BASE + "identity..", BASE + "shared..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "app..");

    @ArchTest
    static final ArchRule webLayerDoesNotUseDaosDirectly = noClasses()
            .that().resideInAPackage("..registration.web..")
            .should().dependOnClassesThat().resideInAPackage("..registration.dao..");

    @ArchTest
    static final ArchRule noCyclesBetweenModules = slices().matching(BASE + "(*)..")
            .should().beFreeOfCycles();
}

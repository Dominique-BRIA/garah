package com.garah.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * L'architecture du chapitre 06, vérifiée à chaque build.
 *
 * <p>Une règle d'architecture qu'on ne teste pas est une règle qu'on viole le
 * jour où on est pressé — et personne ne le verra en relecture, parce qu'un
 * {@code import} de trop ne ressemble pas à une faute.</p>
 */
@DisplayName("Architecture")
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importer() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.garah.api");
    }

    @Test
    @DisplayName("aucun cycle entre les domaines")
    void aucunCycleEntreDomaines() {
        slices()
                .matching("com.garah.api.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    @Test
    @DisplayName("le domaine ne connaît pas le web")
    void leDomaineNeConnaitPasLeWeb() {
        noClasses()
                .that().resideInAPackage("..domaine..")
                .should().dependOnClassesThat().resideInAPackage("..web..")
                .because("""
                        une règle métier doit être vraie qu'on l'appelle depuis une API REST, \
                        depuis un import de fichier ou depuis un test""")
                .check(classes);
    }

    @Test
    @DisplayName("les entités JPA ne sortent pas du domaine")
    void lesEntitesRestentDansLeDomaine() {
        noClasses()
                .that().resideInAPackage("..web..")
                .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                .because("""
                        exposer une entité, c'est publier son schéma de base : \
                        un renommage de colonne casserait les trois frontends, \
                        et un mot de passe finirait un jour dans une réponse JSON""")
                .check(classes);
    }

    @Test
    @DisplayName("les couches respectent leur sens de dépendance")
    void lesCouchesRespectentLeurSens() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy("..web..")
                .layer("Domaine").definedBy("..domaine..")
                .layer("Infra").definedBy("..infra..")

                // Personne n'appelle le web : c'est le point d'entrée.
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                // L'infra sert le domaine, jamais l'inverse au sens des appels.
                .whereLayer("Infra").mayOnlyBeAccessedByLayers("Domaine", "Web")
                .check(classes);
    }
}

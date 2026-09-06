package com.garah.api.iam;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.domaine.*;
import com.garah.api.iam.infra.CasUtilisationRepository;
import com.garah.api.iam.infra.CategorieResponsableRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le calcul des permissions effectives, contre une vraie base PostgreSQL.
 *
 * <p>Pourquoi pas une base en mémoire (H2) ? Parce que tout ce qui protège
 * GARAH — index uniques partiels, contraintes d'exclusion, CTE, triggers —
 * n'existe pas en H2. Un test qui passe sur H2 et échoue en production est
 * pire qu'un test absent : il donne confiance à tort.</p>
 *
 * <p>{@code @Transactional} sur la classe fait annuler chaque test à la fin :
 * la base retrouve son état initial sans qu'on ait à nettoyer.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Permissions effectives d'un responsable")
class ServicePermissionsTest {

    @Autowired ServicePermissions service;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired CategorieResponsableRepository categories;
    @Autowired CasUtilisationRepository casUtilisation;
    @Autowired EntityManager em;

    private Responsable paul;

    @BeforeEach
    void preparerPaul() {
        Utilisateur u = new Utilisateur(TypeUtilisateur.RESPONSABLE,
                "Mbarga", "paul.test@garah.cm", "empreinte");
        utilisateurs.save(u);

        paul = responsables.save(new Responsable(u, "RESP-TEST-001"));

        // Deux profils qui partagent volontairement PRIX_MODIFIER :
        // c'est le cœur du piège de D-02.
        CategorieResponsable commercial = categories.save(new CategorieResponsable("Test Commercial"));
        commercial.accorder(cas("PRIX_MODIFIER"));
        commercial.accorder(cas("PRODUIT_PUBLIER"));

        CategorieResponsable logistique = categories.save(new CategorieResponsable("Test Logistique"));
        logistique.accorder(cas("PRIX_MODIFIER"));
        logistique.accorder(cas("EXPEDITION_CREER"));

        paul.ajouterCategorie(commercial, true);
        paul.ajouterCategorie(logistique, false);
        responsables.save(paul);

        em.flush();
    }

    private CasUtilisation cas(String code) {
        return casUtilisation.findByCode(code).orElseThrow();
    }

    @Test
    @DisplayName("les catégories multiples s'additionnent")
    void lesCategoriesSAdditionnent() {
        em.clear();

        Set<String> permissions = service.permissionsEffectives(paul.getId());

        assertThat(permissions)
                .contains("PRIX_MODIFIER", "PRODUIT_PUBLIER", "EXPEDITION_CREER")
                .doesNotContain("PAIEMENT_REMBOURSER");
    }

    @Test
    @DisplayName("le titre affiché vient de la catégorie principale")
    void leTitreVientDeLaCategoriePrincipale() {
        em.clear();

        Responsable charge = responsables.chargerAvecCategories(paul.getId()).orElseThrow();

        assertThat(charge.titre()).contains("Test Commercial");
    }

    @Test
    @DisplayName("un ADD ajoute une permission qu'aucune catégorie ne donne")
    void unAddAjouteUnePermission() {
        service.poserException(paul.getId(), "INCIDENT_CREER", TypeException.ADD,
                "Renfort logistique pendant les congés", null);
        em.flush();
        em.clear();

        assertThat(service.permissionsEffectives(paul.getId())).contains("INCIDENT_CREER");
    }

    /**
     * LE test qui compte : c'est l'exercice 5 du chapitre 04.
     *
     * <p>PRIX_MODIFIER vient de DEUX catégories. Un seul REMOVE doit le retirer
     * ENTIÈREMENT. Si la soustraction s'appliquait catégorie par catégorie,
     * la seconde redonnerait le droit et personne ne s'en apercevrait — sauf
     * le jour d'un contrôle sur une remise non autorisée.</p>
     */
    @Test
    @DisplayName("un REMOVE retire la permission même si DEUX catégories la donnent")
    void unRemoveRetireMemeSiDeuxCategoriesLaDonnent() {
        assertThat(service.permissionsEffectives(paul.getId())).contains("PRIX_MODIFIER");

        service.poserException(paul.getId(), "PRIX_MODIFIER", TypeException.REMOVE,
                "Retrait après incident sur une remise", null);
        em.flush();
        em.clear();

        Set<String> permissions = service.permissionsEffectives(paul.getId());

        assertThat(permissions).doesNotContain("PRIX_MODIFIER");
        // Les autres droits des deux catégories ne sont pas affectés.
        assertThat(permissions).contains("PRODUIT_PUBLIER", "EXPEDITION_CREER");
    }

    @Test
    @DisplayName("une exception sans motif est refusée")
    void uneExceptionSansMotifEstRefusee() {
        assertThatThrownBy(() ->
                service.poserException(paul.getId(), "INCIDENT_CREER", TypeException.ADD, "  ", null))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("motif");
    }

    @Test
    @DisplayName("on ne peut pas attribuer une permission qui n'existe pas")
    void onNePeutPasInventerUnePermission() {
        assertThatThrownBy(() ->
                service.poserException(paul.getId(), "PRODUIT_TELEPORTER", TypeException.ADD,
                        "essai", null))
                .isInstanceOf(RessourceIntrouvable.class);
    }

    @Test
    @DisplayName("un responsable inconnu déclenche une erreur explicite")
    void responsableInconnu() {
        assertThatThrownBy(() -> service.permissionsEffectives(-999L))
                .isInstanceOf(RessourceIntrouvable.class);
    }
}

package com.garah.api.iam;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.ServiceEquipe;
import com.garah.api.iam.domaine.ServiceProfilResponsable;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.VueMembre;
import com.garah.api.iam.domaine.VueProfil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La création des comptes internes.
 *
 * <p>🎯 <b>Ce chemin n'était couvert par aucun test</b> — et c'est celui dont
 * on nous a signalé, le 08/09/2026, qu'il « ne marchait pas ». La cause était
 * dans l'interface (un bouton désactivé sans un mot), mais l'absence de test
 * ici a rendu la question impossible à trancher autrement qu'en lisant le
 * code.</p>
 *
 * <p>Ce qui se vérifie : qu'un ADMIN et un RESPONSABLE se créent bien tous les
 * deux, et qu'ils ne se créent <b>pas de la même façon</b> — l'un n'est
 * affecté à aucun poste, l'autre reçoit matricule et profils. C'est la
 * distinction que le service porte, et elle ne se voit dans aucune signature.</p>
 */
@SpringBootTest
@DisplayName("Équipe : créer un compte interne")
class ServiceEquipeTest {

    private static final String ADMIN = "equipe.test.admin@garah.cm";
    private static final String RESPONSABLE = "equipe.test.resp@garah.cm";
    private static final String PROFIL = "Profil d essai equipe";

    /** Six caractères : le minimum retenu le 08/09/2026 pour un provisoire. */
    private static final String MOT_DE_PASSE = "abc123";

    @Autowired ServiceEquipe equipe;
    @Autowired ServiceProfilResponsable profils;
    @Autowired JdbcTemplate sql;

    private Long profilId;

    @BeforeEach
    void nettoyer() {
        // ⚠️ L'ordre suit les clés étrangères, du plus dépendant au moins :
        // les affectations, puis le poste, puis le compte. Inversé, la base
        // refuserait la suppression — et le test échouerait AVANT d'avoir rien
        // vérifié, sur un message qui ne parle pas de son sujet.
        for (String email : List.of(ADMIN, RESPONSABLE)) {
            sql.update("""
                    DELETE FROM responsable_categorie WHERE responsable_id IN (
                        SELECT id FROM utilisateur WHERE lower(email) = ?)
                    """, email);
            sql.update("""
                    DELETE FROM responsable_cas_utilisation WHERE responsable_id IN (
                        SELECT id FROM utilisateur WHERE lower(email) = ?)
                    """, email);
            sql.update("""
                    DELETE FROM responsable WHERE id IN (
                        SELECT id FROM utilisateur WHERE lower(email) = ?)
                    """, email);
            sql.update("DELETE FROM utilisateur WHERE lower(email) = ?", email);
        }

        // ⚠️ Réutilisé s'il existe déjà, jamais recréé. Un profil ne se
        // supprime pas — il se désactive (les droits accordés doivent rester
        // explicables) — et le recréer à chaque test échouait dès le second
        // sur « Un profil porte déjà ce nom », avant toute vérification.
        profilId = profils.lister().stream()
                .filter(p -> PROFIL.equals(p.nom()))
                .map(VueProfil::id)
                .findFirst()
                .orElseGet(() -> profils.creer(PROFIL, "Pour les tests",
                        List.of("PRODUIT_CONSULTER")).id());
    }

    @Test
    @DisplayName("Un administrateur se crée sans poste ni profil")
    void creerUnAdministrateur() {
        VueMembre membre = equipe.creer(TypeUtilisateur.ADMIN, "Mbarga", "Jean",
                ADMIN, "+237 6 99 11 22 33", MOT_DE_PASSE, null, List.of(), null);

        assertThat(membre.id()).isNotNull();
        assertThat(membre.type()).isEqualTo("ADMIN");

        // ⚠️ Le coeur de la distinction : un administrateur agit sur le
        // systeme, il n'occupe pas un poste. Un matricule lui donnerait un
        // emploi qu'il n'a pas.
        assertThat(membre.matricule()).isNull();
        assertThat(membre.profils()).isEmpty();
    }

    @Test
    @DisplayName("Un responsable se crée avec un matricule et son profil principal")
    void creerUnResponsable() {
        VueMembre membre = equipe.creer(TypeUtilisateur.RESPONSABLE, "Ngo Bassong", "Aline",
                RESPONSABLE, null, MOT_DE_PASSE, LocalDate.of(2024, 3, 1),
                List.of(profilId), profilId);

        assertThat(membre.type()).isEqualTo("RESPONSABLE");
        assertThat(membre.matricule()).isNotBlank();
        assertThat(membre.dateEmbauche()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(membre.profils()).hasSize(1);

        // Le titre ne vient d'aucun champ « titre » : il est le nom du profil
        // principal. C'est ce qui l'empeche de diverger des droits reels.
        assertThat(membre.titre()).isEqualTo(PROFIL);
    }

    @Test
    @DisplayName("Six caractères suffisent pour un mot de passe provisoire")
    void unMotDePasseCourtEstAccepte() {
        // Le seuil vit dans l'annotation du controleur, pas ici. Ce test dit
        // seulement que le SERVICE ne pose pas de regle concurrente : le jour
        // ou quelqu'un en ajoutera une, la creation cesserait de fonctionner
        // sans que le controleur ait change.
        assertThat(MOT_DE_PASSE).hasSize(6);

        VueMembre membre = equipe.creer(TypeUtilisateur.ADMIN, "Mbarga", null,
                ADMIN, null, MOT_DE_PASSE, null, List.of(), null);

        assertThat(membre.id()).isNotNull();
    }

    @Test
    @DisplayName("La même adresse ne sert pas deux fois")
    void refuseUneAdresseDejaPrise() {
        equipe.creer(TypeUtilisateur.ADMIN, "Mbarga", null, ADMIN, null,
                MOT_DE_PASSE, null, List.of(), null);

        assertThatThrownBy(() -> equipe.creer(TypeUtilisateur.RESPONSABLE, "Autre", null,
                ADMIN.toUpperCase(), null, MOT_DE_PASSE, null, List.of(profilId), profilId))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("existe déjà");
    }

    @Test
    @DisplayName("Un super-administrateur ne se crée pas depuis cet écran")
    void refuseLesTypesNonCreables() {
        assertThatThrownBy(() -> equipe.creer(TypeUtilisateur.SUPER_ADMIN, "Mbarga", null,
                ADMIN, null, MOT_DE_PASSE, null, List.of(), null))
                .isInstanceOf(RegleMetierViolee.class);

        // Un client s'inscrit lui-meme : le creer ici court-circuiterait la
        // verification d'adresse e-mail.
        assertThatThrownBy(() -> equipe.creer(TypeUtilisateur.CLIENT, "Mbarga", null,
                ADMIN, null, MOT_DE_PASSE, null, List.of(), null))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("Sans photo déposée, la vue ne porte aucune adresse")
    void sansPhotoAucuneAdresse() {
        VueMembre membre = equipe.creer(TypeUtilisateur.ADMIN, "Mbarga", null,
                ADMIN, null, MOT_DE_PASSE, null, List.of(), null);

        // ⚠️ null, et non une chaine vide ou une adresse qui pointe nulle part.
        // C'est ce que gu-avatar attend pour engendrer un avatar a partir du
        // nom ; une adresse morte afficherait une image cassee.
        assertThat(membre.urlPhoto()).isNull();
    }
}

package com.garah.api.iam;

import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.FicheClient;
import com.garah.api.iam.domaine.ResumeClient;
import com.garah.api.iam.domaine.ServiceClient;
import com.garah.api.iam.domaine.StatutUtilisateur;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les clients vus du back-office.
 *
 * <p>🎯 Le point que ces tests protègent n'est pas la pagination : c'est que
 * la liste soit une <b>projection</b>, et non une entité convertie. Le jour où
 * {@code Utilisateur} gagne une colonne sensible, {@link ResumeClient} ne doit
 * pas la recevoir — et c'est un test de forme, pas de comportement, qui
 * l'attrape.</p>
 */
@SpringBootTest
@DisplayName("Clients (back-office)")
class ServiceClientTest {

    private static final String CODE = "CLI-BO-1";
    private static final String EMAIL = "client.bo@garah.cm";

    @Autowired ServiceClient service;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private Long clientId;

    @BeforeEach
    void preparer() {
        nettoyer();

        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.CLIENT, "Mballa", EMAIL, "x"));
            u.marquerEmailVerifie();
            u.setTelephone("+237690000000");
            clientId = clients.save(new Client(u, CODE)).getId();
        });
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM client WHERE code_client = ?", CODE);
        jdbc.update("DELETE FROM utilisateur WHERE email = ?", EMAIL);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("la liste se cherche par code, par nom ou par e-mail")
    void rechercheSurTroisChamps() {
        // Un agent au téléphone a l'un des trois, jamais les trois. Ce test
        // exécute réellement la projection : une @Query cassée n'échouerait
        // qu'au moment où on l'appelle.
        assertThat(idsTrouves(null, CODE)).contains(clientId);
        assertThat(idsTrouves(null, "Mballa")).contains(clientId);
        assertThat(idsTrouves(null, "client.bo")).contains(clientId);

        assertThat(idsTrouves(null, "INTROUVABLE-XYZ")).isEmpty();
    }

    @Test
    @DisplayName("la liste ne transporte aucun secret")
    void projectionSansSecret() {
        ResumeClient vu = service.administration(null, CODE, PageRequest.of(0, 25))
                .getContent()
                .getFirst();

        assertThat(vu.nom()).isEqualTo("Mballa");
        assertThat(vu.emailVerifie()).isTrue();

        // 🎯 LE TEST QUI COMPTE. Le record n'a pas de champ pour un mot de
        //    passe, et ne doit jamais en gagner un. Ajouter un champ ici sans
        //    y penser ferait passer ce test au rouge — c'est exactement le but.
        assertThat(ResumeClient.class.getRecordComponents())
                .extracting("name")
                .containsExactly("id", "code", "nom", "email", "telephone",
                        "emailVerifie", "statut", "dateInscription");
    }

    @Test
    @DisplayName("suspendre met INACTIF, jamais BLOQUE")
    void suspension() {
        FicheClient suspendu = service.activer(clientId, false);

        // INACTIF est une décision administrative ; BLOQUE est une décision de
        // sécurité, prise sur alerte, et elle appartient à la surveillance.
        // Les confondre ferait passer pour fraudeur un client simplement
        // désactivé.
        assertThat(suspendu.statut()).isEqualTo(StatutUtilisateur.INACTIF.name());
        assertThat(suspendu.statut()).isNotEqualTo(StatutUtilisateur.BLOQUE.name());

        assertThat(idsTrouves(StatutUtilisateur.ACTIF, CODE)).doesNotContain(clientId);
        assertThat(idsTrouves(StatutUtilisateur.INACTIF, CODE)).contains(clientId);

        assertThat(service.activer(clientId, true).statut())
                .isEqualTo(StatutUtilisateur.ACTIF.name());
    }

    @Test
    @DisplayName("on corrige le téléphone, jamais l'adresse e-mail")
    void modificationLimitee() {
        FicheClient modifie = service.modifier(clientId, "+237699111222", "en");

        assertThat(modifie.telephone()).isEqualTo("+237699111222");
        assertThat(modifie.langue()).isEqualTo("en");

        // L'e-mail identifie le compte et sert à s'y connecter. Le changer
        // depuis le back-office reviendrait à donner le compte d'un client à
        // quelqu'un d'autre — le service n'a même pas le paramètre pour.
        assertThat(modifie.email()).isEqualTo(EMAIL);

        // Un téléphone vidé devient nul, pas une chaîne vide : « pas de
        // numéro » et « numéro vide » se confondraient à l'affichage.
        assertThat(service.modifier(clientId, "   ", null).telephone()).isNull();
    }

    @Test
    @DisplayName("la fiche dit quand le client ne s'est jamais connecté")
    void jamaisConnecte() {
        FicheClient fiche = service.fiche(clientId);

        // Une date nulle est une INFORMATION : un compte créé et jamais
        // utilisé explique à lui seul la moitié des « je n'ai rien reçu ».
        assertThat(fiche.dateDerniereConnexion()).isNull();
        assertThat(fiche.code()).isEqualTo(CODE);
        assertThat(fiche.utilisateurId()).isNotNull();
    }

    private java.util.List<Long> idsTrouves(StatutUtilisateur statut, String recherche) {
        return service.administration(statut, recherche, PageRequest.of(0, 25))
                .getContent().stream()
                .map(ResumeClient::id)
                .toList();
    }
}

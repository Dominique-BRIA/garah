package com.garah.api.iam;

import com.garah.api.iam.domaine.Client;
import com.garah.api.iam.domaine.ResultatConnexion;
import com.garah.api.iam.domaine.ServiceInscription;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.infra.ClientRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'inscription — la porte d'entrée de toute la plateforme.
 *
 * <p>D-07 impose un compte pour commander. Sans ce chemin, aucun client ne peut
 * exister autrement que par un {@code INSERT} manuel, et le tunnel de vente
 * entier est inatteignable.</p>
 */
@SpringBootTest
@DisplayName("Inscription d'un client")
class ServiceInscriptionTest {

    private static final String EMAIL = "nouvelle.cliente@garah.cm";

    @Autowired ServiceInscription inscription;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired JdbcTemplate sql;

    @BeforeEach
    void nettoyer() {
        // On efface les comptes de ce test, dans l'ordre des clés étrangères.
        sql.update("""
                DELETE FROM client WHERE id IN (
                    SELECT id FROM utilisateur WHERE lower(email) LIKE '%@garah.cm'
                      AND lower(email) LIKE 'nouvelle%')
                """);
        sql.update("DELETE FROM utilisateur WHERE lower(email) LIKE 'nouvelle%@garah.cm'");
    }

    @Test
    @DisplayName("crée un utilisateur ET sa ligne client, dans la même transaction")
    void creeLesDeuxLignes() {
        inscription.inscrire(EMAIL, "un-mot-de-passe-long", "Ngo Bassong",
                "Aline", "+237 6 99 00 00 00", "fr", "10.0.0.1");

        Optional<com.garah.api.iam.domaine.Utilisateur> utilisateur =
                utilisateurs.findByEmailIgnoreCase(EMAIL);

        assertThat(utilisateur).isPresent();
        assertThat(utilisateur.get().getType()).isEqualTo(TypeUtilisateur.CLIENT);

        // 🎯 Le vrai objet du test. Un utilisateur SANS ligne client se
        // connecterait normalement, puis échouerait au premier ajout au
        // panier — et le défaut ne se verrait qu'au moment de payer.
        Optional<Client> client = clients.findById(utilisateur.get().getId());
        assertThat(client).isPresent();
        assertThat(client.get().getCodeClient()).startsWith("CLI-");
    }

    @Test
    @DisplayName("le nouvel inscrit est connecté immédiatement")
    void renvoieUnJetonUtilisable() {
        ResultatConnexion resultat = inscription.inscrire(
                EMAIL, "un-mot-de-passe-long", "Ngo Bassong", null, null, "fr", "10.0.0.1");

        assertThat(resultat.jeton()).isNotBlank();
        assertThat(resultat.type()).isEqualTo(TypeUtilisateur.CLIENT);

        // Un client n'a AUCUNE permission : son accès repose sur la propriété
        // de ses données, pas sur des droits (chapitre 08).
        assertThat(resultat.permissions()).isEmpty();
    }

    @Test
    @DisplayName("deux inscriptions ne partagent jamais le même code client")
    void lesCodesClientSontUniques() {
        var premiere = inscription.inscrire(EMAIL, "un-mot-de-passe-long",
                "Première", null, null, "fr", "10.0.0.1");
        var seconde = inscription.inscrire("nouvelle.seconde@garah.cm", "un-mot-de-passe-long",
                "Seconde", null, null, "fr", "10.0.0.1");

        String codeA = clients.findById(premiere.utilisateurId()).orElseThrow().getCodeClient();
        String codeB = clients.findById(seconde.utilisateurId()).orElseThrow().getCodeClient();

        // La séquence PostgreSQL (V20) est atomique. Un `count(*) + 1`
        // donnerait le même code à deux inscriptions simultanées, et la
        // contrainte UNIQUE ferait échouer une inscription valide.
        assertThat(codeA).isNotEqualTo(codeB);
    }

    @Test
    @DisplayName("refuse une adresse déjà utilisée, quelle que soit la casse")
    void refuseUnDoublonDAdresse() {
        inscription.inscrire(EMAIL, "un-mot-de-passe-long", "Première",
                null, null, "fr", "10.0.0.1");

        // ⚠️ Majuscules. L'index unique porte sur lower(email) : si le service
        // cherchait avec une comparaison sensible à la casse, il laisserait
        // passer, et la base refuserait — une 500 au lieu d'un message clair.
        assertThatThrownBy(() -> inscription.inscrire(
                "NOUVELLE.CLIENTE@GARAH.CM", "un-autre-mot-de-passe", "Doublon",
                null, null, "fr", "10.0.0.1"))
                .isInstanceOf(ServiceInscription.AdresseDejaUtilisee.class);
    }

    @Test
    @DisplayName("refuse un mot de passe trop court")
    void refuseUnMotDePasseFaible() {
        assertThatThrownBy(() -> inscription.inscrire(
                EMAIL, "court", "Ngo Bassong", null, null, "fr", "10.0.0.1"))
                .isInstanceOf(ServiceInscription.MotDePasseTropFaible.class);

        // Et rien n'a été créé : un refus ne doit pas laisser d'utilisateur
        // orphelin derrière lui.
        assertThat(utilisateurs.findByEmailIgnoreCase(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("le mot de passe n'est jamais stocké en clair")
    void leMotDePasseEstHache() {
        String enClair = "un-mot-de-passe-long";
        inscription.inscrire(EMAIL, enClair, "Ngo Bassong", null, null, "fr", "10.0.0.1");

        String stocke = utilisateurs.findByEmailIgnoreCase(EMAIL).orElseThrow().getMotDePasse();

        assertThat(stocke).isNotEqualTo(enClair);
        // BCrypt, coût 12 — jamais MD5 ni SHA-256, qui sont conçus pour être
        // rapides et donc parfaits pour un attaquant.
        assertThat(stocke).startsWith("$2a$12$");
    }

    @Test
    @DisplayName("une langue inconnue retombe sur le français")
    void langueInconnueRetombeSurFrancais() {
        inscription.inscrire(EMAIL, "un-mot-de-passe-long", "Ngo Bassong",
                null, null, "klingon", "10.0.0.1");

        // Refuser serait plus strict, mais la langue n'est qu'un confort
        // d'affichage : faire échouer une inscription pour un code exotique
        // envoyé par un frontend mal configuré serait absurde.
        assertThat(utilisateurs.findByEmailIgnoreCase(EMAIL).orElseThrow().getLangue())
                .isEqualTo("fr");
    }
}

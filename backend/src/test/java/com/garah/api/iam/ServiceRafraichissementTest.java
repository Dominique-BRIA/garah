package com.garah.api.iam;

import com.garah.api.iam.domaine.JetonRafraichissement;
import com.garah.api.iam.domaine.ServiceInscription;
import com.garah.api.iam.domaine.ServiceRafraichissement;
import com.garah.api.iam.domaine.StatutUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.JetonRafraichissementRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le jeton de rafraîchissement, et ce qu'il rend enfin possible (D-19).
 *
 * <p>D-16 assumait un défaut : un compte bloqué gardait son accès jusqu'à
 * l'expiration de son jeton. Ces tests vérifient que ce n'est plus vrai — et
 * surtout, ils <b>verrouillent</b> le comportement, pour que personne ne le
 * défasse par inadvertance en simplifiant le code.</p>
 */
@SpringBootTest
@DisplayName("Jetons de rafraîchissement")
class ServiceRafraichissementTest {

    private static final String EMAIL = "session.test@garah.cm";
    private static final String MOT_DE_PASSE = "un-mot-de-passe-long";
    private static final String IP = "10.0.0.42";

    @Autowired ServiceRafraichissement sessions;
    @Autowired ServiceInscription inscription;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired JetonRafraichissementRepository jetons;
    @Autowired JdbcTemplate sql;

    private Long utilisateurId;

    @BeforeEach
    void preparer() {
        sql.update("""
                DELETE FROM jeton_rafraichissement WHERE utilisateur_id IN (
                    SELECT id FROM utilisateur WHERE lower(email) = ?)
                """, EMAIL);
        sql.update("DELETE FROM client WHERE id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", EMAIL);
        sql.update("DELETE FROM evenement_securite WHERE utilisateur_id IN (SELECT id FROM utilisateur WHERE lower(email) = ?)", EMAIL);
        sql.update("DELETE FROM utilisateur WHERE lower(email) = ?", EMAIL);

        utilisateurId = inscription.inscrire(EMAIL, MOT_DE_PASSE, "Session",
                null, null, "fr", IP).utilisateurId();
    }

    /** Ouvre une session et renvoie le jeton de rafraîchissement en clair. */
    private String ouvrir() {
        var utilisateur = utilisateurs.findById(utilisateurId).orElseThrow();
        var connexion = new com.garah.api.iam.domaine.ResultatConnexion(
                "jeton-factice", 900L, utilisateur.getId(), utilisateur.getType(),
                utilisateur.getNom(), utilisateur.getLangue(),
                // Aucune photo : ce test porte sur la rotation des jetons, pas
                // sur le profil. Le champ existe depuis que la connexion
                // renvoie la cle de l'avatar.
                null, java.util.Set.<String>of());

        return sessions.ouvrirSession(connexion, IP).jetonRafraichissement();
    }

    // -------------------------------------------------------------------------
    // Le fonctionnement normal
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un jeton valide donne un couple neuf")
    void rafraichitNormalement() {
        String premier = ouvrir();

        var couple = sessions.rafraichir(premier, IP);

        assertThat(couple.jetonAcces()).isNotBlank();
        assertThat(couple.jetonRafraichissement()).isNotBlank();

        // 🎯 ROTATION : le nouveau jeton est DIFFERENT de l'ancien.
        // Sans rotation, un jeton vole resterait valable quatorze jours sans
        // que rien ne permette de s'en apercevoir.
        assertThat(couple.jetonRafraichissement()).isNotEqualTo(premier);
    }

    @Test
    @DisplayName("le jeton n'est jamais stocké en clair")
    void leJetonEstHache() {
        String enClair = ouvrir();

        // Aucune ligne ne doit contenir la valeur en clair : cette table est
        // une table de mots de passe deguisee.
        Integer trouvees = sql.queryForObject(
                "SELECT count(*) FROM jeton_rafraichissement WHERE empreinte = ?",
                Integer.class, enClair);

        assertThat(trouvees).isZero();
        assertThat(jetons.findByEmpreinte(enClair)).isEmpty();
    }

    @Test
    @DisplayName("la rotation reste dans la même famille")
    void laRotationGardeLaFamille() {
        String premier = ouvrir();
        var famillePremier = jetons.findAll().stream()
                .filter(j -> j.getUtilisateurId().equals(utilisateurId))
                .findFirst().orElseThrow().getFamille();

        sessions.rafraichir(premier, IP);

        // Tous les jetons de cette session partagent la meme famille : c'est
        // ce qui permettra de tous les couper d'un coup en cas de vol.
        assertThat(jetons.findAll().stream()
                .filter(j -> j.getUtilisateurId().equals(utilisateurId))
                .map(JetonRafraichissement::getFamille))
                .containsOnly(famillePremier);
    }

    // -------------------------------------------------------------------------
    // La détection de vol
    // -------------------------------------------------------------------------

    /**
     * <b>Le test central de D-19.</b>
     *
     * <p>Un jeton déjà consommé qui revient signifie qu'il a été volé — sans
     * qu'on puisse savoir si c'est la victime ou le voleur qui appelle. On
     * coupe donc les deux.</p>
     */
    @Test
    @DisplayName("réutiliser un jeton consommé coupe TOUTE la famille")
    void laReutilisationCoupeLaFamille() {
        String premier = ouvrir();
        var couple = sessions.rafraichir(premier, IP);
        String second = couple.jetonRafraichissement();

        // Le voleur (ou la victime) represente le PREMIER jeton, deja consomme.
        assertThatThrownBy(() -> sessions.rafraichir(premier, IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);

        // ⚠️ Le SECOND jeton — parfaitement valide jusqu'ici — doit lui aussi
        // etre coupe. Ne revoquer que celui presente punirait la victime et
        // laisserait le voleur continuer avec le jeton issu de la rotation.
        assertThatThrownBy(() -> sessions.rafraichir(second, IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);

        assertThat(jetons.findAll().stream()
                .filter(j -> j.getUtilisateurId().equals(utilisateurId)))
                .allMatch(j -> j.getDateRevocation() != null);
    }

    @Test
    @DisplayName("la réutilisation garde la trace du vol, sans l'écraser")
    void laTraceDuVolEstPreservee() {
        String premier = ouvrir();
        sessions.rafraichir(premier, IP);

        assertThatThrownBy(() -> sessions.rafraichir(premier, IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);

        // Au moins un jeton porte REUTILISATION : c'est la seule preuve qu'il
        // faut alerter l'utilisateur. Une rotation ulterieure ne doit jamais
        // ecraser ce motif.
        assertThat(jetons.findAll().stream()
                .filter(j -> j.getUtilisateurId().equals(utilisateurId))
                .map(JetonRafraichissement::getMotifRevocation))
                .contains(JetonRafraichissement.Motif.REUTILISATION);
    }

    // -------------------------------------------------------------------------
    // Ce que D-16 ne savait pas faire
    // -------------------------------------------------------------------------

    /**
     * 🎯 <b>Le défaut que D-16 assumait, et qui disparaît ici.</b>
     *
     * <p>« Un compte bloqué garde lui aussi son jeton valide jusqu'à
     * expiration. » Ce n'est plus vrai : le rafraîchissement relit le compte
     * en base, donc le blocage prend effet au plus tard 15 minutes après.</p>
     */
    @Test
    @DisplayName("un compte bloqué ne peut plus rafraîchir")
    void unCompteBloqueNePeutPlusRafraichir() {
        String jeton = ouvrir();

        Utilisateur utilisateur = utilisateurs.findById(utilisateurId).orElseThrow();
        utilisateur.setStatut(StatutUtilisateur.BLOQUE);
        utilisateurs.save(utilisateur);

        assertThatThrownBy(() -> sessions.rafraichir(jeton, IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);

        // Et toutes ses sessions sont coupees, pas seulement celle-ci.
        assertThat(jetons.findAll().stream()
                .filter(j -> j.getUtilisateurId().equals(utilisateurId)))
                .allMatch(j -> j.getDateRevocation() != null);
    }

    @Test
    @DisplayName("la déconnexion agit immédiatement")
    void laDeconnexionEstImmediate() {
        String jeton = ouvrir();

        sessions.fermerSession(jeton);

        assertThatThrownBy(() -> sessions.rafraichir(jeton, IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);
    }

    @Test
    @DisplayName("se déconnecter deux fois ne lève pas")
    void seDeconnecterDeuxFoisEstSansEffet() {
        String jeton = ouvrir();

        sessions.fermerSession(jeton);
        // Second clic, onglet oublie, session deja expiree : le cas NORMAL.
        // Le resultat voulu est atteint dans tous les cas.
        sessions.fermerSession(jeton);
        sessions.fermerSession(null);
    }

    // -------------------------------------------------------------------------
    // Les refus simples
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un jeton inconnu est refusé sans rien révoquer")
    void unJetonInconnuEstRefuse() {
        ouvrir();

        assertThatThrownBy(() -> sessions.rafraichir("jeton-totalement-invente", IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);

        // ⚠️ La session legitime doit SURVIVRE. Sinon n'importe qui
        // deconnecterait n'importe qui en postant des jetons au hasard.
        assertThat(jetons.findAll().stream()
                .filter(j -> j.getUtilisateurId().equals(utilisateurId)))
                .allMatch(j -> j.getDateRevocation() == null);
    }

    @Test
    @DisplayName("un jeton vide ou nul est refusé")
    void unJetonVideEstRefuse() {
        assertThatThrownBy(() -> sessions.rafraichir(null, IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);
        assertThatThrownBy(() -> sessions.rafraichir("   ", IP))
                .isInstanceOf(ServiceRafraichissement.RafraichissementRefuse.class);
    }

    @Test
    @DisplayName("le message de refus ne dit jamais POURQUOI")
    void leMessageNeRenseignePasLAttaquant() {
        String jeton = ouvrir();
        sessions.fermerSession(jeton);

        // Inconnu et revoque doivent produire le MEME message. Distinguer les
        // deux renseignerait un attaquant sur l'etat de sa cible.
        String surRevoque = messageDeRefus(jeton);
        String surInconnu = messageDeRefus("jeton-totalement-invente");

        assertThat(surRevoque).isEqualTo(surInconnu);
    }

    private String messageDeRefus(String jeton) {
        try {
            sessions.rafraichir(jeton, IP);
            throw new AssertionError("Le rafraîchissement aurait dû être refusé.");
        } catch (ServiceRafraichissement.RafraichissementRefuse e) {
            return e.getMessage();
        }
    }
}

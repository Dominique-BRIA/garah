package com.garah.api.iam;

import com.garah.api.iam.domaine.ProfilUtilisateur;
import com.garah.api.iam.domaine.ServiceInscription;
import com.garah.api.iam.domaine.ServiceProfil;
import com.garah.api.iam.domaine.ServiceRafraichissement;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Son propre compte : le corriger, et changer son mot de passe.
 *
 * <p>Deux choses sont vérifiées ici qu'aucune relecture ne rattrape :
 * qu'un champ vide <b>efface</b> au lieu d'être ignoré, et qu'un changement de
 * mot de passe <b>coupe réellement les sessions</b> — le point sur lequel
 * repose toute la valeur de l'opération.</p>
 */
@SpringBootTest
@DisplayName("Profil : ses propres informations")
class ServiceProfilTest {

    private static final String EMAIL = "profil.test@garah.cm";
    private static final String MOT_DE_PASSE = "un-mot-de-passe-long";

    @Autowired ServiceProfil profils;
    @Autowired ServiceInscription inscription;
    @Autowired com.garah.api.iam.domaine.ServiceAuthentification authentification;
    @Autowired ServiceRafraichissement sessions;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired PasswordEncoder encodeur;
    @Autowired JdbcTemplate sql;

    private Long utilisateurId;

    @BeforeEach
    void creerUnCompte() {
        sql.update("""
                DELETE FROM jeton_rafraichissement WHERE utilisateur_id IN (
                    SELECT id FROM utilisateur WHERE lower(email) = ?)
                """, EMAIL);
        sql.update("""
                DELETE FROM client WHERE id IN (
                    SELECT id FROM utilisateur WHERE lower(email) = ?)
                """, EMAIL);
        sql.update("DELETE FROM utilisateur WHERE lower(email) = ?", EMAIL);

        inscription.inscrire(EMAIL, MOT_DE_PASSE, "Ngo Bassong",
                "Aline", "+237 6 99 00 00 00", "fr", "10.0.0.1");

        utilisateurId = utilisateurs.findByEmailIgnoreCase(EMAIL).orElseThrow().getId();
    }

    // -------------------------------------------------------------------------
    // Lecture
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rend ce que la base contient, pas ce que le jeton porte")
    void litLaBase() {
        ProfilUtilisateur profil = profils.lire(utilisateurId);

        // L'e-mail et le téléphone ne sont PAS dans le JWT : les voir ici est
        // exactement ce qui justifie que cette route existe à côté de /moi.
        assertThat(profil.email()).isEqualTo(EMAIL);
        assertThat(profil.telephone()).isEqualTo("+237 6 99 00 00 00");
        assertThat(profil.nom()).isEqualTo("Ngo Bassong");
        assertThat(profil.emailVerifie()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Modification
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un champ absent n'est pas modifié")
    void champAbsentIgnore() {
        profils.modifier(utilisateurId, "Ngo Bassong Épouse Tchana", null, null, null, "10.0.0.1");

        ProfilUtilisateur apres = profils.lire(utilisateurId);
        assertThat(apres.nom()).isEqualTo("Ngo Bassong Épouse Tchana");
        // Le prénom et le téléphone n'étaient pas dans la demande : intacts.
        assertThat(apres.prenom()).isEqualTo("Aline");
        assertThat(apres.telephone()).isEqualTo("+237 6 99 00 00 00");
    }

    /**
     * ⚠️ La distinction qui fait tout l'intérêt du {@code PATCH}.
     *
     * <p>Sans elle, on ne pourrait jamais <b>retirer</b> son numéro de
     * téléphone : la chaîne vide serait traitée comme « pas de changement », et
     * l'ancien numéro resterait en base pour toujours.</p>
     */
    @Test
    @DisplayName("un champ vide efface la valeur")
    void champVideEfface() {
        profils.modifier(utilisateurId, null, null, "", null, "10.0.0.1");

        assertThat(profils.lire(utilisateurId).telephone()).isNull();
    }

    @Test
    @DisplayName("un nom vide est ignoré plutôt qu'appliqué")
    void nomVideIgnore() {
        profils.modifier(utilisateurId, "   ", null, null, null, "10.0.0.1");

        // Un compte sans nom n'a nulle part où s'afficher : on garde l'ancien.
        assertThat(profils.lire(utilisateurId).nom()).isEqualTo("Ngo Bassong");
    }

    @Test
    @DisplayName("une langue inconnue retombe sur le français, sans échouer")
    void langueInconnueRetombeSurFrancais() {
        profils.modifier(utilisateurId, null, null, null, "zz", "10.0.0.1");

        // La MÊME règle qu'à l'inscription. Si ce test casse un jour, c'est
        // que les deux chemins ont divergé.
        assertThat(profils.lire(utilisateurId).langue()).isEqualTo("fr");
    }

    // -------------------------------------------------------------------------
    // Mot de passe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le mot de passe actuel est exigé, même authentifié")
    void refuseUnMauvaisMotDePasseActuel() {
        assertThatThrownBy(() ->
                profils.changerMotDePasse(utilisateurId, "ce-n-est-pas-le-bon",
                        "un-nouveau-mot-de-passe", "10.0.0.1"))
                .isInstanceOf(ServiceProfil.MotDePasseActuelIncorrect.class);

        // Et surtout : rien n'a bougé en base.
        assertThat(encodeur.matches(MOT_DE_PASSE,
                utilisateurs.findById(utilisateurId).orElseThrow().getMotDePasse())).isTrue();
    }

    @Test
    @DisplayName("un nouveau mot de passe trop court est refusé")
    void refuseUnMotDePasseTropCourt() {
        assertThatThrownBy(() ->
                profils.changerMotDePasse(utilisateurId, MOT_DE_PASSE, "court", "10.0.0.1"))
                .isInstanceOf(ServiceInscription.MotDePasseTropFaible.class);
    }

    @Test
    @DisplayName("réutiliser le mot de passe actuel est refusé")
    void refuseUnMotDePasseInchange() {
        assertThatThrownBy(() ->
                profils.changerMotDePasse(utilisateurId, MOT_DE_PASSE, MOT_DE_PASSE, "10.0.0.1"))
                .isInstanceOf(ServiceProfil.MotDePasseInchange.class);
    }

    @Test
    @DisplayName("le changement remplace réellement l'empreinte")
    void changeLEmpreinte() {
        profils.changerMotDePasse(utilisateurId, MOT_DE_PASSE, "un-autre-mot-de-passe", "10.0.0.1");

        String empreinte = utilisateurs.findById(utilisateurId).orElseThrow().getMotDePasse();
        assertThat(encodeur.matches("un-autre-mot-de-passe", empreinte)).isTrue();
        assertThat(encodeur.matches(MOT_DE_PASSE, empreinte)).isFalse();
    }

    /**
     * 🎯 <b>Le test qui justifie toute l'opération.</b>
     *
     * <p>Le cas d'usage principal d'un changement de mot de passe est le vol.
     * Si les sessions ouvertes survivaient, le voleur garderait son jeton de
     * rafraîchissement <b>quatorze jours</b> (D-19) — et le propriétaire
     * croirait s'être protégé.</p>
     *
     * <p>Le motif écrit en base est vérifié aussi : il doit dire
     * {@code MOT_DE_PASSE_CHANGE} et non {@code COMPTE_FERME}. Un journal
     * d'audit ne se réécrit pas, et confondre les deux ferait lire, plus tard,
     * qu'un compte a été fermé alors qu'il ne l'a jamais été.</p>
     */
    @Test
    @DisplayName("changer le mot de passe coupe toutes les sessions ouvertes")
    void coupeToutesLesSessions() {
        // Deux VRAIES connexions : deux familles de jetons, comme deux
        // appareils. Fabriquer les lignes à la main testerait le SQL, pas le
        // chemin que les utilisateurs empruntent.
        sessions.ouvrirSession(
                authentification.connecter(EMAIL, MOT_DE_PASSE, "10.0.0.1"), "10.0.0.1");
        sessions.ouvrirSession(
                authentification.connecter(EMAIL, MOT_DE_PASSE, "10.0.0.2"), "10.0.0.2");

        Integer avant = sql.queryForObject("""
                SELECT count(*) FROM jeton_rafraichissement
                 WHERE utilisateur_id = ? AND date_revocation IS NULL
                """, Integer.class, utilisateurId);
        assertThat(avant).isGreaterThanOrEqualTo(2);

        profils.changerMotDePasse(utilisateurId, MOT_DE_PASSE, "un-autre-mot-de-passe", "10.0.0.1");

        Integer restantes = sql.queryForObject("""
                SELECT count(*) FROM jeton_rafraichissement
                 WHERE utilisateur_id = ? AND date_revocation IS NULL
                """, Integer.class, utilisateurId);
        assertThat(restantes).isZero();

        Integer avecLeBonMotif = sql.queryForObject("""
                SELECT count(*) FROM jeton_rafraichissement
                 WHERE utilisateur_id = ? AND motif_revocation = 'MOT_DE_PASSE_CHANGE'
                """, Integer.class, utilisateurId);
        assertThat(avecLeBonMotif).isGreaterThanOrEqualTo(2);
    }
}

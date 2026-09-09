package com.garah.api.commun;

import com.garah.api.iam.domaine.ServiceProfilResponsable;
import com.garah.api.iam.domaine.VueProfil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le journal des actions, de bout en bout.
 *
 * <p>Un service annonce ce qu'il fait, un écouteur l'écrit. Entre les deux, un
 * événement Spring : trois pièces, dont aucune ne signale son absence. Si le
 * {@code @Component} disparaissait de l'écouteur, tout continuerait à
 * fonctionner exactement pareil — sauf que plus rien ne serait tracé, et
 * personne ne le saurait avant d'avoir besoin d'une trace.</p>
 *
 * <p>Non {@code @Transactional} : le journal écrit en {@code REQUIRES_NEW},
 * dans sa propre transaction. Un rollback de test ne le nettoierait pas.</p>
 */
@SpringBootTest
@DisplayName("Journal des actions")
class JournalActionsTest {

    private static final String NOM_PROFIL = "Test Journal J";

    /** N'importe laquelle : un profil sans aucune permission est refusé. */
    private static final String PERMISSION = "SERVICE_MEMBRE_CONSULTER";

    private static final String EMAIL_ACTEUR = "acteur.journal@garah.cm";

    @Autowired ServiceProfilResponsable profils;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void preparer() {
        nettoyer();
    }

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
        // L'ordre compte : le journal référence le compte.
        jdbc.update("DELETE FROM audit_log WHERE entite = 'categorie_responsable'");
        jdbc.update("DELETE FROM categorie_responsable WHERE nom = ?", NOM_PROFIL);
        jdbc.update("DELETE FROM utilisateur WHERE email = ?", EMAIL_ACTEUR);
    }

    /** Un compte qui existe VRAIMENT, pour vérifier que le lien est bien posé. */
    private Long creerUnCompte() {
        jdbc.update("""
                INSERT INTO utilisateur (type, nom, email, mot_de_passe)
                VALUES ('ADMIN', 'Awa Bello', ?, 'empreinte')
                """, EMAIL_ACTEUR);
        return jdbc.queryForObject(
                "SELECT id FROM utilisateur WHERE email = ?", Long.class, EMAIL_ACTEUR);
    }

    private void agirEnTantQue(String sujet, String type, String nom, String email) {
        Jwt jeton = Jwt.withTokenValue("faux")
                .header("alg", "HS256")
                .subject(sujet)
                .claim("type", type)
                .claim("nom", nom)
                .claim("email", email)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jeton, AuthorityUtils.NO_AUTHORITIES));
    }

    private List<Map<String, Object>> lignes() {
        return jdbc.queryForList("""
                SELECT action, acteur_nom, acteur_email, entite_id, utilisateur_id,
                       ancienne_valeur::text AS avant, nouvelle_valeur::text AS apres
                  FROM audit_log
                 WHERE entite = 'categorie_responsable'
                 ORDER BY id
                """);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ un compte qui n'existe plus n'empêche PAS d'agir")
    void unCompteDisparuNEmpechePasDAgir() {
        // 🎯 CE TEST A TROUVÉ UN VRAI DÉFAUT.
        //
        //    `audit_log.utilisateur_id` référence `utilisateur`. Un jeton dont
        //    le sujet ne désigne plus personne faisait échouer l'insertion sur
        //    la clé étrangère — et l'échec remontait dans l'action journalisée.
        //    Autrement dit : le journal, censé être un témoin, devenait le
        //    maillon qui empêche une vente.
        //
        //    La ligne s'écrit désormais SANS LE LIEN. Le nom et l'adresse sont
        //    recopiés depuis le début, précisément pour ce cas : la trace dit
        //    toujours qui a agi.
        agirEnTantQue("999999999", "ADMIN", "Compte Disparu", "parti@garah.cm");

        profils.creer(NOM_PROFIL, null, List.of(PERMISSION));

        Map<String, Object> ligne = lignes().getFirst();
        assertThat(ligne).containsEntry("acteur_nom", "Compte Disparu");
        assertThat(ligne.get("utilisateur_id"))
                .as("le lien manque, la trace reste")
                .isNull();
    }

    @Test
    @DisplayName("un geste interne laisse une ligne, avec le nom de son auteur")
    void unGesteInterneLaisseUneLigne() {
        Long acteurId = creerUnCompte();
        agirEnTantQue(String.valueOf(acteurId), "ADMIN", "Awa Bello", EMAIL_ACTEUR);

        VueProfil profil = profils.creer(NOM_PROFIL, "Profil de test", List.of(PERMISSION));

        List<Map<String, Object>> tracees = lignes();
        assertThat(tracees).hasSize(1);
        assertThat(tracees.getFirst())
                .containsEntry("action", "PROFIL_CREER")
                .containsEntry("acteur_nom", "Awa Bello")
                .containsEntry("acteur_email", EMAIL_ACTEUR)
                .containsEntry("entite_id", profil.id())
                .containsEntry("utilisateur_id", acteurId);
    }

    @Test
    @DisplayName("une modification garde l'AVANT, que la base a déjà écrasé")
    void uneModificationGardeLAvant() {
        agirEnTantQue("42", "ADMIN", "Awa Bello", "awa@garah.cm");
        VueProfil profil = profils.creer(NOM_PROFIL, null, List.of(PERMISSION));

        profils.changerStatut(profil.id(), false);

        // 🎯 La table ne porte que le statut courant. Sans le cliché d'avant,
        //    « le profil a été désactivé » ne dit pas s'il était actif ou déjà
        //    hors service — et c'est précisément ce qu'on veut savoir.
        Map<String, Object> desactivation = lignes().get(1);
        assertThat(desactivation).containsEntry("action", "PROFIL_DESACTIVER");
        assertThat((String) desactivation.get("avant")).contains("ACTIF");
        assertThat((String) desactivation.get("apres")).contains("INACTIF");
    }

    @Test
    @DisplayName("⚠️ un CLIENT n'écrit rien dans le journal des actions internes")
    void unClientNEcritRien() {
        // Le cas ne se produit pas sur cette route-là — un client ne crée pas
        // de profil. Mais il se produit sur l'annulation d'une commande et sur
        // la demande de retour, où le même service sert les deux publics. Le
        // filtre est unique et vit dans l'écouteur : c'est ici qu'on le tient.
        agirEnTantQue("7", "CLIENT", "Jean Client", "jean@exemple.cm");

        profils.creer(NOM_PROFIL, null, List.of(PERMISSION));

        assertThat(lignes())
                .as("le parcours d'un client a son propre journal")
                .isEmpty();
    }

    @Test
    @DisplayName("hors requête, la ligne porte « Système » — et elle existe")
    void horsRequeteLaLigneExisteQuandMeme() {
        // Une tâche planifiée annule des commandes impayées. Sans acteur, on
        // aurait été tenté de ne rien écrire : « pourquoi ma commande a-t-elle
        // été annulée ? » n'aurait alors aucune réponse.
        profils.creer(NOM_PROFIL, null, List.of(PERMISSION));

        assertThat(lignes()).hasSize(1);
        assertThat(lignes().getFirst()).containsEntry("acteur_nom", "Système");
    }
}

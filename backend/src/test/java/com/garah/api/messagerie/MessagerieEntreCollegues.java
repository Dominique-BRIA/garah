package com.garah.api.messagerie;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.Responsable;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.messagerie.domaine.ServiceMessagerie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La messagerie interne relie TOUTE la maison.
 *
 * <h2>🎯 Une contrainte de schéma déguisée en principe</h2>
 *
 * <p>V30 faisait pointer les clés étrangères de la messagerie vers
 * {@code responsable}. Un ADMIN ou un SUPER_ADMIN n'a pas de ligne dans cette
 * table : ils en étaient <b>exclus</b>. L'API l'affichait même comme une
 * règle — « un compte d'administration n'y a pas de place ».</p>
 *
 * <p>⚠️ Personne n'avait décidé cela. C'était une conséquence du schéma, et
 * elle s'était habillée en principe métier le jour où on lui a écrit un
 * message d'erreur. L'administration fait partie de la maison : elle a autant
 * de raisons d'écrire à un chef de service qu'il en a de lui répondre.</p>
 *
 * <p>V32 repointe les cinq clés vers {@code utilisateur}. Comme
 * {@code responsable.id} <b>est</b> l'identifiant de l'utilisateur, aucune
 * ligne n'a bougé.</p>
 *
 * <p>⚠️ Il reste UNE exclusion, et elle est voulue : un CLIENT n'a rien à
 * faire dans une messagerie interne. La base ne sait pas l'exprimer —
 * {@code utilisateur} porte les quatre types — donc c'est le service qui le
 * vérifie, et c'est ce que ces tests tiennent.</p>
 */
@SpringBootTest
@DisplayName("Messagerie entre collègues")
class MessagerieEntreCollegues {

    private static final String EMAIL_A = "collegue.a@garah.cm";
    private static final String EMAIL_B = "collegue.b@garah.cm";
    private static final String EMAIL_PATRON = "patron.messagerie@garah.cm";
    private static final String EMAIL_CLIENT = "client.messagerie@garah.cm";

    @Autowired ServiceMessagerie messagerie;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    private Long a;
    private Long b;
    private Long patron;
    private Long client;

    @BeforeEach
    void creerLesComptes() {
        nettoyer();
        a = creerResponsable(EMAIL_A, "RES-MSG-A");
        b = creerResponsable(EMAIL_B, "RES-MSG-B");
        patron = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.SUPER_ADMIN, "Patron", EMAIL_PATRON, "x")).getId();

        // ⚠️ Sans ligne `client`, mais c'est sans importance : le service
        //    interroge le TYPE du compte, pas l'existence d'une ligne dans une
        //    table annexe. C'est précisément ce que V32 corrige.
        client = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.CLIENT, "Passant", EMAIL_CLIENT, "x")).getId();
    }

    private Long creerResponsable(String email, String matricule) {
        return transactions.execute(s -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.RESPONSABLE, "Ateba", email, "x"));
            responsables.save(new Responsable(u, matricule));
            return u.getId();
        });
    }

    @AfterEach
    void nettoyer() {
        for (String email : new String[] {EMAIL_A, EMAIL_B, EMAIL_PATRON, EMAIL_CLIENT}) {
            utilisateurs.findByEmailIgnoreCase(email).ifPresent(u -> {
                jdbc.update("DELETE FROM message_interne WHERE expediteur_id = ?", u.getId());
                jdbc.update("""
                        DELETE FROM fil_interne
                         WHERE utilisateur_a = ? OR utilisateur_b = ?
                        """, u.getId(), u.getId());
                responsables.findById(u.getId()).ifPresent(responsables::delete);
                utilisateurs.delete(u);
            });
        }
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deux membres de l'équipe s'écrivent")
    void deuxMembresSEcrivent() {
        var message = messagerie.envoyer(a, b, "Peux-tu regarder la commande ?");

        assertThat(message.contenu()).isEqualTo("Peux-tu regarder la commande ?");
        assertThat(messagerie.mesFils(b)).hasSize(1);
        assertThat(messagerie.mesFils(b).getFirst().nonLus()).isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ l'administration ÉCRIT, et c'est le sens de V32")
    void lAdministrationEcrit() {
        // Avant : une violation de clé étrangère, puis un message qui en
        // faisait une règle — « un compte d'administration n'y a pas de
        // place ». Personne ne l'avait décidé.
        var message = messagerie.envoyer(patron, a, "Peux-tu me rappeler ?");

        assertThat(message.contenu()).isEqualTo("Peux-tu me rappeler ?");
        assertThat(messagerie.mesFils(a)).hasSize(1);
    }

    @Test
    @DisplayName("⚠️ et on lui RÉPOND")
    void etOnLuiRepond() {
        messagerie.envoyer(patron, a, "Peux-tu me rappeler ?");
        var reponse = messagerie.envoyer(a, patron, "Je vous rappelle.");

        assertThat(reponse.contenu()).isEqualTo("Je vous rappelle.");
        // Le MÊME fil : un aller-retour n'ouvre pas deux conversations.
        assertThat(messagerie.mesFils(patron)).hasSize(1);
    }

    @Test
    @DisplayName("⚠️ un CLIENT reste dehors — la seule exclusion voulue")
    void unClientResteDehors() {
        assertThatThrownBy(() -> messagerie.envoyer(client, a, "Bonjour"))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("comptes de la maison");

        assertThatThrownBy(() -> messagerie.envoyer(a, client, "Bonjour"))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("membre de la maison");
    }

    @Test
    @DisplayName("on ne s'écrit pas à soi-même")
    void onNeSEcritPasASoiMeme() {
        assertThatThrownBy(() -> messagerie.envoyer(a, a, "Note pour moi"))
                .isInstanceOf(RegleMetierViolee.class);
    }
}

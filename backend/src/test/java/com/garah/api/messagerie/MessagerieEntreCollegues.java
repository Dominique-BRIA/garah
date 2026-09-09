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
 * La messagerie interne relie les membres de l'ÉQUIPE.
 *
 * <h2>🎯 Ce que ces tests ferment</h2>
 *
 * <p>{@code message_interne.expediteur_id} et {@code fil_interne} référencent
 * {@code responsable}. Un ADMIN ou un SUPER_ADMIN n'a <b>pas</b> de ligne dans
 * cette table : la clé étrangère refusait, et le gestionnaire d'erreurs
 * traduisait cela en « cette opération renvoie à un élément qui n'existe pas,
 * ou qui a été supprimé entre-temps ».</p>
 *
 * <p>⚠️ Le message était faux dans les <b>deux moitiés</b> de sa phrase : rien
 * n'avait été supprimé, et l'élément n'a jamais existé. On aurait cherché une
 * donnée disparue là où il fallait lire « ce compte n'est pas concerné par
 * cette fonctionnalité ».</p>
 *
 * <p>Trouvé en éprouvant l'écran neuf contre l'API réelle : le
 * super-administrateur voyait ses collègues joignables, et chaque envoi
 * échouait.</p>
 */
@SpringBootTest
@DisplayName("Messagerie entre collègues")
class MessagerieEntreCollegues {

    private static final String EMAIL_A = "collegue.a@garah.cm";
    private static final String EMAIL_B = "collegue.b@garah.cm";
    private static final String EMAIL_PATRON = "patron.messagerie@garah.cm";

    @Autowired ServiceMessagerie messagerie;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    private Long a;
    private Long b;
    private Long patron;

    @BeforeEach
    void creerLesComptes() {
        nettoyer();
        a = creerResponsable(EMAIL_A, "RES-MSG-A");
        b = creerResponsable(EMAIL_B, "RES-MSG-B");
        patron = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.SUPER_ADMIN, "Patron", EMAIL_PATRON, "x")).getId();
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
        for (String email : new String[] {EMAIL_A, EMAIL_B, EMAIL_PATRON}) {
            utilisateurs.findByEmailIgnoreCase(email).ifPresent(u -> {
                jdbc.update("DELETE FROM message_interne WHERE expediteur_id = ?", u.getId());
                jdbc.update("""
                        DELETE FROM fil_interne
                         WHERE responsable_a = ? OR responsable_b = ?
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
    @DisplayName("⚠️ un compte d'administration reçoit un refus CLAIR")
    void unCompteDAdministrationRecoitUnRefusClair() {
        // Avant : une violation de clé étrangère, traduite en « élément
        // supprimé entre-temps ». On cherchait une donnée disparue.
        assertThatThrownBy(() -> messagerie.envoyer(patron, a, "Bonjour"))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("membres de l'équipe");
    }

    @Test
    @DisplayName("⚠️ et on ne peut pas non plus ÉCRIRE à un compte d'administration")
    void onNEcritPasNonPlusAUnCompteDAdministration() {
        assertThatThrownBy(() -> messagerie.envoyer(a, patron, "Bonjour"))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("membre de l'équipe");
    }

    @Test
    @DisplayName("on ne s'écrit pas à soi-même")
    void onNeSEcritPasASoiMeme() {
        assertThatThrownBy(() -> messagerie.envoyer(a, a, "Note pour moi"))
                .isInstanceOf(RegleMetierViolee.class);
    }
}

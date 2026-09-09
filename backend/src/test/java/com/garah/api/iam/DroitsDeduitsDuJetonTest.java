package com.garah.api.iam;

import com.garah.api.iam.domaine.Responsable;
import com.garah.api.iam.domaine.ServiceAuthentification;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import com.garah.api.iam.securite.ServiceJeton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les droits d'un administrateur se déduisent, ils ne voyagent plus.
 *
 * <h2>⚠️ Pourquoi ces tests-là, et pas ceux qui existaient</h2>
 *
 * <p>Les autres tests d'autorisation passent par
 * {@code jwt().authorities("...")} : ils <b>posent</b> les autorisations
 * directement dans le contexte de sécurité et <b>ne traversent jamais le
 * convertisseur</b>. Ils seraient restés verts même si le convertisseur ne
 * rendait plus rien — c'est-à-dire même si tous les administrateurs avaient
 * perdu tous leurs droits.</p>
 *
 * <p>Ceux-ci fabriquent un <b>vrai jeton</b> et le présentent en en-tête
 * {@code Authorization}. C'est le seul chemin qui exerce la déduction.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Droits déduits du jeton")
class DroitsDeduitsDuJetonTest {

    private static final String EMAIL_PATRON = "patron.deduit@garah.cm";
    private static final String EMAIL_RESP = "resp.deduit@garah.cm";

    @Autowired MockMvc mvc;
    @Autowired ServiceJeton jetons;
    @Autowired ServiceAuthentification authentification;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired TransactionTemplate transactions;

    private Utilisateur patron;
    private Utilisateur responsable;

    @BeforeEach
    void creerLesComptes() {
        nettoyer();
        transactions.executeWithoutResult(s -> {
            utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.SUPER_ADMIN, "Patron", EMAIL_PATRON, "x"));
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.RESPONSABLE, "Ateba", EMAIL_RESP, "x"));
            responsables.save(new Responsable(u, "RESP-DED-001"));
        });
        patron = utilisateurs.findByEmailIgnoreCase(EMAIL_PATRON).orElseThrow();
        responsable = utilisateurs.findByEmailIgnoreCase(EMAIL_RESP).orElseThrow();
    }

    @AfterEach
    void nettoyer() {
        utilisateurs.findByEmailIgnoreCase(EMAIL_RESP).ifPresent(u -> {
            responsables.findById(u.getId()).ifPresent(responsables::delete);
            utilisateurs.delete(u);
        });
        utilisateurs.findByEmailIgnoreCase(EMAIL_PATRON).ifPresent(utilisateurs::delete);
    }

    private String jetonDe(Utilisateur u) {
        return jetons.creer(u, authentification.permissionsDe(u));
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ le jeton d'un super-administrateur n'énumère plus rien")
    void leJetonNEnumerePlusRien() {
        String jeton = jetonDe(patron);

        // 197 codes pesaient près de 7 Ko d'en-tête HTTP, sur CHAQUE appel — et
        // franchissaient la limite du serveur dès qu'un paramètre de requête
        // s'allongeait (D-34).
        assertThat(jeton.length())
                .as("le jeton doit rester une poignée d'octets")
                .isLessThan(600);
    }

    @Test
    @DisplayName("⚠️ et pourtant il ouvre une route du module SÉCURITÉ")
    void etPourtantIlOuvreLeModuleSecurite() throws Exception {
        // C'EST LE TEST. Le jeton ne porte aucune permission ; l'autorisation
        // vient du convertisseur, qui les déduit du claim `type`. Si cette
        // déduction tombait, ce serait 403 partout pour le compte qui a tous
        // les droits — et aucun autre test ne le verrait.
        mvc.perform(get("/api/surveillance/audit")
                        .header("Authorization", "Bearer " + jetonDe(patron)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("⚠️ un ADMIN reste tenu à l'écart du module SÉCURITÉ")
    void unAdminResteTenuALEcart() throws Exception {
        // La séparation des pouvoirs ne doit pas s'être perdue dans la
        // déduction : celui qui administre les comptes ne relit pas le journal
        // de ce qu'il a fait.
        Utilisateur admin = new Utilisateur(
                TypeUtilisateur.ADMIN, "Admin", "admin.deduit@garah.cm", "x");

        mvc.perform(get("/api/surveillance/audit")
                        .header("Authorization", "Bearer " + jetonDe(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un responsable, lui, porte toujours ses droits")
    void unResponsablePorteToujoursLesSiens() {
        // Ses droits SONT une donnée le concernant — ses profils, ses
        // exceptions. Rien ne permet de les recalculer sans lire la base, et
        // c'est ce que D-16 voulait éviter à chaque requête.
        Set<String> droits = authentification.permissionsDe(responsable);
        String jeton = jetons.creer(responsable, droits);

        assertThat(jeton).isNotBlank();
        assertThat(droits)
                .as("ce responsable n'a aucun profil : la liste est vide, pas déduite")
                .isEmpty();
    }
}

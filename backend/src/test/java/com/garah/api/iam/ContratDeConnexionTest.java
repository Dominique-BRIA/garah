package com.garah.api.iam;

import com.garah.api.iam.domaine.Responsable;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les NOMS des champs de la réponse de connexion sont un contrat.
 *
 * <h2>🎯 Ce que ce test empêche de se reproduire</h2>
 *
 * <p>Trois applications lisent cette réponse. Deux la lisaient <b>mal</b> :</p>
 *
 * <pre>
 * ce qu'elles cherchaient    ce que le serveur envoie
 * jetonAcces                 jeton
 * utilisateurId, nom, email  utilisateur : { id, nom, email }
 * </pre>
 *
 * <p>La boutique et le mobile étaient donc <b>incapables de se connecter</b>,
 * chacun de son côté, depuis assez longtemps pour que personne ne sache
 * quand. Le back-office, lui, lisait juste — c'est pourquoi le défaut est
 * resté invisible : l'application dont on se sert tous les jours marchait.</p>
 *
 * <h2>⚠️ Pourquoi rien ne le voyait</h2>
 *
 * <p>Aucune des trois ne vérifie quoi que ce soit à l'exécution. En
 * TypeScript, {@code post<ReponseConnexion>} est une promesse faite au
 * compilateur ; en Dart, lire une clé absente rend {@code null}. Le désaccord
 * ne se manifeste que devant l'utilisateur.</p>
 *
 * <p>Chaque client a maintenant son test de contrat. Celui-ci est le pendant
 * <b>côté serveur</b> : il fige les noms là où ils se décident. Renommer un
 * champ casse ici, dans le dépôt qui l'a renommé — pas trois semaines plus
 * tard, dans une application qu'on ne compilait pas.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Contrat de la réponse de connexion")
class ContratDeConnexionTest {

    private static final String EMAIL = "contrat.connexion@garah.cm";
    private static final String MOT_DE_PASSE = "MotDePasse123";

    @Autowired MockMvc mvc;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired PasswordEncoder encodeur;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void creerLeCompte() {
        nettoyer();
        transactions.executeWithoutResult(s -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.RESPONSABLE, "Ateba", EMAIL, encodeur.encode(MOT_DE_PASSE)));
            u.marquerEmailVerifie();
            responsables.save(new Responsable(u, "RESP-CONTRAT-1"));
        });
    }

    @AfterEach
    void nettoyer() {
        utilisateurs.findByEmailIgnoreCase(EMAIL).ifPresent(u -> {
            responsables.findById(u.getId()).ifPresent(responsables::delete);
            utilisateurs.delete(u);
        });
    }

    @Test
    @DisplayName("⚠️ les noms sont figés : trois applications en dépendent")
    void lesNomsSontFiges() throws Exception {
        mvc.perform(post("/api/auth/connexion")
                        .header("X-Garah-Client", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","motDePasse":"%s"}
                                """.formatted(EMAIL, MOT_DE_PASSE)))
                .andExpect(status().isOk())

                // Le jeton s'appelle « jeton ». Cherché sous « jetonAcces », il
                // valait null, et TOUS les appels suivants partaient sans
                // autorisation — en silence.
                .andExpect(jsonPath("$.jeton").isNotEmpty())
                .andExpect(jsonPath("$.jetonAcces").doesNotExist())

                .andExpect(jsonPath("$.typeJeton").value("Bearer"))
                .andExpect(jsonPath("$.expireDansSecondes").isNumber())

                // Le compte est IMBRIQUÉ. Lu à la racine, il donnait un objet
                // de champs vides — et comme il n'était pas nul, l'application
                // se déclarait connectée.
                .andExpect(jsonPath("$.utilisateur.id").isNumber())
                .andExpect(jsonPath("$.utilisateur.nom").isNotEmpty())
                .andExpect(jsonPath("$.utilisateur.type").isNotEmpty())
                .andExpect(jsonPath("$.utilisateurId").doesNotExist())

                // ⚠️ Le type est lu par la boutique pour refuser une session
                //    d'administration sur les écrans « mon compte ».
                .andExpect(jsonPath("$.utilisateur.type").value("RESPONSABLE"))

                // Les permissions voyagent dans le CORPS, jamais lues du jeton
                // par les frontends : c'est ce qui a permis de les retirer du
                // jeton d'un administrateur (D-35) sans rien casser à l'écran.
                .andExpect(jsonPath("$.permissions").isArray());
    }
}

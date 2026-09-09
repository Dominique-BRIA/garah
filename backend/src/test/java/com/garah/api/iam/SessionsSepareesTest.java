package com.garah.api.iam;

import com.garah.api.iam.domaine.Responsable;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.surveillance.infra.EvenementSecuriteRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La boutique et le back-office ne se marchent plus dessus.
 *
 * <h2>🎯 Le défaut que ces tests ferment (D-33)</h2>
 *
 * <p>Le cookie de rafraîchissement s'appelait {@code garah_refresh}, un seul
 * nom pour tout le monde. Le navigateur range un cookie sous la clé
 * <i>(domaine, chemin, nom)</i> : un nom unique voulait donc dire <b>une
 * session par navigateur</b>. Se connecter en client sur la boutique remplaçait
 * la session d'administration ouverte à côté, sans un mot.</p>
 *
 * <h2>⚠️ Pourquoi ça ne se voit pas à l'œil</h2>
 *
 * <p>Tout fonctionne. Les deux applications se connectent, se rafraîchissent,
 * se déconnectent. Le défaut ne se manifeste que si l'on ouvre <b>les deux</b>,
 * dans le <b>même navigateur</b>, à quelques minutes d'intervalle — et il se
 * lit alors comme une déconnexion inexpliquée. C'est exactement le genre de
 * cas qu'un test doit tenir, parce qu'aucune relecture ne le retrouvera.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sessions séparées par public")
class SessionsSepareesTest {

    private static final String EMAIL = "sessions.separees@garah.cm";
    private static final String MOT_DE_PASSE = "MotDePasse123";

    private static final String ENTETE = "X-Garah-Client";
    private static final String COOKIE_ADMIN = "garah_refresh_admin";
    private static final String COOKIE_BOUTIQUE = "garah_refresh_boutique";

    @Autowired MockMvc mvc;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired EvenementSecuriteRepository evenements;
    @Autowired PasswordEncoder encodeur;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void creerLeCompte() {
        nettoyer();
        // I-06 : un RESPONSABLE doit avoir sa ligne dans `responsable`, et les
        // deux ecritures tiennent dans la meme transaction.
        transactions.executeWithoutResult(statut -> {
            Utilisateur u = utilisateurs.save(new Utilisateur(
                    TypeUtilisateur.RESPONSABLE, "Ateba", EMAIL, encodeur.encode(MOT_DE_PASSE)));
            u.marquerEmailVerifie();
            responsables.save(new Responsable(u, "RESP-SEP-001"));
        });
    }

    @AfterEach
    void nettoyer() {
        utilisateurs.findByEmailIgnoreCase(EMAIL).ifPresent(u -> {
            evenements.deleteAll(evenements.findByUtilisateurIdOrderByDateHeureDesc(u.getId()));
            responsables.findById(u.getId()).ifPresent(responsables::delete);
            utilisateurs.delete(u);
        });
    }

    /** Se connecte en se declarant d'un public, et rend l'en-tete Set-Cookie. */
    private String connecterDepuis(String public_) throws Exception {
        MvcResult resultat = mvc.perform(post("/api/auth/connexion")
                        .header(ENTETE, public_)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","motDePasse":"%s"}
                                """.formatted(EMAIL, MOT_DE_PASSE)))
                .andExpect(status().isOk())
                .andReturn();

        String pose = resultat.getResponse().getHeader("Set-Cookie");
        assertThat(pose).as("la connexion doit poser un cookie").isNotNull();
        return pose;
    }

    private static String valeurDe(String enTeteSetCookie) {
        // « nom=valeur; Path=... » — on ne garde que la valeur.
        String premier = enTeteSetCookie.split(";", 2)[0];
        return premier.substring(premier.indexOf('=') + 1);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le back-office range sa session sous son propre nom")
    void leBackOfficeARangeSaSession() throws Exception {
        assertThat(connecterDepuis("admin")).startsWith(COOKIE_ADMIN + "=");
    }

    @Test
    @DisplayName("tout autre public range la sienne ailleurs")
    void laBoutiqueARangeLaSienneAilleurs() throws Exception {
        // La boutique n'a rien a declarer : elle envoie ce qu'elle envoyait
        // deja. C'est le back-office qui se nomme, et personne d'autre — une
        // application qui ignore la convention ne peut donc pas atterrir dans
        // sa session par accident.
        assertThat(connecterDepuis("1")).startsWith(COOKIE_BOUTIQUE + "=");
    }

    @Test
    @DisplayName("⚠️ le cookie de la boutique n'ouvre PAS une session d'administration")
    void leCookieDeLaBoutiqueNOuvrePasLAdministration() throws Exception {
        // C'est LE test. Le navigateur porte les deux cookies a la fois : rien
        // ne l'empeche de presenter celui de la boutique sur un appel du
        // back-office. Si la lecture cherchait « le premier jeton trouve », on
        // retomberait exactement sur le defaut repare.
        String jetonBoutique = valeurDe(connecterDepuis("1"));

        mvc.perform(post("/api/auth/rafraichir")
                        .header(ENTETE, "admin")
                        .cookie(new Cookie(COOKIE_BOUTIQUE, jetonBoutique)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("chacun rafraichit la sienne")
    void chacunRafraichitLaSienne() throws Exception {
        String jetonAdmin = valeurDe(connecterDepuis("admin"));

        mvc.perform(post("/api/auth/rafraichir")
                        .header(ENTETE, "admin")
                        .cookie(new Cookie(COOKIE_ADMIN, jetonAdmin)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("⚠️ se deconnecter d'un cote n'efface pas la session de l'autre")
    void seDeconnecterNEffacePasLAutre() throws Exception {
        String jetonAdmin = valeurDe(connecterDepuis("admin"));
        String jetonBoutique = valeurDe(connecterDepuis("1"));

        // La deconnexion revoque la FAMILLE du jeton presente. Presenter le
        // cookie de la boutique depuis le back-office ne doit donc rien
        // fermer : il n'est meme pas lu.
        mvc.perform(post("/api/auth/deconnexion")
                        .header(ENTETE, "admin")
                        .cookie(new Cookie(COOKIE_BOUTIQUE, jetonBoutique)))
                .andExpect(status().isNoContent());

        // Les deux sessions sont intactes : celle de la boutique parce qu'elle
        // n'a pas ete lue, celle du back-office parce qu'on n'y a pas touche.
        mvc.perform(post("/api/auth/rafraichir")
                        .header(ENTETE, "admin")
                        .cookie(new Cookie(COOKIE_ADMIN, jetonAdmin)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/rafraichir")
                        .header(ENTETE, "1")
                        .cookie(new Cookie(COOKIE_BOUTIQUE, jetonBoutique)))
                .andExpect(status().isOk());
    }
}

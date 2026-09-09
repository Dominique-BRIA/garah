package com.garah.api.commun;

import com.garah.api.commun.securite.Acteur;
import com.garah.api.commun.securite.ActeurCourant;
import com.garah.api.commun.securite.AdresseIp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui agit, et depuis où.
 *
 * <p>Chaque cas ci-dessous produit, quand il est faux, une ligne de journal
 * qui a l'air correcte : elle a une date, une action, une cible. Seul l'acteur
 * est faux — et personne ne le remarque avant d'avoir besoin de le lire.</p>
 *
 * <p>Sans Spring : {@code ActeurCourant} ne lit que deux porte-contextes,
 * qu'un test peut poser lui-même. Démarrer l'application pour vérifier une
 * lecture de jeton coûterait vingt secondes par exécution.</p>
 */
@DisplayName("L'acteur courant")
class ActeurCourantTest {

    private final ActeurCourant acteurs = new ActeurCourant();

    @AfterEach
    void nettoyer() {
        // ⚠️ Les deux contextes sont portés par le FIL D'EXÉCUTION, et les
        //    tests se partagent le fil. Sans ce nettoyage, un test laisserait
        //    son jeton au suivant — qui passerait, ou échouerait, selon
        //    l'ordre d'exécution.
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void authentifier(String sujet, String type, String nom, String email) {
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

    private void requeteDepuis(String enTeteTransmis) {
        MockHttpServletRequest requete = new MockHttpServletRequest();
        requete.setRemoteAddr("10.0.0.9");
        if (enTeteTransmis != null) {
            requete.addHeader("X-Forwarded-For", enTeteTransmis);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requete));
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un responsable est un acteur interne, nommé")
    void unResponsableEstInterne() {
        authentifier("42", "RESPONSABLE", "Awa Bello", "awa@garah.cm");

        Acteur acteur = acteurs.maintenant();

        assertThat(acteur.id()).isEqualTo(42L);
        assertThat(acteur.nom()).isEqualTo("Awa Bello");
        assertThat(acteur.email()).isEqualTo("awa@garah.cm");
        assertThat(acteur.estInterne()).isTrue();
    }

    @Test
    @DisplayName("⚠️ un client n'entre pas dans le journal des actions internes")
    void unClientNEstPasInterne() {
        // Le journal d'audit répond à « qui, chez nous, a touché à cette
        // donnée ». Y verser le parcours des clients le noierait sous le
        // trafic normal de la boutique, et les quelques gestes internes de la
        // journée deviendraient introuvables.
        authentifier("7", "CLIENT", "Jean Client", "jean@exemple.cm");

        assertThat(acteurs.maintenant().estInterne()).isFalse();
    }

    @Test
    @DisplayName("hors requête, l'acteur est le SYSTÈME — pas « inconnu »")
    void horsRequeteCEstLeSysteme() {
        // Une tâche planifiée n'a pas de jeton. Un acteur nul laisserait croire
        // à une donnée manquante ; « Système » dit ce qui s'est passé, et
        // distingue une commande expirée toute seule d'une commande annulée
        // par quelqu'un.
        Acteur acteur = acteurs.maintenant();

        assertThat(acteur.id()).isNull();
        assertThat(acteur.nom()).isEqualTo(Acteur.NOM_SYSTEME);
        assertThat(acteur.nature()).isEqualTo(Acteur.Nature.SYSTEME);
        assertThat(acteur.estInterne())
                .as("le système agit pour la maison : ses gestes se journalisent")
                .isTrue();
    }

    @Test
    @DisplayName("une authentification anonyme ne se prend pas pour un acteur")
    void anonymeEstLeSysteme() {
        // Son principal est une simple chaîne, pas un Jwt. Sans le contrôle de
        // type, Long.valueOf sur « anonymousUser » lèverait au milieu d'une
        // vente.
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("cle", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(acteurs.maintenant().nature()).isEqualTo(Acteur.Nature.SYSTEME);
    }

    @Test
    @DisplayName("⚠️ un type de compte inconnu est traité comme INTERNE")
    void unTypeInconnuEstInterne() {
        // Un jeton d'une version antérieure, un rôle ajouté demain. Mieux vaut
        // une ligne de journal en trop qu'un geste interne sans aucune trace.
        authentifier("3", "ARCHIVISTE", "Nouveau Role", "x@garah.cm");

        assertThat(acteurs.maintenant().estInterne()).isTrue();
    }

    @Test
    @DisplayName("un jeton sans nom reste un acteur identifiable")
    void unJetonSansNomResteIdentifiable() {
        // La colonne du nom est NOT NULL. Un nom vide ferait échouer
        // l'écriture du journal, donc l'action elle-même.
        authentifier("77", "ADMIN", null, null);

        assertThat(acteurs.maintenant().nom()).contains("77");
    }

    // -------------------------------------------------------------------------
    // L'adresse
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("l'adresse vient de X-Forwarded-For, premier élément")
    void lAdresseVientDuPremierProxy() {
        requeteDepuis("41.202.0.1, 10.0.0.1");

        assertThat(acteurs.maintenant().adresseIp()).isEqualTo("41.202.0.1");
    }

    @Test
    @DisplayName("⚠️ un en-tête fantaisiste ne doit pas empêcher une vente")
    void unEnTeteFantaisisteEstEcarte() {
        // La colonne est de type inet : PostgreSQL refuse ce qui n'est pas une
        // adresse, AU MOMENT DE L'ÉCRITURE. Sans filtre, une chaîne de dix
        // caractères dans un en-tête HTTP — fourni par le client, donc
        // falsifiable — ferait échouer le journal, donc l'action journalisée.
        requeteDepuis("<script>alert(1)</script>");

        assertThat(acteurs.maintenant().adresseIp()).isNull();
    }

    @Test
    @DisplayName("ce qui n'est pas une adresse est écarté, sans jamais résoudre un nom")
    void seulesLesAdressesPassent() {
        // ⚠️ Aucune résolution DNS : InetAddress.getByName partirait en requête
        //    réseau vers un serveur choisi par l'appelant.
        assertThat(AdresseIp.normaliser("41.202.0.1")).isEqualTo("41.202.0.1");
        assertThat(AdresseIp.normaliser("2001:db8::1")).isEqualTo("2001:db8::1");
        assertThat(AdresseIp.normaliser("garah.cm")).isNull();
        assertThat(AdresseIp.normaliser("abcdef")).isNull();
        assertThat(AdresseIp.normaliser("")).isNull();
        assertThat(AdresseIp.normaliser(null)).isNull();
    }
}

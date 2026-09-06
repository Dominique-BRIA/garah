package com.garah.api.commun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le premier test qui passe réellement par HTTP.
 *
 * <p><b>Pourquoi il arrive si tard, et pourquoi c'est un problème.</b> Tous les
 * tests écrits jusqu'ici appellent les services directement. C'est le bon
 * choix pour vérifier une règle métier — mais la chaîne de filtres de sécurité
 * n'est alors <b>jamais traversée</b>. Résultat : le contrôleur du catalogue
 * annonçait dans son commentaire « aucune authentification : la vitrine est
 * ouverte », alors que la configuration exigeait un jeton sur cette route
 * depuis le chapitre 08. Les 150 tests étaient verts.</p>
 *
 * <p>Le défaut n'aurait été découvert qu'au premier chargement de la page
 * d'accueil du site vitrine — c'est-à-dire après le déploiement.</p>
 *
 * <p>🎯 <b>La leçon :</b> une règle de sécurité vit dans la configuration, pas
 * dans le contrôleur. Un commentaire qui décrit un comportement que rien ne
 * vérifie finit toujours par mentir.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sécurité HTTP")
class SecuriteHttpTest {

    @Autowired MockMvc http;

    // -------------------------------------------------------------------------
    // Ce qui doit être ouvert
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("la vitrine lit le catalogue sans jeton")
    void leCatalogueEstPublic() throws Exception {
        http.perform(get("/api/produits"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("la fiche publique se lit sans jeton")
    void laFichePubliqueEstPublique() throws Exception {
        // Un slug inexistant : on attend 404, PAS 401.
        //
        // La distinction est le cœur du test. Un 401 dirait « la route est
        // fermée » ; un 404 dit « la route est ouverte, mais ce produit
        // n'existe pas ». C'est le second qu'on veut.
        http.perform(get("/api/produits/un-slug-qui-nexiste-pas"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("la configuration est lisible sans compte")
    void laConfigurationEstPublique() throws Exception {
        http.perform(get("/api/configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devise").value("XAF"))
                .andExpect(jsonPath("$.baseUrlMedias").exists());
    }

    @Test
    @DisplayName("la sonde de santé répond sans jeton")
    void laSanteEstPublique() throws Exception {
        http.perform(get("/api/sante"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Ce qui doit être fermé
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sans jeton, une route protégée répond 401 et non 500")
    void sansJetonCEst401() throws Exception {
        http.perform(get("/api/auth/moi"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/produits/administration/1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Le piège que ce test verrouille.
     *
     * <p>{@code GET /api/produits} est public. Si la règle avait été écrite
     * sans préciser la méthode, elle aurait ouvert du même coup
     * {@code POST /api/produits} — la <b>création</b> de produit.</p>
     *
     * <p>C'est la répétition exacte de l'erreur du chapitre 08 avec
     * {@code /api/auth/**}, sous une autre forme : là c'était un joker de
     * chemin, ici ce serait un joker de méthode.</p>
     */
    @Test
    @DisplayName("ouvrir le GET du catalogue n'ouvre pas son POST")
    void ouvrirLeGetNOuvrePasLePost() throws Exception {
        http.perform(post("/api/produits"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un jeton sans la bonne permission répond 403, pas 401")
    void jetonSansPermissionCEst403() throws Exception {
        http.perform(get("/api/produits/administration/1")
                        .with(jwt().jwt(j -> j.subject("1"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("COMMANDE_CONSULTER"))))
                .andExpect(status().isForbidden());
    }

    /**
     * 401 et 403 ne disent pas la même chose, et la confusion coûte cher en
     * support : « je suis connecté et ça ne marche pas ».
     *
     * <pre>
     * 401   je ne sais pas qui tu es        → reconnecte-toi
     * 403   je sais qui tu es, et tu n'as   → demande la permission
     *       pas le droit                      à un administrateur
     * </pre>
     */
    @Test
    @DisplayName("avec la permission, la route protégée est atteinte")
    void jetonAvecPermissionAtteintLeControleur() throws Exception {
        // Le produit 1 n'existe pas forcément : on attend donc 404, ce qui
        // prouve que la requête est allée jusqu'au service. Ni 401 ni 403.
        http.perform(get("/api/produits/administration/999999")
                        .with(jwt().jwt(j -> j.subject("1"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("PRODUIT_CONSULTER"))))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Les routes ouvertes par la mise en service de l'API (points 1 a 5)
    // -------------------------------------------------------------------------

    /**
     * L'inscription DOIT etre publique : c'est la porte d'entree.
     *
     * <p>D-07 impose un compte pour commander. Si cette route exigeait un
     * jeton, il faudrait deja un compte pour en creer un — et le tunnel de
     * vente entier serait inatteignable.</p>
     *
     * <p>On envoie un corps invalide et on attend 400 : la distinction est le
     * coeur du test. Un 401 dirait « route fermee » ; un 400 dit « route
     * ouverte, mais ta demande est mal formee ». C'est le second qu'on veut.</p>
     */
    @Test
    @DisplayName("l'inscription est ouverte sans jeton")
    void lInscriptionEstPublique() throws Exception {
        http.perform(post("/api/auth/inscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Le webhook de l'operateur ne peut pas etre authentifie.
     *
     * <p>Campay n'a pas de compte chez nous et ne portera jamais de jeton.
     * Ce qui protege cette route n'est donc PAS l'authentification, mais le
     * fait qu'elle ne croit rien de ce qu'on lui envoie : elle n'en retient
     * qu'une reference, puis redemande l'etat reel a Campay.</p>
     *
     * <p>Une reference inconnue doit repondre 200, jamais une erreur : un
     * operateur qui recoit autre chose que 200 rejoue sa notification
     * indefiniment.</p>
     */
    @Test
    @DisplayName("le webhook de paiement est ouvert et repond 200 sur une reference inconnue")
    void leWebhookEstPublic() throws Exception {
        http.perform(post("/api/paiements/notifications/campay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"reference-totalement-inventee\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traite").value(false));
    }

    @Test
    @DisplayName("les points de recuperation se lisent sans compte")
    void lesPointsDeRecuperationSontPublics() throws Exception {
        // Le client choisit son point AU MOMENT de la commande (D-05), et la
        // vitrine doit l'annoncer a un visiteur anonyme.
        http.perform(get("/api/lieux/points-recuperation"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("le suivi d'un colis se lit sans compte")
    void leSuiviEstPublic() throws Exception {
        // 404, PAS 401 — et c'est tout le sujet.
        //
        // Un 401 dirait « la route est fermee » ; un 404 dit « la route est
        // ouverte, mais ce numero de suivi n'existe pas ». C'est le second
        // qu'on veut, et c'est aussi le bon comportement metier : un numero
        // inconnu n'a aucun parcours a montrer.
        http.perform(get("/api/expeditions/suivi/NUMERO-INEXISTANT"))
                .andExpect(status().isNotFound());
    }

    /**
     * ⚠️ L'ORDRE des regles compte.
     *
     * <p>{@code /api/produits/tendance} doit etre declare AVANT
     * {@code /api/produits/{slug}}, sinon « tendance » serait pris pour un
     * slug — la route repondrait 404, et le bloc « produits tendance » de la
     * page d'accueil resterait vide sans qu'aucune erreur n'apparaisse.</p>
     */
    @Test
    @DisplayName("les produits tendance ne sont pas pris pour un slug")
    void lesProduitsTendanceSontPublics() throws Exception {
        http.perform(get("/api/produits/tendance"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Ce que la mise en service NE doit PAS avoir ouvert
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le panier exige un jeton")
    void lePanierEstFerme() throws Exception {
        http.perform(get("/api/panier"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("passer commande exige un jeton")
    void laCommandeEstFermee() throws Exception {
        http.perform(post("/api/commandes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointRecuperationId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Ouvrir le webhook n'ouvre pas le reste du paiement.
     *
     * <p>C'est la meme famille de piege que le joker de methode sur
     * {@code /api/produits} : une regle trop large sur {@code /api/paiements}
     * aurait laisse n'importe qui declencher un encaissement.</p>
     */
    @Test
    @DisplayName("ouvrir le webhook n'ouvre pas le declenchement de paiement")
    void leWebhookNOuvrePasLePaiement() throws Exception {
        http.perform(post("/api/paiements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandeId\":1,\"moyen\":\"MTN_MOMO\",\"telephone\":\"699000000\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("le journal d'audit exige la permission du module SECURITE")
    void lAuditEstReserve() throws Exception {
        http.perform(get("/api/surveillance/audit/produit/1"))
                .andExpect(status().isUnauthorized());

        // Meme avec un jeton valide : un droit quelconque ne suffit pas.
        http.perform(get("/api/surveillance/audit/produit/1")
                        .with(jwt().jwt(j -> j.subject("1"))
                                .authorities(new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("COMMANDE_CONSULTER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("le televersement de media exige une permission")
    void leTeleversementEstFerme() throws Exception {
        http.perform(post("/api/produits/1/medias"))
                .andExpect(status().isUnauthorized());
    }


    // -------------------------------------------------------------------------
    // La protection CSRF des routes a cookie (D-19)
    // -------------------------------------------------------------------------

    /**
     * ⚠️ Ce test protege un controle de SECURITE, pas un confort.
     *
     * <p>Le cookie de rafraichissement est en {@code SameSite=None} — il le
     * doit, Vercel et Render etant deux sites differents (D-14). Le navigateur
     * l'envoie donc aussi depuis une page tierce.</p>
     *
     * <p>Sans l'exigence d'un en-tete personnalise, n'importe quelle page
     * pourrait declencher un rafraichissement sur le dos d'un visiteur
     * connecte. L'en-tete force un preflight CORS, que seules nos origines
     * passent.</p>
     */
    @Test
    @DisplayName("rafraichir sans l'en-tete client est refuse")
    void rafraichirSansEnteteEstRefuse() throws Exception {
        http.perform(post("/api/auth/rafraichir"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTETE_CLIENT_MANQUANT"));
    }

    @Test
    @DisplayName("se deconnecter sans l'en-tete client est refuse")
    void deconnexionSansEnteteEstRefusee() throws Exception {
        http.perform(post("/api/auth/deconnexion"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTETE_CLIENT_MANQUANT"));
    }

    /**
     * Avec l'en-tete, la requete atteint le service — qui refuse faute de
     * cookie valide.
     *
     * <p>401 et non 403 : la distinction est le coeur du test. Un 403 voudrait
     * dire « bloque par le filtre » ; un 401 dit « le filtre a laisse passer,
     * et c'est la session qui manque ». C'est le second qu'on veut.</p>
     */
    @Test
    @DisplayName("avec l'en-tete, la requete atteint le service")
    void avecEnteteLaRequeteEstTraitee() throws Exception {
        http.perform(post("/api/auth/rafraichir")
                        .header("X-Garah-Client", "1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ⚠️ La regle porte sur un chemin EXACT, jamais un prefixe.
     *
     * <p>Un {@code startsWith("/api/auth")} fermerait aussi la connexion et
     * l'inscription — ou personne n'a encore de session. Se connecter
     * repondrait 403, et plus personne ne pourrait entrer.</p>
     */
    @Test
    @DisplayName("la connexion n'exige PAS l'en-tete client")
    void laConnexionNExigePasLEntete() throws Exception {
        // 400 (corps invalide), pas 403 : le filtre ne s'applique pas ici.
        http.perform(post("/api/auth/connexion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

}

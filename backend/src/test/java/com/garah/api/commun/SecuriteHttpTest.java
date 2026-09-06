package com.garah.api.commun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
}

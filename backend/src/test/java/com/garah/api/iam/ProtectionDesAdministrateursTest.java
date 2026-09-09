package com.garah.api.iam;

import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.Utilisateur;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Un responsable ne touche pas à un administrateur.
 *
 * <h2>🎯 Le trou que ces tests ferment</h2>
 *
 * <p>Créer un compte ADMIN exigeait déjà {@code ADMIN_CREER}. Mais
 * <b>modifier</b>, <b>activer</b>, <b>désactiver</b> et surtout <b>imposer un
 * mot de passe</b> ne regardaient que les droits {@code RESPONSABLE_*}.</p>
 *
 * <p>Un responsable à qui un profil accordait {@code RESPONSABLE_MODIFIER}
 * pouvait donc <b>réinitialiser le mot de passe d'un administrateur</b>, puis
 * se connecter à sa place. Ce n'est pas une gêne : c'est une prise de
 * compte.</p>
 *
 * <p>{@code ADMIN_MODIFIER}, {@code ADMIN_ACTIVER} et {@code ADMIN_DESACTIVER}
 * existaient au référentiel depuis l'origine, dans le module SÉCURITÉ. Ils
 * n'étaient vérifiés nulle part — déclarés, jamais appliqués.</p>
 *
 * <h2>⚠️ Ces tests passent par le contexte de sécurité, pas par un vrai jeton</h2>
 *
 * <p>C'est voulu ici : ce qu'on éprouve est l'<b>expression d'autorisation</b>,
 * et il faut pouvoir composer des jeux de droits qu'aucun type de compte ne
 * porte — un responsable avec {@code RESPONSABLE_MODIFIER} mais sans
 * {@code ADMIN_MODIFIER}. La déduction depuis le jeton est éprouvée ailleurs,
 * par {@code DroitsDeduitsDuJetonTest}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Protection des administrateurs")
class ProtectionDesAdministrateursTest {

    private static final String EMAIL_ADMIN = "admin.protege@garah.cm";
    private static final String EMAIL_RESP = "resp.protege@garah.cm";

    @Autowired MockMvc mvc;
    @Autowired UtilisateurRepository utilisateurs;

    private Long idAdmin;
    private Long idResponsable;

    @BeforeEach
    void creerLesCibles() {
        nettoyer();
        idAdmin = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.ADMIN, "Cible", EMAIL_ADMIN, "x")).getId();
        idResponsable = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.RESPONSABLE, "Cible", EMAIL_RESP, "x")).getId();
    }

    @AfterEach
    void nettoyer() {
        utilisateurs.findByEmailIgnoreCase(EMAIL_ADMIN).ifPresent(utilisateurs::delete);
        utilisateurs.findByEmailIgnoreCase(EMAIL_RESP).ifPresent(utilisateurs::delete);
    }

    /** Les droits d'un responsable qui gère l'équipe, SANS les droits ADMIN_*. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor sansDroitAdmin() {
        return jwt().authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("RESPONSABLE_MODIFIER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("RESPONSABLE_ACTIVER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("RESPONSABLE_DESACTIVER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor avecDroitAdmin() {
        return jwt().authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("RESPONSABLE_MODIFIER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("RESPONSABLE_ACTIVER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("RESPONSABLE_DESACTIVER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN_MODIFIER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN_ACTIVER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN_DESACTIVER"));
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ on ne réinitialise pas le mot de passe d'un administrateur")
    void onNeReinitialisePasLeMotDePasseDUnAdmin() throws Exception {
        // LE test. Imposer un mot de passe, c'est pouvoir se connecter a la
        // place de quelqu'un.
        mvc.perform(post("/api/equipe/{id}/mot-de-passe", idAdmin)
                        .with(sansDroitAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motDePasse\":\"NouveauMotDePasse123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("⚠️ on ne désactive pas un administrateur")
    void onNeDesactivePasUnAdmin() throws Exception {
        mvc.perform(delete("/api/equipe/{id}/activation", idAdmin).with(sansDroitAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("⚠️ on ne modifie pas un administrateur")
    void onNeModifiePasUnAdmin() throws Exception {
        mvc.perform(put("/api/equipe/{id}", idAdmin)
                        .with(sansDroitAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nom\":\"Renomme\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un responsable, lui, reste gérable")
    void unResponsableResteGerable() throws Exception {
        // ⚠️ La garde ne doit pas tout fermer : sans ce test, la rendre trop
        //    stricte passerait inapercu et l'ecran « Equipe » deviendrait
        //    inutilisable pour ceux a qui il est destine.
        mvc.perform(delete("/api/equipe/{id}/activation", idResponsable)
                        .with(sansDroitAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("le super-administrateur, lui, passe")
    void leSuperAdministrateurPasse() throws Exception {
        mvc.perform(delete("/api/equipe/{id}/activation", idAdmin).with(avecDroitAdmin()))
                .andExpect(status().isOk());
    }
}

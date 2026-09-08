package com.garah.api.iam;

import com.garah.api.iam.domaine.ServiceProfilResponsable;
import com.garah.api.iam.domaine.VueProfil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qui a le droit de fabriquer un administrateur.
 *
 * <h2>🎯 L'escalade que ces tests ferment</h2>
 *
 * <p>{@code POST /api/equipe} n'exigeait que {@code RESPONSABLE_CREER}, un
 * droit du module {@code ADMINISTRATION}. Un <b>responsable</b> à qui un
 * profil l'accordait pouvait donc créer un compte ADMIN et s'y connecter :
 * une permission devenait « tout sauf le module sécurité ».</p>
 *
 * <p>{@code ADMIN_CREER} existait pourtant depuis le référentiel d'origine,
 * dans le module {@code SECURITE}. Il n'était vérifié nulle part. <b>Un droit
 * déclaré et jamais appliqué ne protège rien, et se lit pourtant comme une
 * protection</b> — c'est ce qui rend ce genre de trou si durable.</p>
 *
 * <h2>Pourquoi ces tests passent par HTTP</h2>
 *
 * <p>La règle vit dans une annotation du contrôleur. Un test qui appellerait
 * {@code ServiceEquipe} directement ne la traverserait jamais et resterait
 * vert quoi qu'il arrive — le piège décrit dans {@code SecuriteHttpTest}.</p>
 *
 * <h2>⚠️ Les demandes sont VALIDES, et c'est obligatoire</h2>
 *
 * <p>Première version de ces tests : un corps volontairement incomplet, pour
 * ne rien créer en base. Les quatre répondaient <b>400</b>, y compris ceux qui
 * devaient répondre 403 — et la suite passait pour verte au premier coup
 * d'œil.</p>
 *
 * <p>La raison : {@code @Valid} s'applique à la <b>résolution des arguments</b>,
 * avant que l'intercepteur de sécurité n'entoure la méthode. Un corps invalide
 * n'atteint donc JAMAIS {@code @PreAuthorize}. Un test d'autorisation nourri
 * de données invalides ne teste pas l'autorisation.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Équipe : qui peut créer un administrateur")
class CreationAdministrateurTest {

    private static final String PROFIL = "Profil d essai autorisation";
    private static final List<String> ADRESSES = List.of(
            "creation.admin.refuse@garah.cm",
            "creation.admin.sans.base@garah.cm",
            "creation.admin.permis@garah.cm",
            "creation.resp.permis@garah.cm");

    @Autowired MockMvc http;
    @Autowired ServiceProfilResponsable profils;
    @Autowired JdbcTemplate sql;

    private Long profilId;

    @BeforeEach
    void nettoyer() {
        for (String email : ADRESSES) {
            sql.update("""
                    DELETE FROM responsable_categorie WHERE responsable_id IN (
                        SELECT id FROM utilisateur WHERE lower(email) = ?)
                    """, email);
            sql.update("""
                    DELETE FROM responsable_cas_utilisation WHERE responsable_id IN (
                        SELECT id FROM utilisateur WHERE lower(email) = ?)
                    """, email);
            sql.update("""
                    DELETE FROM responsable WHERE id IN (
                        SELECT id FROM utilisateur WHERE lower(email) = ?)
                    """, email);
            sql.update("DELETE FROM utilisateur WHERE lower(email) = ?", email);
        }

        // Réutilisé s'il existe : un profil ne se supprime pas, il se désactive.
        profilId = profils.lister().stream()
                .filter(p -> PROFIL.equals(p.nom()))
                .map(VueProfil::id)
                .findFirst()
                .orElseGet(() -> profils.creer(PROFIL, "Pour les tests",
                        List.of("PRODUIT_CONSULTER")).id());
    }

    /** Un appelant porteur des droits nommés, et de rien d'autre. */
    private static RequestPostProcessor avec(String... droits) {
        SimpleGrantedAuthority[] autorites = new SimpleGrantedAuthority[droits.length];
        for (int i = 0; i < droits.length; i++) {
            autorites[i] = new SimpleGrantedAuthority(droits[i]);
        }
        return jwt().jwt(j -> j.subject("1")).authorities(autorites);
    }

    private static String demandeAdmin(String email) {
        return """
                {"type":"ADMIN","nom":"Mbarga","prenom":"Jean","email":"%s",
                 "telephone":"+237 6 99 11 22 33","motDePasse":"abc123"}
                """.formatted(email);
    }

    private String demandeResponsable(String email) {
        return """
                {"type":"RESPONSABLE","nom":"Ngo Bassong","prenom":"Aline","email":"%s",
                 "motDePasse":"abc123","profilIds":[%d],"profilPrincipalId":%d}
                """.formatted(email, profilId, profilId);
    }

    @Test
    @DisplayName("Un responsable ne fabrique pas un administrateur")
    void responsableNePeutPasCreerUnAdmin() throws Exception {
        http.perform(post("/api/equipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demandeAdmin(ADRESSES.get(0)))
                        .with(avec("RESPONSABLE_CREER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN_CREER seul ne suffit pas")
    void adminCreerSeulNeSuffitPas() throws Exception {
        // Les deux droits sont exigés ENSEMBLE. Sans cela, quelqu'un qui
        // recevrait ADMIN_CREER sans RESPONSABLE_CREER créerait des comptes
        // alors qu'il n'a pas le droit d'en créer tout court.
        http.perform(post("/api/equipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demandeAdmin(ADRESSES.get(1)))
                        .with(avec("ADMIN_CREER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Avec les deux droits, l'administrateur se crée")
    void lesDeuxDroitsOuvrentLaPorte() throws Exception {
        http.perform(post("/api/equipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demandeAdmin(ADRESSES.get(2)))
                        .with(avec("RESPONSABLE_CREER", "ADMIN_CREER")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Créer un responsable n'a pas changé")
    void responsableCreerSuffitToujoursPourUnResponsable() throws Exception {
        // 🎯 Le garde-fou de ce correctif. Une regle de securite trop large
        // se remarque le jour ou plus personne ne peut travailler ; celle-ci
        // ne doit fermer QUE la creation d administrateurs.
        http.perform(post("/api/equipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demandeResponsable(ADRESSES.get(3)))
                        .with(avec("RESPONSABLE_CREER")))
                .andExpect(status().isCreated());
    }
}

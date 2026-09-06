package com.garah.api.iam.web;

import com.garah.api.iam.domaine.ServiceAuthentification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class ControleurAuthentification {

    private final ServiceAuthentification authentification;

    public ControleurAuthentification(ServiceAuthentification authentification) {
        this.authentification = authentification;
    }

    @PostMapping("/connexion")
    public ReponseConnexion connexion(@Valid @RequestBody DemandeConnexion demande,
                                      HttpServletRequest requete) {
        return ReponseConnexion.de(
                authentification.connecter(demande.email(), demande.motDePasse(), adresseIp(requete)));
    }

    /**
     * Renvoie ce que le jeton porte. Utile aux frontends au démarrage, et
     * pratique pour comprendre ce qu'un JWT contient réellement.
     */
    @GetMapping("/moi")
    public Map<String, Object> moi(@AuthenticationPrincipal Jwt jeton) {
        return Map.of(
                "id", jeton.getSubject(),
                "nom", jeton.getClaimAsString("nom"),
                "type", jeton.getClaimAsString("type"),
                "langue", jeton.getClaimAsString("langue"),
                "permissions", jeton.getClaimAsStringList("permissions") == null
                        ? List.of() : jeton.getClaimAsStringList("permissions"),
                "expireLe", String.valueOf(jeton.getExpiresAt()));
    }

    /**
     * L'adresse réelle du client.
     *
     * <p>⚠️ Derrière Render, Vercel ou un Nginx, {@code getRemoteAddr()} renvoie
     * l'adresse du <b>proxy</b>, pas celle du visiteur. Sans cette lecture de
     * {@code X-Forwarded-For}, tous les événements de sécurité porteraient la
     * même adresse IP — et le score de risque serait aveugle (D-14).</p>
     */
    private String adresseIp(HttpServletRequest requete) {
        String transmise = requete.getHeader("X-Forwarded-For");
        if (transmise != null && !transmise.isBlank()) {
            // Le premier de la liste est le client d'origine.
            return transmise.split(",")[0].trim();
        }
        return requete.getRemoteAddr();
    }
}

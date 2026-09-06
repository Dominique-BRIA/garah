package com.garah.api.mesure.web;

import com.garah.api.commun.web.AdresseClient;
import com.garah.api.mesure.domaine.ProduitTendance;
import com.garah.api.mesure.domaine.ServiceStatistiques;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Vues de produits, favoris et statistiques.
 *
 * <p>Deux publics très différents cohabitent ici : la vitrine, qui
 * <b>écrit</b> des vues sans être authentifiée, et le back-office, qui
 * <b>lit</b> des agrégats sous permission.</p>
 */
@RestController
@RequestMapping("/api")
public class ControleurStatistiques {

    private static final int TENDANCE_MAX = 50;

    private final ServiceStatistiques statistiques;

    public ControleurStatistiques(ServiceStatistiques statistiques) {
        this.statistiques = statistiques;
    }

    /**
     * Enregistre la consultation d'une fiche produit. <b>Route publique.</b>
     *
     * <p>Elle doit l'être : la vitrine est ouverte, et la majorité des vues
     * viennent de visiteurs non connectés. Les exiger authentifiés ne
     * mesurerait plus l'audience, mais seulement les clients déjà acquis.</p>
     *
     * <p>Répond {@code 202 Accepted} et jamais d'erreur : le service avale ses
     * propres échecs. <b>Une statistique ne doit jamais casser une page
     * produit</b> — c'est une mesure, pas une transaction commerciale.</p>
     *
     * <p>⚠️ Le détail nominatif (qui, quand, depuis quelle IP) est purgé à
     * 90 jours (D-15). Seul l'agrégat quotidien est conservé.</p>
     */
    @PostMapping("/produits/{produitId}/vues")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void enregistrerVue(@PathVariable Long produitId,
                               @RequestBody(required = false) DemandeVue demande,
                               @AuthenticationPrincipal Jwt jeton,
                               HttpServletRequest requete) {

        statistiques.enregistrerVue(produitId,
                jeton == null ? null : Long.valueOf(jeton.getSubject()),
                demande == null ? null : demande.sessionId(),
                demande == null ? null : demande.source(),
                AdresseClient.de(requete));
    }

    /**
     * Les produits qui montent. <b>Route publique</b> : c'est un bloc de la
     * page d'accueil, pas une donnée interne.
     */
    @GetMapping("/produits/tendance")
    public List<ProduitTendance> tendance(@RequestParam(defaultValue = "10") int limite) {
        return statistiques.produitsTendance(Math.clamp(limite, 1, TENDANCE_MAX));
    }

    // -------------------------------------------------------------------------
    // Les favoris du client
    // -------------------------------------------------------------------------

    @PutMapping("/favoris/{produitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ajouterFavori(@PathVariable Long produitId,
                              @AuthenticationPrincipal Jwt jeton) {
        statistiques.ajouterFavori(Long.valueOf(jeton.getSubject()), produitId);
    }

    @DeleteMapping("/favoris/{produitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirerFavori(@PathVariable Long produitId,
                              @AuthenticationPrincipal Jwt jeton) {
        statistiques.retirerFavori(Long.valueOf(jeton.getSubject()), produitId);
    }

    // -------------------------------------------------------------------------
    // Le back-office
    // -------------------------------------------------------------------------

    @GetMapping("/statistiques/produits/{produitId}")
    @PreAuthorize("hasAuthority('STATISTIQUE_PRODUIT_CONSULTER')")
    public Map<String, Long> statistiquesProduit(@PathVariable Long produitId) {
        return Map.of(
                "vuesTotales", statistiques.vuesTotales(produitId),
                "favoris", statistiques.favorisDe(produitId));
    }

    /**
     * Relance l'agrégation d'une journée, à la main.
     *
     * <p>Le traitement tourne chaque nuit (D-15). Cette route sert au
     * <b>rattrapage</b> : instance redémarrée pendant la nuit, journée oubliée
     * après une panne. Le service est idempotent par (produit, jour) — rejouer
     * la même date écrase proprement, et c'est bien pour cela qu'on peut
     * l'exposer sans danger.</p>
     */
    @PostMapping("/statistiques/agregation/{jour}")
    @PreAuthorize("hasAuthority('STATISTIQUE_GENERALE_CONSULTER')")
    public Map<String, Integer> agreger(@PathVariable String jour) {
        return Map.of("lignes", statistiques.agregerLeJour(java.time.LocalDate.parse(jour)));
    }

    public record DemandeVue(
            /*
             * Identifiant de session du navigateur, pour compter les vues
             * UNIQUES d'un visiteur non connecté. Aucun rapport avec la
             * sécurité : il est fourni par le client et vaut ce qu'il vaut.
             */
            @Size(max = 100, message = "Identifiant de session trop long.")
            String sessionId,

            @Size(max = 50, message = "Source trop longue.")
            String source) {
    }
}

package com.garah.api.mesure.web;

import com.garah.api.commun.web.AdresseClient;
import com.garah.api.mesure.domaine.BilanPeriode;
import com.garah.api.mesure.domaine.ProduitTendance;
import com.garah.api.mesure.domaine.ServiceStatistiques;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    /**
     * Ma liste d'envies — <b>des identifiants</b>.
     *
     * <p>La vitrine la complète avec {@code /produits/par-ids} pour obtenir
     * photos et prix. Faire rendre les produits ici ferait dépendre la mesure
     * du catalogue, c'est-à-dire de ce qu'elle mesure.</p>
     */
    @GetMapping("/favoris/miens")
    public List<Long> mesFavoris(@AuthenticationPrincipal Jwt jeton) {
        return statistiques.mesFavoris(Long.valueOf(jeton.getSubject()));
    }

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

    /**
     * Le bilan d'une période.
     *
     * <p>Lu dans les <b>agrégats quotidiens</b>, jamais recalculé depuis le
     * détail : {@code vue_produit} est purgé à 90 jours (D-15), et un bilan
     * recalculé serait donc juste sur les dernières semaines et faux au-delà,
     * sans que rien ne le signale.</p>
     *
     * <p>Les deux dates sont facultatives : par défaut les <b>trente derniers
     * jours</b>, la question qu'on se pose neuf fois sur dix.</p>
     */
    @GetMapping("/statistiques/bilan")
    @PreAuthorize("hasAuthority('STATISTIQUE_GENERALE_CONSULTER')")
    public BilanPeriode bilan(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
            @RequestParam(defaultValue = "10") int limite) {

        LocalDate fin = au == null ? LocalDate.now() : au;
        LocalDate debut = du == null ? fin.minusDays(29) : du;

        return statistiques.bilan(debut, fin, Math.clamp(limite, 1, 50));
    }

    @GetMapping("/statistiques/produits/{produitId}")
    @PreAuthorize("hasAuthority('STATISTIQUE_PRODUIT_CONSULTER')")
    public Map<String, Long> statistiquesProduit(@PathVariable Long produitId) {
        return Map.of(
                "vuesTotales", statistiques.vuesTotales(produitId),
                "favoris", statistiques.favorisDe(produitId));
    }

    /**
     * Résume, à la main, les journées de la période qui ne l'ont jamais été.
     *
     * <p>Le traitement tourne chaque nuit (D-15). Cette route sert au
     * <b>rattrapage</b> : serveur endormi ou redémarré pendant la nuit.</p>
     *
     * <p>⚠️ Elle rattrapait UNE journée, la veille : une semaine de nuits
     * manquées laissait six jours irrécupérables. Elle couvre désormais toute
     * la période affichée — dans la limite de ce qu'on peut encore compter
     * juste (le détail des vues est purgé à 90 jours).</p>
     */
    @PostMapping("/statistiques/rattrapage")
    @PreAuthorize("hasAuthority('STATISTIQUE_GENERALE_CONSULTER')")
    public Map<String, Integer> rattraper(
            @org.springframework.web.bind.annotation.RequestParam String du,
            @org.springframework.web.bind.annotation.RequestParam String au) {
        return Map.of("journees", statistiques.rattraper(
                java.time.LocalDate.parse(du), java.time.LocalDate.parse(au)));
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

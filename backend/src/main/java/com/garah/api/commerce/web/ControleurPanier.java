package com.garah.api.commerce.web;

import com.garah.api.commerce.domaine.ContenuPanier;
import com.garah.api.commerce.domaine.ResultatFusion;
import com.garah.api.commerce.domaine.ServicePanier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Le panier du client connecté.
 *
 * <p>Aucune permission n'est exigée : un client n'a pas de droits, son accès
 * repose sur la <b>propriété</b> de ses données (chapitre 08). C'est
 * l'identifiant du jeton qui désigne le panier, jamais un paramètre.</p>
 *
 * <p>⚠️ Il n'y a <b>pas</b> de {@code /api/paniers/{id}}. Exposer un
 * identifiant de panier ouvrirait la question « ai-je le droit de voir
 * celui-ci ? », et il suffirait d'oublier le contrôle une fois. Ici la
 * question ne se pose pas : il n'existe qu'un chemin, et il mène toujours au
 * panier de l'appelant.</p>
 */
@RestController
@RequestMapping("/api/panier")
public class ControleurPanier {

    private final ServicePanier panier;

    public ControleurPanier(ServicePanier panier) {
        this.panier = panier;
    }

    @GetMapping
    public ContenuPanier contenu(@AuthenticationPrincipal Jwt jeton) {
        return panier.contenu(client(jeton));
    }

    /**
     * Ajoute une quantité à une ligne existante, ou crée la ligne.
     *
     * <p>Le prix n'est pas figé : un panier n'engage personne, et
     * {@link ContenuPanier} recalcule au tarif du jour à chaque affichage.
     * La photographie des prix a lieu au passage de commande, pas ici.</p>
     */
    @PostMapping("/lignes")
    public ContenuPanier ajouter(@Valid @RequestBody DemandeLignePanier demande,
                                 @AuthenticationPrincipal Jwt jeton) {
        return panier.ajouter(client(jeton), demande.varianteId(), demande.quantite());
    }

    /**
     * Fixe la quantité d'une ligne. Une quantité de 0 retire la ligne.
     *
     * <p>{@code PUT} et non {@code POST} : l'opération est idempotente. Un
     * client qui clique deux fois sur « quantité : 3 » doit obtenir 3, pas 6 —
     * et c'est exactement l'erreur que produit un {@code POST} d'ajout mal
     * utilisé par l'interface.</p>
     */
    @PutMapping("/lignes/{varianteId}")
    public ContenuPanier definirQuantite(@PathVariable Long varianteId,
                                         @Valid @RequestBody DemandeQuantite demande,
                                         @AuthenticationPrincipal Jwt jeton) {
        return panier.definirQuantite(client(jeton), varianteId, demande.quantite());
    }

    @DeleteMapping("/lignes/{varianteId}")
    public ContenuPanier retirer(@PathVariable Long varianteId,
                                 @AuthenticationPrincipal Jwt jeton) {
        return panier.retirer(client(jeton), varianteId);
    }

    @DeleteMapping
    public ContenuPanier vider(@AuthenticationPrincipal Jwt jeton) {
        return panier.vider(client(jeton));
    }

    /**
     * Reprend le panier constitué <b>avant</b> la connexion.
     *
     * <p>La vitrine est ouverte, le paiement non (D-07) : un visiteur remplit
     * son panier dans son navigateur, puis se connecte au moment de commander.
     * Cette route fait se rejoindre les deux.</p>
     *
     * <p>🎯 <b>Elle rend les écarts, pas seulement le panier.</b> Le panier
     * local ne connaît ni le stock, ni le catalogue, ni ce que le client a
     * déjà mis de côté ailleurs — la fusion produit donc presque toujours un
     * panier différent de celui qu'il avait sous les yeux. Le lui dire est le
     * but de la route ; un panier qui change tout seul juste avant de payer
     * fait perdre la confiance.</p>
     *
     * <p><b>Idempotente</b> : on garde la plus grande des deux quantités, jamais
     * la somme. Une requête réémise sur une connexion instable ne double donc
     * rien — voir {@code ServicePanier.fusionner}.</p>
     */
    @PostMapping("/fusion")
    public ResultatFusion fusionner(@Valid @RequestBody DemandeFusion demande,
                                    @AuthenticationPrincipal Jwt jeton) {
        return panier.fusionner(client(jeton), demande.lignes().stream()
                .map(l -> new ServicePanier.LigneLocale(l.varianteId(), l.quantite()))
                .toList());
    }

    /**
     * ⚠️ L'identifiant vient du jeton signé, <b>jamais</b> du corps ni de l'URL.
     *
     * <p>Un {@code clientId} accepté depuis la requête permettrait de lire et
     * de modifier le panier de n'importe qui, en changeant un chiffre.</p>
     */
    private static Long client(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    public record DemandeLignePanier(
            @NotNull(message = "La variante est obligatoire.") Long varianteId,

            /*
             * Le plafond n'est pas de la méfiance : sans lui, « ajouter
             * 2 000 000 000 » ferait déborder le calcul du montant de ligne
             * avant même que le contrôle de stock ne s'exécute.
             */
            @Min(value = 1, message = "La quantité doit être d'au moins 1.")
            @Max(value = 10000, message = "Quantité trop importante pour une commande en ligne.")
            int quantite) {
    }

    public record DemandeQuantite(
            @Min(value = 0, message = "La quantité ne peut pas être négative.")
            @Max(value = 10000, message = "Quantité trop importante pour une commande en ligne.")
            int quantite) {
    }

    /**
     * Le panier tel que le navigateur le gardait.
     *
     * <p>Le plafond de 100 lignes n'est pas de la méfiance envers le client :
     * ce corps vient d'un <b>stockage local</b>, que rien n'empêche d'être
     * corrompu ou bricolé. Sans borne, une seule requête pourrait demander des
     * milliers de résolutions de variantes.</p>
     */
    public record DemandeFusion(
            @NotNull(message = "Les lignes sont obligatoires.")
            @Size(max = 100, message = "Trop de lignes dans le panier à reprendre.")
            @Valid
            List<DemandeLignePanier> lignes) {
    }
}

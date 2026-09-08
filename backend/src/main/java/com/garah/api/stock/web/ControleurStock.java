package com.garah.api.stock.web;

import com.garah.api.stock.domaine.EtatStock;
import com.garah.api.stock.domaine.VueMouvement;
import com.garah.api.stock.domaine.ServiceStock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Le stock : entrées, ajustements et consultation.
 *
 * <p>⚠️ <b>Ni réservation ni libération ne sont exposées</b>, alors que les
 * permissions {@code STOCK_RESERVER} et {@code STOCK_LIBERER} existent au
 * référentiel. C'est délibéré : ces deux opérations sont des <b>conséquences</b>
 * du passage de commande et du paiement, jamais des gestes d'administration.</p>
 *
 * <p>Les exposer permettrait de libérer à la main du stock réservé par une
 * commande en cours de paiement — le client paierait une marchandise qui vient
 * d'être rendue disponible à quelqu'un d'autre. Une API qui offre un bouton
 * n'a pas besoin qu'on s'en serve pour être dangereuse : il suffit qu'il
 * existe.</p>
 */
@RestController
@RequestMapping("/api/stock")
public class ControleurStock {

    private final ServiceStock stock;

    public ControleurStock(ServiceStock stock) {
        this.stock = stock;
    }

    /**
     * L'inventaire complet, filtrable.
     *
     * <p>Le tri place les ruptures <b>en premier</b> : un écran de stock
     * s'ouvre pour savoir ce qui manque, pas pour admirer ce qui est plein.</p>
     *
     * <p>{@code sousLeSeuil=true} donne la même chose que {@code /alertes},
     * mais paginé et cherchable. La route {@code /alertes} reste, non paginée :
     * elle alimente le compteur du tableau de bord, qui n'a besoin que d'un
     * nombre.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('STOCK_CONSULTER')")
    public Page<EtatStock> lister(
            @RequestParam(required = false) String recherche,
            @RequestParam(defaultValue = "false") boolean sousLeSeuil,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille) {

        return stock.administration(recherche, sousLeSeuil,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, 100)));
    }

    /**
     * Le stock de toutes les déclinaisons d'un produit.
     *
     * <p>C'est en regardant un produit qu'on se demande combien il en reste,
     * pas en parcourant un inventaire. Sans cette route, la quantité ne
     * vivrait que sur l'écran de stock — et il faudrait quitter la fiche,
     * chercher la déclinaison, revenir.</p>
     */
    @GetMapping("/produits/{produitId}")
    @PreAuthorize("hasAuthority('STOCK_CONSULTER')")
    public List<EtatStock> pourProduit(@PathVariable Long produitId) {
        return stock.pourProduit(produitId);
    }

    @GetMapping("/{varianteId}")
    @PreAuthorize("hasAuthority('STOCK_CONSULTER')")
    public EtatStock etat(@PathVariable Long varianteId) {
        return stock.etat(varianteId);
    }

    /**
     * Règle le seuil d'alerte.
     *
     * <p>À zéro — la valeur par défaut — l'alerte ne se déclenche qu'à la
     * rupture, c'est-à-dire trop tard : la marchandise met des jours à venir
     * de Douala. Un seuil à cinq prévient pendant qu'il reste de quoi vendre.</p>
     *
     * <p>Gardé par {@code STOCK_AJUSTER} : régler un seuil, c'est décider
     * quand l'équipe sera alertée. Ce n'est pas de la consultation.</p>
     */
    @PutMapping("/{varianteId}/seuil")
    @PreAuthorize("hasAuthority('STOCK_AJUSTER')")
    public EtatStock definirSeuil(@PathVariable Long varianteId,
                                  @Valid @RequestBody DemandeSeuil demande) {
        return stock.definirSeuil(varianteId, demande.seuilAlerte());
    }

    /**
     * L'historique des mouvements d'une déclinaison.
     *
     * <p>C'est ce qui répond à « pourquoi n'en reste-t-il que trois ? ». La
     * quantité courante est une photo de l'instant ; les mouvements sont les
     * faits datés qui l'expliquent.</p>
     *
     * <p>Sa propre permission : voir un stock et voir <b>qui</b> l'a ajusté et
     * <b>pourquoi</b> ne se confient pas forcément à la même personne.</p>
     */
    @GetMapping("/{varianteId}/mouvements")
    @PreAuthorize("hasAuthority('STOCK_CONSULTER_HISTORIQUE')")
    public List<VueMouvement> mouvements(@PathVariable Long varianteId) {
        return stock.mouvements(varianteId);
    }

    /**
     * Les variantes passées sous leur seuil d'alerte.
     *
     * <p>C'est l'écran d'accueil du gestionnaire de stock : ce qu'il faut
     * réapprovisionner, et rien d'autre.</p>
     */
    @GetMapping("/alertes")
    @PreAuthorize("hasAuthority('STOCK_CONSULTER')")
    public List<EtatStock> alertes() {
        return stock.alertes();
    }

    /**
     * Le nombre de declinaisons sous leur seuil, et rien d autre.
     *
     * <p>{@code /alertes} charge les lignes PUIS les enrichit du nom de chaque
     * declinaison. Pour un chiffre sur une carte, c est deux requetes et une
     * liste entiere qui traverse le reseau pour qu on en lise la taille.</p>
     */
    @GetMapping("/alertes/nombre")
    @PreAuthorize("hasAuthority('STOCK_CONSULTER')")
    public long nombreAlertes() {
        return stock.nombreAlertes();
    }

    /** Une entrée de marchandise : réception d'un réapprovisionnement. */
    @PostMapping("/{varianteId}/entrees")
    @PreAuthorize("hasAuthority('STOCK_ENTREE_ENREGISTRER')")
    public EtatStock entrer(@PathVariable Long varianteId,
                            @Valid @RequestBody DemandeEntree demande,
                            @AuthenticationPrincipal Jwt jeton) {
        return stock.entrer(varianteId, demande.quantite(),
                utilisateur(jeton), demande.commentaire());
    }

    /**
     * L'inventaire : on déclare ce qu'on a <b>réellement</b> compté.
     *
     * <p>On envoie la quantité constatée, pas un écart. Calculer soi-même
     * « il en manque 3 » suppose de connaître la quantité théorique au moment
     * du comptage — or elle a pu bouger entre-temps. En envoyant le constat, le
     * service calcule l'écart au moment où il pose le verrou, et le mouvement
     * enregistré est toujours juste.</p>
     *
     * <p>Le motif est obligatoire : un ajustement sans explication est un trou
     * dans l'inventaire que personne ne saura combler six mois plus tard.</p>
     */
    @PostMapping("/{varianteId}/ajustements")
    @PreAuthorize("hasAuthority('STOCK_AJUSTER')")
    public EtatStock ajuster(@PathVariable Long varianteId,
                             @Valid @RequestBody DemandeAjustement demande,
                             @AuthenticationPrincipal Jwt jeton) {
        return stock.ajuster(varianteId, demande.quantiteReelle(),
                utilisateur(jeton), demande.motif());
    }

    /**
     * Le stock est-il réconcilié ?
     *
     * <p>Vérifie que la quantité courante égale la somme de tous les
     * mouvements. Une divergence signale un écrit direct en base ou un bug —
     * dans les deux cas, quelque chose qu'aucun écran ne montrerait autrement.</p>
     */
    @GetMapping("/{varianteId}/reconciliation")
    @PreAuthorize("hasAuthority('STOCK_CONSULTER_HISTORIQUE')")
    public Map<String, Boolean> reconciliation(@PathVariable Long varianteId) {
        return Map.of("reconcilie", stock.estReconcilie(varianteId));
    }

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }

    public record DemandeEntree(
            @Min(value = 1, message = "Une entrée porte sur au moins une unité.")
            int quantite,

            @Size(max = 500, message = "Commentaire trop long.")
            String commentaire) {
    }

    /**
     * Le seuil a partir duquel l'equipe est prevenue.
     *
     * <p>Zero est une valeur legitime : « previens-moi quand il n'y en a
     * plus ». Elle est simplement rarement la bonne — la marchandise met des
     * jours a venir de Douala.</p>
     */
    public record DemandeSeuil(
            @Min(value = 0, message = "Un seuil d'alerte ne peut pas etre negatif.")
            int seuilAlerte) {
    }

    public record DemandeAjustement(
            @Min(value = 0, message = "Une quantité constatée ne peut pas être négative.")
            int quantiteReelle,

            @NotBlank(message = "Le motif de l'ajustement est obligatoire.")
            @Size(max = 500, message = "Motif trop long.")
            String motif) {
    }
}

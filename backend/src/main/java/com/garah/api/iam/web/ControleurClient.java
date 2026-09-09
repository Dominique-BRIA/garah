package com.garah.api.iam.web;

import com.garah.api.iam.domaine.FicheClient;
import com.garah.api.iam.domaine.ResumeClient;
import com.garah.api.iam.domaine.ServiceClient;
import com.garah.api.iam.domaine.StatutUtilisateur;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Les clients, vus du back-office.
 *
 * <h2>« Deux publics, deux routes »</h2>
 *
 * <p>🎯 Ce contrôleur est <b>entièrement</b> réservé aux comptes internes :
 * chaque route porte une permission du module {@code CLIENT}. Un client, lui,
 * gère son compte par {@code /api/profil} — une route qui ne connaît que
 * <b>lui</b>, jamais un identifiant en paramètre.</p>
 *
 * <p>Les fusionner derrière un {@code if (estResponsable)} ferait qu'un oubli
 * de condition ouvre les fiches de tous les clients à n'importe quel compte
 * connecté. C'est la règle fondatrice n°4, et c'est ici qu'elle se voit le
 * mieux.</p>
 *
 * <h2>Ce que ce contrôleur ne fait pas</h2>
 *
 * <p><b>Aucune suppression.</b> Un client porte des commandes, des paiements,
 * des retours, des écritures comptables. L'effacer rendrait inexplicables des
 * lignes qui, elles, restent.</p>
 *
 * <p><b>Aucun changement d'adresse e-mail.</b> Elle identifie le compte et
 * sert à s'y connecter : la changer depuis ici reviendrait à donner le compte
 * d'un client à quelqu'un d'autre, sans que le premier soit prévenu.</p>
 *
 * <p><b>Aucune remise à zéro de mot de passe.</b> Le client la demande
 * lui-même ; un agent qui pourrait la déclencher pourrait détourner un
 * compte.</p>
 */
@RestController
@RequestMapping("/api/clients")
public class ControleurClient {

    private final ServiceClient clients;
    private final com.garah.api.surveillance.domaine.ServiceActiviteClient parcours;

    public ControleurClient(ServiceClient clients,
                            com.garah.api.surveillance.domaine.ServiceActiviteClient parcours) {
        this.parcours = parcours;
        this.clients = clients;
    }

    /**
     * La liste, cherchable par code, nom ou e-mail.
     *
     * <p>Les trois à la fois, et non un champ à choisir : un agent au
     * téléphone a l'un des trois, jamais les trois — obliger à choisir ferait
     * échouer une recherche sur deux.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('CLIENT_CONSULTER')")
    public Page<ResumeClient> lister(
            @RequestParam(required = false) StatutUtilisateur statut,
            @RequestParam(required = false) String recherche,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int taille) {

        return clients.administration(statut, recherche,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, 100)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CLIENT_CONSULTER')")
    public FicheClient fiche(@PathVariable Long id) {
        return clients.fiche(id);
    }

    /**
     * Le parcours d'un client : ce qu'il a fait, du plus récent au plus ancien.
     *
     * <h2>🎯 Le droit existait, il ne gardait rien</h2>
     *
     * <p>{@code CLIENT_CONSULTER_HISTORIQUE} figure au référentiel depuis
     * l'origine et n'était vérifié <b>nulle part</b> — faute de route à
     * garder : la table {@code activite_client}, déclarée en V12, n'avait ni
     * entité ni écriture. Les deux manques se répondaient.</p>
     *
     * <h2>⚠️ Ce n'est pas le journal des actions internes</h2>
     *
     * <p>Celui-ci répond à « qu'a fait CE CLIENT ? » — il a mis ceci au
     * panier, commandé, annulé. L'autre répond à « qui, chez nous, a touché à
     * cette donnée ? ». Les confondre ferait disparaître les quelques gestes
     * internes d'une journée sous le trafic de la boutique.</p>
     */
    @GetMapping("/{id}/parcours")
    @PreAuthorize("hasAuthority('CLIENT_CONSULTER_HISTORIQUE')")
    public org.springframework.data.domain.Page<
            com.garah.api.surveillance.domaine.VueActiviteClient> parcours(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int taille) {

        return parcours.parcoursDe(id,
                org.springframework.data.domain.PageRequest.of(page, Math.min(taille, 200)));
    }

    /**
     * Corrige les coordonnées : téléphone et langue.
     *
     * <p>Rien d'autre — voir l'en-tête de cette classe.</p>
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CLIENT_MODIFIER')")
    public FicheClient modifier(@PathVariable Long id,
                                @Valid @RequestBody DemandeModification demande) {
        return clients.modifier(id, demande.telephone(), demande.langue());
    }

    /**
     * Suspend ou réactive.
     *
     * <p>⚠️ <b>Deux permissions distinctes sur une seule route</b>, choisies
     * selon le sens du geste. Suspendre un client coupe ses achats ; le
     * réactiver ne coupe rien. Ce ne sont pas les mêmes conséquences, donc pas
     * les mêmes droits — et une seule permission pour les deux donnerait à qui
     * peut réactiver le pouvoir de suspendre.</p>
     *
     * <p>🎯 L'expression appelle {@code estActivation()} et <b>non</b>
     * {@code actif()}. Spring Security s'exécute <b>avant</b> la validation du
     * corps : à ce moment, {@code actif} peut encore être nul malgré le
     * {@code @NotNull}, et {@code !null} fait échouer l'évaluation SpEL — un
     * {@code 500} sur une requête malformée, là où on veut un {@code 400}.
     * {@code estActivation()} rend un {@code boolean} primitif, jamais nul.</p>
     */
    @PutMapping("/{id}/activation")
    @PreAuthorize("(#demande.estActivation() and hasAuthority('CLIENT_ACTIVER'))"
                + " or (!#demande.estActivation() and hasAuthority('CLIENT_DESACTIVER'))")
    public FicheClient activer(@PathVariable Long id,
                               @Valid @RequestBody DemandeActivation demande) {
        return clients.activer(id, demande.actif());
    }

    // -------------------------------------------------------------------------

    public record DemandeModification(
            @Size(max = 30, message = "Numéro de téléphone trop long.")
            String telephone,

            @Size(max = 5, message = "Code de langue invalide.")
            String langue) {
    }

    public record DemandeActivation(
            @NotNull(message = "L'état est obligatoire.") Boolean actif) {

        /**
         * Utilisé par le {@code @PreAuthorize} de la route.
         *
         * <p>⚠️ Le {@code Boolean} est encapsulé pour que l'expression SpEL
         * n'ait pas à gérer le cas nul — que {@code @NotNull} refuse de toute
         * façon, mais <b>après</b> l'autorisation : Spring Security s'exécute
         * avant la validation du corps.</p>
         */
        public boolean estActivation() {
            return Boolean.TRUE.equals(actif);
        }
    }
}

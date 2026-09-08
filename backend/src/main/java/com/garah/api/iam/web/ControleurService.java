package com.garah.api.iam.web;

import com.garah.api.iam.domaine.ServiceEquipe;
import com.garah.api.iam.domaine.ServiceHierarchie;
import com.garah.api.iam.domaine.VueMembre;
import com.garah.api.surveillance.domaine.ServiceAudit;
import com.garah.api.surveillance.domaine.VueActivite;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Le service, et son chef.
 *
 * <pre>
 * Super Admin  →  Admin  →  Chef de service  →  Membre
 * </pre>
 *
 * <h2>🎯 Pourquoi des routes séparées de {@code /api/equipe}</h2>
 *
 * <p>Ce ne sont pas les mêmes gestes, même s'ils se ressemblent.
 * {@code /api/equipe} sert à administrer <b>toute</b> l'équipe ;
 * {@code /api/services} sert à un chef à administrer <b>la sienne</b>. Les
 * confondre reviendrait à donner à une permission deux sens selon qui la
 * détient — et l'écran des profils ne pourrait plus l'expliquer.</p>
 *
 * <h2>⚠️ DEUX contrôles sur chaque geste, et il faut les deux</h2>
 *
 * <ul>
 *   <li>l'<b>autorité</b> — {@code @PreAuthorize} : ce profil a-t-il ce
 *       droit ?</li>
 *   <li>la <b>portée</b> — {@code exigerAutoriteSur} : sur cette
 *       personne-là ?</li>
 * </ul>
 *
 * <p>L'annotation garde la porte, le service garde la pièce. Elle seule
 * laisserait un chef de la logistique désactiver un magasinier du commercial ;
 * lui seul ouvrirait la messagerie à des comptes qui n'ont rien à y faire.</p>
 */
@RestController
@RequestMapping("/api/services")
public class ControleurService {

    /** Une page d'activité ne dépasse pas cette taille, quoi qu'on demande. */
    private static final int TAILLE_MAX = 200;

    private final ServiceHierarchie hierarchie;
    private final ServiceEquipe equipe;
    private final ServiceAudit audit;

    public ControleurService(ServiceHierarchie hierarchie, ServiceEquipe equipe,
                             ServiceAudit audit) {
        this.hierarchie = hierarchie;
        this.equipe = equipe;
        this.audit = audit;
    }

    // -------------------------------------------------------------------------
    // Ce que fait l'ADMIN : nommer
    // -------------------------------------------------------------------------

    /**
     * Nomme le chef d'un service.
     *
     * <p>{@code PUT} : le geste est idempotent, et il <b>remplace</b>. Nommer
     * un second chef démet le premier dans la même transaction — sans quoi
     * l'index unique refuserait l'écriture avec un message que personne ne
     * peut lire.</p>
     *
     * <p>⚠️ Le futur chef doit déjà être <b>membre</b> du service. Un chef qui
     * n'en serait pas membre serait un supérieur sans équipe.</p>
     */
    @PutMapping("/{categorieId}/chef/{responsableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SERVICE_CHEF_NOMMER')")
    public void nommerChef(@PathVariable Long categorieId,
                           @PathVariable Long responsableId) {
        hierarchie.nommerChef(categorieId, responsableId);
    }

    /**
     * Démet le chef d'un service.
     *
     * <p>Idempotent : un service sans chef est un état normal — celui d'avant
     * la nomination. Démettre deux fois ne doit pas échouer.</p>
     */
    @DeleteMapping("/{categorieId}/chef")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SERVICE_CHEF_NOMMER')")
    public void demettreLeChef(@PathVariable Long categorieId) {
        hierarchie.demettreLeChef(categorieId);
    }

    // -------------------------------------------------------------------------
    // Ce que fait le CHEF, sur son service et lui seul
    // -------------------------------------------------------------------------

    /**
     * Les services que je dirige.
     *
     * <p>Aucune permission : la réponse est <b>vide</b> pour qui n'en dirige
     * aucun, et c'est la seule information qu'elle donne. L'écran s'en sert
     * pour décider d'afficher ou non l'entrée « Mon service » — la demander
     * sous permission obligerait à donner un droit à tout le monde pour
     * apprendre qu'on n'a rien.</p>
     */
    @GetMapping("/miens")
    public List<Long> mesServices(@AuthenticationPrincipal Jwt jeton) {
        return hierarchie.servicesDiriges(utilisateur(jeton));
    }

    /**
     * Les membres d'un service que je dirige.
     *
     * <p>⚠️ On vérifie que je le dirige <b>vraiment</b>. Sans ce contrôle, la
     * permission suffirait à lire l'équipe de n'importe quel service en
     * changeant un identifiant dans l'URL.</p>
     */
    @GetMapping("/{categorieId}/membres")
    @PreAuthorize("hasAuthority('SERVICE_MEMBRE_CONSULTER')")
    public List<VueMembre> membres(@PathVariable Long categorieId,
                                   @AuthenticationPrincipal Jwt jeton) {
        exigerQueJeDirige(categorieId, jeton);
        return equipe.membresDu(categorieId);
    }

    @PatchMapping("/membres/{id}")
    @PreAuthorize("hasAuthority('SERVICE_MEMBRE_MODIFIER')")
    public VueMembre modifier(@PathVariable Long id,
                              @Valid @RequestBody ControleurEquipe.DemandeModificationMembre demande,
                              @AuthenticationPrincipal Jwt jeton) {
        hierarchie.exigerAutoriteSur(utilisateur(jeton), id);
        return equipe.modifier(id, demande.nom(), demande.prenom(),
                demande.telephone(), demande.dateEmbauche());
    }

    @PostMapping("/membres/{id}/activation")
    @PreAuthorize("hasAuthority('SERVICE_MEMBRE_ACTIVER')")
    public VueMembre activer(@PathVariable Long id, @AuthenticationPrincipal Jwt jeton) {
        hierarchie.exigerAutoriteSur(utilisateur(jeton), id);
        return equipe.changerStatut(id, true);
    }

    @DeleteMapping("/membres/{id}/activation")
    @PreAuthorize("hasAuthority('SERVICE_MEMBRE_DESACTIVER')")
    public VueMembre desactiver(@PathVariable Long id, @AuthenticationPrincipal Jwt jeton) {
        hierarchie.exigerAutoriteSur(utilisateur(jeton), id);
        return equipe.changerStatut(id, false);
    }

    /**
     * Redonne un mot de passe à un membre qui a perdu le sien.
     *
     * <p>L'ancien n'est pas demandé : un chef ne le connaît pas, et c'est très
     * bien ainsi.</p>
     */
    @PostMapping("/membres/{id}/mot-de-passe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SERVICE_MEMBRE_MOT_DE_PASSE')")
    public void reinitialiserMotDePasse(
            @PathVariable Long id,
            @Valid @RequestBody ControleurEquipe.DemandeMotDePasse demande,
            @AuthenticationPrincipal Jwt jeton) {
        hierarchie.exigerAutoriteSur(utilisateur(jeton), id);
        equipe.reinitialiserMotDePasse(id, demande.motDePasse());
    }

    /**
     * Ce qu'un membre a fait, et quand.
     *
     * <p>🎯 <b>Ce n'est pas le journal d'audit.</b> Celui-ci porte les clichés
     * JSON des objets modifiés — donc, potentiellement, n'importe quelle donnée
     * du système — et reste réservé au module SÉCURITÉ. Un chef reçoit
     * l'action, l'objet visé et l'horodatage, sans leur contenu.</p>
     *
     * <p>⚠️ La réduction se fait dans {@code ServiceAudit}, pas ici. Renvoyer
     * la vue complète en comptant sur l'écran pour ne pas l'afficher
     * reviendrait à poser un rideau devant une fenêtre ouverte.</p>
     */
    @GetMapping("/membres/{id}/activite")
    @PreAuthorize("hasAuthority('SERVICE_MEMBRE_ACTIVITE')")
    public Page<VueActivite> activite(@PathVariable Long id,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int taille,
                                      @AuthenticationPrincipal Jwt jeton) {
        hierarchie.exigerAutoriteSur(utilisateur(jeton), id);
        return audit.activiteDe(id,
                PageRequest.of(Math.max(page, 0), Math.clamp(taille, 1, TAILLE_MAX)));
    }

    // -------------------------------------------------------------------------

    /**
     * Refuse si je ne dirige pas ce service.
     *
     * <p>« Introuvable » serait plus discret, mais ce n'est pas le bon choix
     * ici : un chef connaît la liste des services de la maison, et lui dire
     * « ce service n'existe pas » le ferait douter de sa mémoire plutôt que de
     * ses droits.</p>
     */
    private void exigerQueJeDirige(Long categorieId, Jwt jeton) {
        if (!hierarchie.servicesDiriges(utilisateur(jeton)).contains(categorieId)) {
            throw new com.garah.api.commun.erreur.RegleMetierViolee(
                    "PAS_CHEF_DE_CE_SERVICE",
                    "Vous ne dirigez pas ce service.");
        }
    }

    private static Long utilisateur(Jwt jeton) {
        return Long.valueOf(jeton.getSubject());
    }
}

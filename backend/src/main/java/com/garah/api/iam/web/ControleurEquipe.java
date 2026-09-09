package com.garah.api.iam.web;

import com.garah.api.iam.domaine.ServiceEquipe;
import com.garah.api.iam.domaine.TypeUtilisateur;
import com.garah.api.iam.domaine.VueMembre;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Les comptes internes : administrateurs et responsables.
 *
 * <p>Distinct de {@code /api/auth/inscription}, qui crée un CLIENT et qui est
 * ouvert à tous. Ici, on crée quelqu'un qui travaille dans l'entreprise : cela
 * demande une permission, et le compte reçoit des droits.</p>
 */
@RestController
@RequestMapping("/api/equipe")
public class ControleurEquipe {

    private final ServiceEquipe equipe;

    public ControleurEquipe(ServiceEquipe equipe) {
        this.equipe = equipe;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('RESPONSABLE_CONSULTER')")
    public List<VueMembre> lister() {
        return equipe.lister();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RESPONSABLE_CONSULTER')")
    public VueMembre detail(@PathVariable Long id) {
        return equipe.detail(id);
    }

    /**
     * Crée un compte interne.
     *
     * <h2>⚠️ Deux droits, parce que deux gestes de portée différente</h2>
     *
     * <p>Créer un responsable et créer un administrateur passaient par la même
     * autorisation, {@code RESPONSABLE_CREER}. Or ce droit vit dans le module
     * {@code ADMINISTRATION} : un <b>responsable</b> à qui un profil l'accorde
     * pouvait donc fabriquer un compte ADMIN et s'y connecter. Une permission
     * devenait « tout sauf le module sécurité ».</p>
     *
     * <p>{@code ADMIN_CREER} existait depuis le référentiel d'origine (V14),
     * dans le module {@code SECURITE} — donc réservé au super-administrateur.
     * Il n'était vérifié <b>nulle part</b>. Un droit déclaré et jamais
     * appliqué ne protège rien, et se lit pourtant comme une protection.</p>
     *
     * <p>C'est le raisonnement que le service tient déjà pour refuser la
     * création d'un SUPER_ADMIN — « donner à un Admin le moyen de se hisser
     * au-dessus de son propre niveau ». Il manquait un cran plus bas.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("""
            hasAuthority('RESPONSABLE_CREER') and (
                #demande.type() != T(com.garah.api.iam.domaine.TypeUtilisateur).ADMIN
                or hasAuthority('ADMIN_CREER'))
            """)
    public VueMembre creer(@Valid @RequestBody DemandeMembre demande) {
        return equipe.creer(demande.type(), demande.nom(), demande.prenom(),
                demande.email(), demande.telephone(), demande.motDePasse(),
                demande.dateEmbauche(), demande.profilIds(), demande.profilPrincipalId());
    }

    /**
     * ⚠️ Le droit {@code ADMIN_MODIFIER} s'ajoute quand la cible est un
     *    administrateur — voir {@link GardeEquipe}. Sans lui, un responsable
     *    pouvait modifier un compte placé au-dessus du sien.
     */
    @PutMapping("/{id}")
    @PreAuthorize("""
            hasAuthority('RESPONSABLE_MODIFIER') and (
                !@gardeEquipe.cibleUnAdmin(#id)
                or hasAuthority('ADMIN_MODIFIER'))
            """)
    public VueMembre modifier(@PathVariable Long id,
                              @Valid @RequestBody DemandeModificationMembre demande) {
        return equipe.modifier(id, demande.nom(), demande.prenom(),
                demande.telephone(), demande.dateEmbauche());
    }

    /**
     * Remplace les profils d'un responsable.
     *
     * <p>Un {@code PUT} : l'écran envoie l'état complet des cases cochées, pas
     * la différence. Calculer la différence côté frontend est un calcul de
     * plus, donc un endroit de plus où se tromper.</p>
     */
    @PutMapping("/{id}/profils")
    @PreAuthorize("""
            hasAuthority('RESPONSABLE_MODIFIER') and (
                !@gardeEquipe.cibleUnAdmin(#id)
                or hasAuthority('ADMIN_MODIFIER'))
            """)
    public VueMembre affecterProfils(@PathVariable Long id,
                                     @Valid @RequestBody DemandeProfils demande) {
        return equipe.affecterProfils(id, demande.profilIds(), demande.profilPrincipalId());
    }

    /*
     * Activation et desactivation sur la MEME route, distinguees par le verbe
     * et gardees par DEUX permissions differentes. Le referentiel les a
     * separees des le depart : rendre quelqu'un a son poste et l'en retirer ne
     * se confient pas forcement a la meme personne.
     */
    @PostMapping("/{id}/activation")
    @PreAuthorize("""
            hasAuthority('RESPONSABLE_ACTIVER') and (
                !@gardeEquipe.cibleUnAdmin(#id)
                or hasAuthority('ADMIN_ACTIVER'))
            """)
    public VueMembre activer(@PathVariable Long id) {
        return equipe.changerStatut(id, true);
    }

    @DeleteMapping("/{id}/activation")
    @PreAuthorize("""
            hasAuthority('RESPONSABLE_DESACTIVER') and (
                !@gardeEquipe.cibleUnAdmin(#id)
                or hasAuthority('ADMIN_DESACTIVER'))
            """)
    public VueMembre desactiver(@PathVariable Long id) {
        return equipe.changerStatut(id, false);
    }

    /**
     * Impose un nouveau mot de passe.
     *
     * <p>L'ancien n'est pas demandé : un administrateur ne le connaît pas, et
     * c'est très bien ainsi. Cette route sert au dépannage, quand quelqu'un a
     * perdu le sien.</p>
     *
     * <p>⚠️ <b>C'est la route la plus dangereuse du contrôleur.</b> Imposer un
     * mot de passe à quelqu'un, c'est pouvoir se connecter à sa place. Sans le
     * droit {@code ADMIN_MODIFIER}, un responsable pouvait donc prendre le
     * compte d'un administrateur — pas seulement le gêner.</p>
     */
    @PostMapping("/{id}/mot-de-passe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("""
            hasAuthority('RESPONSABLE_MODIFIER') and (
                !@gardeEquipe.cibleUnAdmin(#id)
                or hasAuthority('ADMIN_MODIFIER'))
            """)
    public void reinitialiserMotDePasse(@PathVariable Long id,
                                        @Valid @RequestBody DemandeMotDePasse demande) {
        equipe.reinitialiserMotDePasse(id, demande.motDePasse());
    }

    // -------------------------------------------------------------------------

    public record DemandeMembre(
            @NotNull(message = "Le type de compte est obligatoire.")
            TypeUtilisateur type,

            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 100, message = "Le nom ne peut pas depasser 100 caracteres.")
            String nom,

            @Size(max = 100, message = "Le prenom ne peut pas depasser 100 caracteres.")
            String prenom,

            @NotBlank(message = "L'adresse e-mail est obligatoire.")
            @Email(message = "Cette adresse e-mail n'est pas valide.")
            @Size(max = 255, message = "Adresse e-mail trop longue.")
            String email,

            @Size(max = 30, message = "Numero de telephone trop long.")
            @Pattern(regexp = "^$|^[+()0-9 .-]{6,30}$",
                     message = "Ce numero de telephone n'est pas valide.")
            String telephone,

            /*
             * ⚠️ Six caracteres, et c'est PEU. Choix explicite du 08/09/2026.
             *
             * Ce mot de passe est PROVISOIRE : il se dicte au telephone ou se
             * recopie sur un papier, et douze caracteres rendaient ce geste
             * penible au point qu'on creait des comptes en tapant n'importe
             * quoi. Le seuil suit l'usage reel.
             *
             * Ce qui limite le risque : la personne qui le change ensuite,
             * depuis son profil, passe par ServiceInscription et doit alors
             * fournir DIX caracteres. Le seuil bas ne vaut donc que pour la
             * fenetre entre la creation et la premiere connexion.
             *
             * ⚠️ Rien ne FORCE encore ce changement. Tant que ce n'est pas le
             * cas, un compte peut vivre avec six caracteres — et un compte
             * interne ouvre plus de portes qu'un compte client.
             */
            @NotBlank(message = "Le mot de passe est obligatoire.")
            @Size(min = 6, max = 100,
                  message = "Le mot de passe doit compter au moins 6 caracteres.")
            String motDePasse,

            LocalDate dateEmbauche,

            /* Obligatoires pour un RESPONSABLE ; le service le verifie. */
            List<Long> profilIds,
            Long profilPrincipalId) {
    }

    public record DemandeModificationMembre(
            @NotBlank(message = "Le nom est obligatoire.")
            @Size(max = 100, message = "Le nom ne peut pas depasser 100 caracteres.")
            String nom,

            @Size(max = 100, message = "Le prenom ne peut pas depasser 100 caracteres.")
            String prenom,

            @Size(max = 30, message = "Numero de telephone trop long.")
            @Pattern(regexp = "^$|^[+()0-9 .-]{6,30}$",
                     message = "Ce numero de telephone n'est pas valide.")
            String telephone,

            LocalDate dateEmbauche) {
    }

    public record DemandeProfils(
            @NotEmpty(message = "Choisissez au moins un profil.")
            List<Long> profilIds,

            Long profilPrincipalId) {
    }

    /** La reinitialisation par un administrateur : meme geste, meme seuil. */
    public record DemandeMotDePasse(
            @NotBlank(message = "Le mot de passe est obligatoire.")
            @Size(min = 6, max = 100,
                  message = "Le mot de passe doit compter au moins 6 caracteres.")
            String motDePasse) {
    }
}

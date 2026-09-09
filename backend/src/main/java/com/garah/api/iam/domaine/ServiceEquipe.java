package com.garah.api.iam.domaine;

import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.stockage.StockageObjet;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.infra.CategorieResponsableRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * La création et la gestion des comptes internes.
 *
 * <h2>La chaîne hiérarchique, et ce qui la fait tenir</h2>
 *
 * <pre>
 * SUPER_ADMIN   agit SUR le systeme  — quelles fonctionnalites existent
 * ADMIN         agit DANS le systeme — qui les utilise, et sur quoi
 * RESPONSABLE   execute le travail   — selon ses profils
 * CLIENT        consomme le service  — il ne passe pas par ici
 * </pre>
 *
 * <p>Les trois premiers ne se distinguent pas par une case à cocher : leurs
 * droits se <b>calculent</b> différemment ({@code ServiceAuthentification}).
 * Un SuperAdmin reçoit toutes les fonctionnalités actives ; un Admin toutes
 * sauf le module sécurité ; un Responsable l'union de ses profils, plus ses
 * exceptions individuelles, moins celles qu'on lui a retirées.</p>
 *
 * <p>⚠️ <b>Un Admin ne peut jamais inventer une permission.</b> Il choisit
 * dans {@code cas_utilisation}, la table des fonctionnalités réellement
 * implémentées, alimentée par les migrations. C'est ce qui empêche un droit
 * d'exister à l'écran sans exister dans le code.</p>
 */
@Service
public class ServiceEquipe {

    private final UtilisateurRepository utilisateurs;
    private final ResponsableRepository responsables;
    private final CategorieResponsableRepository profils;
    private final PasswordEncoder encodeur;
    private final StockageObjet stockage;
    private final JournalActions journal;

    public ServiceEquipe(UtilisateurRepository utilisateurs,
                         ResponsableRepository responsables,
                         CategorieResponsableRepository profils,
                         PasswordEncoder encodeur,
                         StockageObjet stockage,
                         JournalActions journal) {
        this.utilisateurs = utilisateurs;
        this.responsables = responsables;
        this.profils = profils;
        this.encodeur = encodeur;
        this.stockage = stockage;
        this.journal = journal;
    }

    // -------------------------------------------------------------------------
    // Lectures
    // -------------------------------------------------------------------------

    /**
     * Toute l'équipe : administrateurs et responsables réunis.
     *
     * <p>Deux requêtes, pas une : les administrateurs n'ont pas de ligne dans
     * {@code responsable}, et les responsables ont besoin de leurs catégories.
     * Les fondre en une seule demanderait une jointure externe dont la moitié
     * des colonnes serait nulle.</p>
     */
    @Transactional(readOnly = true)
    public List<VueMembre> lister() {
        List<VueMembre> membres = new ArrayList<>();

        for (Utilisateur u : utilisateurs.findByType(TypeUtilisateur.SUPER_ADMIN)) {
            membres.add(VueMembre.de(u, stockage::urlPublique));
        }
        for (Utilisateur u : utilisateurs.findByType(TypeUtilisateur.ADMIN)) {
            membres.add(VueMembre.de(u, stockage::urlPublique));
        }
        for (Responsable r : responsables.chargerToutAvecCategories()) {
            membres.add(VueMembre.de(r, stockage::urlPublique));
        }

        // Les administrateurs d'abord, puis les responsables par nom. L'ordre
        // du tableau doit refléter la hiérarchie, sinon la liste ne se lit pas.
        return membres.stream()
                .sorted(Comparator.comparingInt(ServiceEquipe::rang)
                        .thenComparing(VueMembre::nom, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Les membres d'un service — c'est-à-dire d'un profil.
     *
     * <p>Le chef y figure : il est membre de son propre service, et l'écran
     * « mon service » serait étrange sans lui.</p>
     *
     * <p>⚠️ Le contrôle de qui a le droit de lire cette liste n'est PAS ici :
     * il vit dans {@link ServiceHierarchie}, seul à connaître l'appelant. Un
     * service qui vérifierait des permissions ne pourrait plus être appelé
     * depuis un test sans monter une session.</p>
     */
    @Transactional(readOnly = true)
    public List<VueMembre> membresDu(Long categorieId) {
        return responsables.chargerToutAvecCategories().stream()
                .filter(r -> r.getCategories().stream()
                        .anyMatch(rc -> rc.getCategorie().getId().equals(categorieId)))
                .map(r -> VueMembre.de(r, stockage::urlPublique))
                .toList();
    }

    @Transactional(readOnly = true)
    public VueMembre detail(Long id) {
        return responsables.chargerAvecCategories(id)
                .map(r -> VueMembre.de(r, stockage::urlPublique))
                .orElseGet(() -> VueMembre.de(chargerUtilisateur(id), stockage::urlPublique));
    }

    // -------------------------------------------------------------------------
    // Création
    // -------------------------------------------------------------------------

    /**
     * Crée un compte interne.
     *
     * <p>Le type décide de la forme du compte : un ADMIN est un simple
     * {@code utilisateur}, un RESPONSABLE reçoit en plus une ligne
     * {@code responsable} avec matricule et profils. La distinction est faite
     * <b>ici</b> et nulle part ailleurs — un appelant n'a pas à savoir que le
     * schéma sépare les deux tables.</p>
     *
     * <p>⚠️ <b>Aucun SUPER_ADMIN ne se crée par cette route.</b> Il n'en existe
     * qu'un, posé au démarrage par l'amorçage. En laisser créer un second
     * depuis l'interface donnerait à un Admin le moyen de se hisser au-dessus
     * de son propre niveau — exactement ce que la séparation des rôles
     * cherche à empêcher.</p>
     *
     * <p>L'adresse est marquée <b>non vérifiée</b> : la personne recevra le
     * courriel de confirmation comme n'importe qui. Un compte créé par un
     * administrateur n'est pas la preuve que l'adresse saisie est la bonne.</p>
     */
    @Transactional
    public VueMembre creer(TypeUtilisateur type, String nom, String prenom, String email,
                           String telephone, String motDePasse, LocalDate dateEmbauche,
                           List<Long> profilIds, Long profilPrincipalId) {

        if (type == TypeUtilisateur.SUPER_ADMIN || type == TypeUtilisateur.CLIENT) {
            throw new RegleMetierViolee("TYPE_NON_CREABLE",
                    "Seuls un administrateur et un responsable se créent ici. "
                    + "Un client s'inscrit lui-même.");
        }

        String adresse = email.strip().toLowerCase(Locale.ROOT);
        if (utilisateurs.existsByEmailIgnoreCase(adresse)) {
            throw new RegleMetierViolee("EMAIL_DEJA_UTILISE",
                    "Un compte existe déjà avec cette adresse e-mail.");
        }

        Utilisateur utilisateur = new Utilisateur(type, nom.strip(), adresse,
                encodeur.encode(motDePasse));
        utilisateur.setPrenom(vide(prenom) ? null : prenom.strip());
        utilisateur.setTelephone(vide(telephone) ? null : telephone.strip());
        utilisateurs.save(utilisateur);

        // ⚠️ Le mot de passe n'entre PAS dans le cliché. Le journal recopie ce
        //    qu'on lui donne et se conserve pour toujours : une empreinte y
        //    resterait lisible longtemps après que le compte l'ait changée.
        journal.creation("COMPTE_CREER", "utilisateur", utilisateur.getId(),
                JournalActions.cliche("type", type, "nom", utilisateur.getNom(),
                        "email", adresse));

        if (type == TypeUtilisateur.ADMIN) {
            return VueMembre.de(utilisateur, stockage::urlPublique);
        }

        Responsable responsable = new Responsable(utilisateur, genererMatricule());
        responsable.setDateEmbauche(dateEmbauche);
        appliquerProfils(responsable, profilIds, profilPrincipalId);

        return VueMembre.de(responsables.save(responsable), stockage::urlPublique);
    }

    // -------------------------------------------------------------------------
    // Modification
    // -------------------------------------------------------------------------

    /**
     * Corrige l'identité d'un membre.
     *
     * <p>Ni le type ni l'adresse e-mail n'y figurent. Le type détermine la
     * façon dont les droits sont calculés : le changer transformerait un
     * responsable en administrateur d'un trait de plume, sans trace. L'adresse
     * sert à se connecter et a été vérifiée : la changer ici contournerait
     * cette vérification.</p>
     */
    @Transactional
    public VueMembre modifier(Long id, String nom, String prenom, String telephone,
                              LocalDate dateEmbauche) {
        Utilisateur utilisateur = chargerUtilisateur(id);

        // Le cliché se prend AVANT l'écriture : après, l'ancien n'existe plus
        // nulle part, et « il a modifié la fiche » sans dire quoi ne règle
        // aucun désaccord.
        var avant = JournalActions.cliche(
                "nom", utilisateur.getNom(),
                "prenom", utilisateur.getPrenom(),
                "telephone", utilisateur.getTelephone());

        utilisateur.renommer(nom.strip());
        utilisateur.setPrenom(vide(prenom) ? null : prenom.strip());
        utilisateur.setTelephone(vide(telephone) ? null : telephone.strip());

        journal.enregistrer("MEMBRE_MODIFIER", "utilisateur", id, avant,
                JournalActions.cliche(
                        "nom", utilisateur.getNom(),
                        "prenom", utilisateur.getPrenom(),
                        "telephone", utilisateur.getTelephone()));

        return responsables.chargerAvecCategories(id)
                .map(r -> {
                    r.setDateEmbauche(dateEmbauche);
                    return VueMembre.de(r, stockage::urlPublique);
                })
                .orElseGet(() -> VueMembre.de(utilisateur, stockage::urlPublique));
    }

    /**
     * Remplace les profils d'un responsable.
     *
     * <p>On <b>remplace</b> plutôt que d'ajouter et de retirer un par un :
     * l'écran montre une liste de cases cochées, et c'est cet état-là qu'il
     * envoie. Une API par différence obligerait le frontend à calculer ce qui
     * a changé — un calcul qu'il ferait mal un jour.</p>
     */
    @Transactional
    public VueMembre affecterProfils(Long id, List<Long> profilIds, Long profilPrincipalId) {
        Responsable responsable = responsables.chargerAvecCategories(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Responsable", id));

        // Relevé AVANT le vidage : après, la liste est vide, et « il a changé
        // les profils » ne dit pas lesquels ont été retirés.
        List<Long> anciens = responsable.getCategories().stream()
                .map(rc -> rc.getCategorie().getId())
                .sorted()
                .toList();

        responsable.getCategories().clear();
        // ⚠️ Le vidage doit atteindre la base AVANT les nouvelles lignes :
        // l'index unique sur la catégorie principale refuserait deux « true »
        // simultanés, même si l'ancien est destiné à disparaître.
        responsables.saveAndFlush(responsable);

        appliquerProfils(responsable, profilIds, profilPrincipalId);

        // 🎯 Redistribuer des droits n'est pas corriger un nom. C'est le geste
        //    qui explique, six mois plus tard, pourquoi quelqu'un a pu publier
        //    un produit ou approcher la caisse.
        journal.enregistrer("PROFILS_AFFECTER", "responsable", id,
                JournalActions.cliche("profils", anciens),
                JournalActions.cliche("profils", profilIds,
                        "principal", profilPrincipalId));

        return VueMembre.de(responsables.save(responsable), stockage::urlPublique);
    }

    /**
     * Active ou désactive un compte.
     *
     * <p><b>Jamais de suppression.</b> Le journal de sécurité, les commandes
     * traitées et les conversations portent l'identifiant de cette personne.
     * La supprimer laisserait des lignes orphelines — ou ferait échouer la
     * suppression sur une clé étrangère, ce qui revient au même pour celui qui
     * essaie.</p>
     *
     * <p>Le statut est porté par {@code utilisateur} <b>et</b>, pour un
     * responsable, par {@code responsable}. Les deux sont posés ensemble :
     * n'en changer qu'un laisserait quelqu'un capable de se connecter mais
     * absent des affectations, ou l'inverse.</p>
     */
    @Transactional
    public VueMembre changerStatut(Long id, boolean actif) {
        Utilisateur utilisateur = chargerUtilisateur(id);

        if (utilisateur.getType() == TypeUtilisateur.SUPER_ADMIN) {
            throw new RegleMetierViolee("SUPER_ADMIN_INTOUCHABLE",
                    "Le compte d'amorçage ne peut pas être désactivé : "
                    + "plus personne ne pourrait rendre la main.");
        }

        StatutUtilisateur statut = actif ? StatutUtilisateur.ACTIF : StatutUtilisateur.INACTIF;
        StatutUtilisateur ancien = utilisateur.getStatut();
        utilisateur.setStatut(statut);

        // « Qui a mis ce compte dehors, et quand ? » est une des premières
        // questions posées après un départ conflictuel.
        journal.changement(actif ? "COMPTE_ACTIVER" : "COMPTE_DESACTIVER",
                "utilisateur", id, "statut", ancien, statut);

        return responsables.chargerAvecCategories(id)
                .map(r -> {
                    r.setStatut(statut);
                    return VueMembre.de(r, stockage::urlPublique);
                })
                .orElseGet(() -> VueMembre.de(utilisateur, stockage::urlPublique));
    }

    /**
     * Impose un nouveau mot de passe.
     *
     * <p>Sert au dépannage : quelqu'un a perdu le sien et il faut le remettre
     * en selle. L'ancien n'est pas demandé — un administrateur ne le connaît
     * pas, et c'est très bien ainsi.</p>
     */
    @Transactional
    public void reinitialiserMotDePasse(Long id, String motDePasse) {
        chargerUtilisateur(id).setMotDePasse(encodeur.encode(motDePasse));

        // ⚠️ Le geste seul, sans aucune valeur. Ni l'ancien mot de passe ni le
        //    nouveau, ni leurs empreintes : le journal est immuable et se
        //    conserve, il ne doit jamais devenir l'endroit où les retrouver.
        //    Ce qui compte ici, c'est que quelqu'un a pris la main sur ce
        //    compte, et qui.
        journal.geste("MOT_DE_PASSE_REINITIALISER", "utilisateur", id);
    }

    // -------------------------------------------------------------------------
    // Interne
    // -------------------------------------------------------------------------

    /**
     * Rattache les profils, en garantissant qu'il y en a exactement un principal.
     *
     * <p>I-02 exige une catégorie principale et une seule. La base sait
     * refuser la seconde — un index unique partiel — mais elle ne sait pas
     * exiger la première : « au moins un » ne s'exprime pas en SQL. C'est donc
     * ici, et nulle part ailleurs, que la règle vit.</p>
     */
    private void appliquerProfils(Responsable responsable, List<Long> profilIds,
                                  Long profilPrincipalId) {
        if (profilIds == null || profilIds.isEmpty()) {
            throw new RegleMetierViolee("PROFIL_OBLIGATOIRE",
                    "Un responsable sans profil n'a aucun droit : il pourrait se "
                    + "connecter sans rien pouvoir faire. Choisissez-en au moins un.");
        }

        // À défaut d'indication, le premier profil devient le principal. C'est
        // lui qui donnera son titre, et un titre vaut mieux qu'un refus.
        Long principal = profilPrincipalId != null && profilIds.contains(profilPrincipalId)
                ? profilPrincipalId
                : profilIds.getFirst();

        for (Long profilId : profilIds.stream().distinct().toList()) {
            CategorieResponsable profil = profils.findById(profilId)
                    .orElseThrow(() -> RessourceIntrouvable.de("Profil", profilId));

            if (!"ACTIF".equals(profil.getStatut())) {
                throw new RegleMetierViolee("PROFIL_INACTIF",
                        "Le profil « " + profil.getNom() + " » est désactivé : "
                        + "on ne peut plus y affecter quelqu'un.");
            }

            responsable.ajouterCategorie(profil, profilId.equals(principal));
        }
    }

    private Utilisateur chargerUtilisateur(Long id) {
        return utilisateurs.findById(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Utilisateur", id));
    }

    /** {@code RES-00042}, tiré d'une séquence PostgreSQL (V25). */
    private String genererMatricule() {
        return "RES-%05d".formatted(responsables.prochainMatricule());
    }

    /** L'ordre d'affichage : la hiérarchie, pas l'ordre d'insertion. */
    private static int rang(VueMembre membre) {
        return switch (membre.type()) {
            case "SUPER_ADMIN" -> 0;
            case "ADMIN" -> 1;
            default -> 2;
        };
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}

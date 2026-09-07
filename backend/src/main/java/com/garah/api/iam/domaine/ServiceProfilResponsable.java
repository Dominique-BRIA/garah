package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.infra.CasUtilisationRepository;
import com.garah.api.iam.infra.CategorieResponsableRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Les profils métier — {@code categorie_responsable} dans le schéma.
 *
 * <p>Un profil est un <b>paquet de permissions</b> auquel on rattache des
 * responsables : « Gestionnaire de catalogue », « Agent de service client ».
 * Sans lui, il faudrait accorder les droits un par un à chaque personne, et
 * personne ne saurait plus, six mois plus tard, pourquoi Paul peut publier un
 * produit et Marie non.</p>
 *
 * <p>Les permissions ne s'inventent pas : elles se choisissent dans
 * {@code cas_utilisation}, la table des fonctionnalités réellement
 * implémentées. C'est ce qui empêche un droit d'exister à l'écran sans exister
 * dans le code.</p>
 */
@Service
public class ServiceProfilResponsable {

    private final CategorieResponsableRepository profils;
    private final CasUtilisationRepository casUtilisation;
    private final ResponsableRepository responsables;

    public ServiceProfilResponsable(CategorieResponsableRepository profils,
                                    CasUtilisationRepository casUtilisation,
                                    ResponsableRepository responsables) {
        this.profils = profils;
        this.casUtilisation = casUtilisation;
        this.responsables = responsables;
    }

    @Transactional(readOnly = true)
    public List<VueProfil> lister() {
        return profils.findAll().stream()
                .sorted(Comparator.comparing(CategorieResponsable::getNom,
                        String.CASE_INSENSITIVE_ORDER))
                .map(p -> VueProfil.complet(p, compterMembres(p.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public VueProfil detail(Long id) {
        return VueProfil.complet(charger(id), compterMembres(id));
    }

    /** Le catalogue des fonctionnalités attribuables, groupé par module. */
    @Transactional(readOnly = true)
    public List<VueCasUtilisation> fonctionnalites() {
        return casUtilisation.findAll().stream()
                .filter(c -> "ACTIF".equals(c.getStatut()))
                .sorted(Comparator.comparing(CasUtilisation::getModule)
                        .thenComparing(CasUtilisation::getCode))
                .map(VueCasUtilisation::de)
                .toList();
    }

    @Transactional
    public VueProfil creer(String nom, String description, List<String> codes) {
        String propre = nom.strip();

        if (profils.findByNom(propre).isPresent()) {
            throw new ConflitEtat("PROFIL_EXISTANT", "Un profil porte déjà ce nom.");
        }

        CategorieResponsable profil = new CategorieResponsable(propre);
        profil.setDescription(vide(description) ? null : description.strip());
        appliquerPermissions(profil, codes);

        return VueProfil.complet(profils.save(profil), 0);
    }

    @Transactional
    public VueProfil modifier(Long id, String nom, String description, List<String> codes) {
        CategorieResponsable profil = charger(id);
        String propre = nom.strip();

        // ⚠️ Comparé à l'ancien nom AVANT d'interroger la base : sans ce test,
        // enregistrer un profil sans le renommer le ferait entrer en collision
        // avec lui-même.
        if (!profil.getNom().equals(propre) && profils.findByNom(propre).isPresent()) {
            throw new ConflitEtat("PROFIL_EXISTANT", "Un profil porte déjà ce nom.");
        }

        profil.renommer(propre);
        profil.setDescription(vide(description) ? null : description.strip());

        profil.getCasUtilisation().clear();
        appliquerPermissions(profil, codes);

        return VueProfil.complet(profil, compterMembres(id));
    }

    /**
     * Désactive un profil, jamais ne le supprime.
     *
     * <p>Des responsables y sont rattachés. Le supprimer les priverait
     * silencieusement de leurs droits — ou ferait échouer la suppression sur
     * une clé étrangère, ce qui revient au même pour celui qui essaie.</p>
     *
     * <p>Désactiver n'enlève rien à ceux qui l'ont déjà : cela empêche
     * seulement d'y affecter quelqu'un de nouveau. Retirer les droits de toute
     * une équipe d'un seul clic est précisément ce qu'on ne veut pas rendre
     * facile.</p>
     */
    @Transactional
    public VueProfil changerStatut(Long id, boolean actif) {
        CategorieResponsable profil = charger(id);
        profil.setStatut(actif ? "ACTIF" : "INACTIF");
        return VueProfil.complet(profil, compterMembres(id));
    }

    // -------------------------------------------------------------------------
    // Interne
    // -------------------------------------------------------------------------

    /**
     * Remplace les permissions du profil par celles-ci.
     *
     * <p>On remplace plutôt qu'on n'ajoute et retire un par un : l'écran montre
     * une liste de cases cochées et envoie cet état-là. Une API par différence
     * obligerait le frontend à calculer ce qui a changé — un calcul qu'il
     * ferait mal un jour.</p>
     */
    private void appliquerPermissions(CategorieResponsable profil, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new RegleMetierViolee("PROFIL_SANS_PERMISSION",
                    "Un profil sans aucune permission ne sert à rien : "
                    + "ceux qui le portent pourraient se connecter sans rien pouvoir faire.");
        }

        for (String code : codes.stream().distinct().toList()) {
            CasUtilisation cas = casUtilisation.findByCode(code)
                    .orElseThrow(() -> RessourceIntrouvable.de("Fonctionnalité", code));

            if (!"ACTIF".equals(cas.getStatut())) {
                throw new RegleMetierViolee("CAS_UTILISATION_INACTIF",
                        "La fonctionnalité " + code + " est désactivée : "
                        + "on ne peut pas l'attribuer.");
            }

            profil.accorder(cas);
        }
    }

    /**
     * Combien de responsables portent ce profil.
     *
     * <p>Le chiffre n'est pas décoratif : il dit ce qu'on casse en modifiant
     * le profil. Retirer une permission d'un profil porté par douze personnes
     * la retire aux douze, d'un coup.</p>
     */
    private int compterMembres(Long profilId) {
        return (int) responsables.chargerToutAvecCategories().stream()
                .filter(r -> r.getCategories().stream()
                        .anyMatch(rc -> rc.getCategorie().getId().equals(profilId)))
                .count();
    }

    private CategorieResponsable charger(Long id) {
        return profils.findById(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Profil", id));
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}

package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.infra.HierarchieRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Qui peut agir sur qui.
 *
 * <pre>
 * Super Admin  →  Admin  →  Chef de service  →  Membre
 * </pre>
 *
 * <h2>🎯 Deux conditions, et il faut LES DEUX</h2>
 *
 * <ol>
 *   <li>le <b>rang</b> : on n'agit que sur quelqu'un de rang strictement
 *       inférieur ;</li>
 *   <li>la <b>portée</b> : un chef n'agit que sur les membres des services
 *       qu'il dirige.</li>
 * </ol>
 *
 * <p>N'en vérifier qu'une laisserait passer exactement ce qu'on veut
 * interdire : le rang seul permettrait à un chef de la logistique de
 * désactiver un magasinier du service commercial ; la portée seule
 * permettrait à un chef de désactiver <b>l'Admin</b> qui se trouve, par
 * hasard, affecté à son service.</p>
 *
 * <h2>⚠️ Le contrôle est ici, pas dans un {@code @PreAuthorize}</h2>
 *
 * <p>Une annotation répond à « ce rôle a-t-il le droit de désactiver un
 * responsable ? ». Elle ne peut pas répondre à « a-t-il le droit de
 * désactiver <b>celui-ci</b> ? », qui demande de lire la cible. Les deux sont
 * nécessaires : l'annotation garde la porte, ce service garde la pièce.</p>
 */
@Service
public class ServiceHierarchie {

    private final UtilisateurRepository utilisateurs;
    private final ResponsableRepository responsables;
    private final HierarchieRepository hierarchie;

    public ServiceHierarchie(UtilisateurRepository utilisateurs,
                             ResponsableRepository responsables,
                             HierarchieRepository hierarchie) {
        this.utilisateurs = utilisateurs;
        this.responsables = responsables;
        this.hierarchie = hierarchie;
    }

    /**
     * Le rang de quelqu'un, déduit de son type et de ce qu'il dirige.
     *
     * <p>Voir {@link RangHierarchique} : rien n'est stocké, tout se déduit.</p>
     */
    @Transactional(readOnly = true)
    public RangHierarchique rangDe(Long utilisateurId) {
        Utilisateur utilisateur = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> RessourceIntrouvable.de("Utilisateur", utilisateurId));

        boolean dirige = utilisateur.getType() == TypeUtilisateur.RESPONSABLE
                && !hierarchie.servicesDiriges(utilisateurId).isEmpty();

        return RangHierarchique.de(utilisateur.getType(), dirige);
    }

    /** Les services que dirige ce responsable. Vide s'il n'en dirige aucun. */
    @Transactional(readOnly = true)
    public List<Long> servicesDiriges(Long responsableId) {
        return hierarchie.servicesDiriges(responsableId);
    }

    /**
     * Refuse le geste si l'acteur n'a pas autorité sur la cible.
     *
     * <p>⚠️ On <b>lève</b> plutôt que de rendre un booléen. Un booléen se
     * teste, et un jour quelqu'un oublie de le tester : le geste passe, et
     * rien ne le signale. Une exception ne s'oublie pas.</p>
     *
     * <p>Le message nomme la raison — hors de son service, ou trop haut placé.
     * « Action non autorisée » ferait chercher un droit manquant là où le
     * problème est la personne visée.</p>
     */
    @Transactional(readOnly = true)
    public void exigerAutoriteSur(Long acteurId, Long cibleId) {
        if (acteurId.equals(cibleId)) {
            // ⚠️ Se désactiver soi-même est le seul geste qu'aucune hiérarchie
            //    ne rattrape : plus personne pour rendre la main, et le compte
            //    reste dehors.
            throw new RegleMetierViolee("ACTION_SUR_SOI_MEME",
                    "On ne peut pas appliquer ce geste à son propre compte.");
        }

        RangHierarchique acteur = rangDe(acteurId);
        RangHierarchique cible = rangDe(cibleId);

        if (!acteur.peutAgirSur(cible)) {
            throw new RegleMetierViolee("RANG_INSUFFISANT",
                    "Cette personne est à un niveau égal ou supérieur au vôtre.");
        }

        // Un chef n'a autorité QUE sur son service. Au-dessus, l'autorité est
        // générale : un Admin agit sur toute l'équipe, c'est son métier.
        if (acteur == RangHierarchique.CHEF_DE_SERVICE
                && !hierarchie.estSousMesOrdres(acteurId, cibleId)) {
            throw new RegleMetierViolee("HORS_DE_MON_SERVICE",
                    "Cette personne n'appartient à aucun service que vous dirigez.");
        }
    }

    // -------------------------------------------------------------------------
    // Nommer
    // -------------------------------------------------------------------------

    /**
     * Nomme le chef d'un service, ou le démet.
     *
     * <p>⚠️ Le futur chef doit <b>déjà être membre</b> du service. Un chef qui
     * n'en serait pas membre serait un supérieur sans équipe — et surtout, la
     * ligne qui porte la direction est celle de l'appartenance : il n'y a pas
     * d'endroit où l'écrire autrement.</p>
     *
     * <p>⚠️ Le précédent chef est démis <b>dans la même transaction</b>. Un
     * index unique interdit deux chefs par service ; sans cette démission,
     * nommer le second échouerait sur une contrainte de base, avec un message
     * que personne ne peut lire.</p>
     */
    @Transactional
    public void nommerChef(Long categorieId, Long responsableId) {
        // ⚠️ On vérifie l'appartenance AVANT de démettre : démettre puis
        //    échouer laisserait le service sans chef du tout, pour une
        //    nomination qu'on vient de refuser.
        exigerMembre(categorieId, responsableId);

        // ⚠️ La démission passe par une requête, qui s'exécute TOUT DE SUITE.
        //    Par les entités, Hibernate décidait seul de l'ordre : la
        //    nomination partait avant la démission, et l'index unique refusait
        //    l'écriture avec un message que personne ne peut lire.
        hierarchie.demettreLeChef(categorieId);

        // Rechargé APRÈS la requête : celle-ci a vidé le contexte de
        // persistance, et l'affectation lue avant serait détachée.
        Responsable futur = responsables.chargerAvecCategories(responsableId)
                .orElseThrow(() -> RessourceIntrouvable.de("Responsable", responsableId));

        futur.getCategories().stream()
                .filter(rc -> rc.getCategorie().getId().equals(categorieId))
                .forEach(rc -> rc.nommerChef(true));
    }

    /**
     * Le futur chef doit déjà être membre du service.
     *
     * <p>Un chef qui n'en serait pas membre serait un supérieur sans équipe —
     * et surtout, la ligne qui porte la direction est celle de
     * l'appartenance : il n'y a pas d'endroit où l'écrire autrement.</p>
     */
    private void exigerMembre(Long categorieId, Long responsableId) {
        Responsable futur = responsables.chargerAvecCategories(responsableId)
                .orElseThrow(() -> RessourceIntrouvable.de("Responsable", responsableId));

        boolean membre = futur.getCategories().stream()
                .anyMatch(rc -> rc.getCategorie().getId().equals(categorieId));

        if (!membre) {
            throw new RegleMetierViolee("PAS_MEMBRE_DU_SERVICE",
                    "On ne dirige que le service dont on est membre. "
                    + "Affectez d'abord cette personne au profil.");
        }
    }

    /**
     * Démet le chef actuel d'un service, s'il y en a un.
     *
     * <p>Idempotent : un service sans chef est un état normal, celui d'avant
     * la nomination. Le démettre deux fois ne doit pas échouer.</p>
     */
    @Transactional
    public void demettreLeChef(Long categorieId) {
        hierarchie.demettreLeChef(categorieId);
    }
}

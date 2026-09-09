package com.garah.api.iam.domaine;

import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.iam.infra.CasUtilisationRepository;
import com.garah.api.iam.infra.ResponsableCasUtilisationRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Le calcul des droits d'un responsable.
 *
 * <pre>
 *     union des cas d'utilisation de TOUTES ses catégories
 *   + exceptions individuelles ADD
 *   − exceptions individuelles REMOVE
 *   ─────────────────────────────────────
 *   = permissions effectives
 * </pre>
 */
@Service
public class ServicePermissions {

    private final ResponsableRepository responsables;
    private final CasUtilisationRepository casUtilisation;
    private final ResponsableCasUtilisationRepository exceptions;
    private final JournalActions journal;

    public ServicePermissions(ResponsableRepository responsables,
                              CasUtilisationRepository casUtilisation,
                              ResponsableCasUtilisationRepository exceptions,
                              JournalActions journal) {
        this.responsables = responsables;
        this.casUtilisation = casUtilisation;
        this.exceptions = exceptions;
        this.journal = journal;
    }

    /**
     * Les codes de permission dont dispose réellement ce responsable.
     *
     * <p>{@code readOnly = true} n'est pas cosmétique : Hibernate saute alors
     * la détection des modifications (le <i>dirty checking</i>) et le pilote
     * peut router la requête vers un réplica en lecture. Sur la requête la
     * plus appelée de l'application, ça compte.</p>
     */
    @Transactional(readOnly = true)
    public Set<String> permissionsEffectives(Long responsableId) {
        if (!responsables.existsById(responsableId)) {
            throw RessourceIntrouvable.de("Responsable", responsableId);
        }
        return new LinkedHashSet<>(casUtilisation.permissionsEffectives(responsableId));
    }

    @Transactional(readOnly = true)
    public boolean peut(Long responsableId, String code) {
        return permissionsEffectives(responsableId).contains(code);
    }

    /**
     * Pose une exception individuelle sur un responsable.
     *
     * <p>Deux contrôles avant d'écrire, et ils ne sont pas redondants avec la
     * base :</p>
     * <ul>
     *   <li>le code doit exister — sinon un Admin invente une permission, ce
     *       que tout le modèle cherche à empêcher (chapitre 01 §4) ;</li>
     *   <li>le motif est obligatoire — parce que « pourquoi Paul a-t-il ce
     *       droit ? » est une question d'audit qui se posera.</li>
     * </ul>
     */
    @Transactional
    public void poserException(Long responsableId, String code, TypeException type,
                               String motif, Long accordePar) {
        Responsable responsable = responsables.findById(responsableId)
                .orElseThrow(() -> RessourceIntrouvable.de("Responsable", responsableId));

        CasUtilisation cas = casUtilisation.findByCode(code)
                .orElseThrow(() -> RessourceIntrouvable.de("Cas d'utilisation", code));

        if (!"ACTIF".equals(cas.getStatut())) {
            throw new RegleMetierViolee("CAS_UTILISATION_INACTIF",
                    "La fonctionnalité " + code + " est désactivée : on ne peut pas l'attribuer.");
        }

        if (motif == null || motif.isBlank()) {
            throw new RegleMetierViolee("MOTIF_OBLIGATOIRE",
                    "Une exception de permission doit être justifiée par un motif.");
        }

        // La clé primaire composite (responsable, cas) empêche d'avoir à la fois
        // un ADD et un REMOVE : save() remplace l'exception existante (I-04).
        exceptions.save(new ResponsableCasUtilisation(responsable, cas, type, motif, accordePar));

        // 🎯 C'est LE geste qui contourne les profils : un droit donné à une
        //    personne et à elle seule, sans que le profil qu'elle porte le
        //    dise. Le motif est déjà exigé ; le journal ajoute qui l'a posé et
        //    quand, ce que la ligne d'exception n'apprend pas si on la retire
        //    plus tard.
        journal.enregistrer("PERMISSION_EXCEPTION", "responsable", responsableId, null,
                JournalActions.cliche("permission", code, "sens", type, "motif", motif));
    }
}

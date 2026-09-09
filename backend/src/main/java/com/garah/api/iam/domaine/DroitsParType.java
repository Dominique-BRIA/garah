package com.garah.api.iam.domaine;

import com.garah.api.iam.infra.CasUtilisationRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Les droits qui se DÉDUISENT du type de compte.
 *
 * <pre>
 * SUPER_ADMIN   toutes les fonctionnalités actives
 * ADMIN         toutes sauf le module SECURITE
 * RESPONSABLE   ses profils et ses exceptions — une donnée, pas une déduction
 * CLIENT        aucune : son accès repose sur la PROPRIÉTÉ de ses données
 * </pre>
 *
 * <h2>🎯 Pourquoi cette classe existe</h2>
 *
 * <p>Pour les deux premiers, la liste n'est pas une information <b>sur la
 * personne</b> : c'est une fonction de son type et du catalogue. Elle est
 * <b>engendrée</b>. La faire voyager dans le jeton revenait à transporter à
 * chaque appel ce que le serveur sait recalculer — 197 codes, près de 7 Ko
 * d'en-tête HTTP, sur chaque requête et depuis une connexion mobile.</p>
 *
 * <p>Elle est donc calculée <b>ici</b>, à un seul endroit, et lue aux deux
 * endroits qui en ont besoin : la réponse de connexion, qui la donne à
 * l'écran, et le convertisseur de jeton, qui la donne à Spring Security. Deux
 * copies de cette règle auraient fini par diverger — et « pourquoi cet
 * administrateur voit-il un bouton que l'API lui refuse ? » est une question
 * dont on ne trouve la réponse qu'en relisant les deux.</p>
 *
 * <h2>⚠️ Le catalogue est lu UNE FOIS, et c'est démontrable</h2>
 *
 * <p>{@code cas_utilisation} n'est écrit par <b>aucune route</b> : il est
 * peuplé par les migrations, et rien dans le code ne le modifie à l'exécution.
 * Son contenu ne peut donc pas changer pendant la vie d'une instance, et le
 * garder en mémoire ne peut pas devenir faux.</p>
 *
 * <p>⚠️ <b>Le jour où une route modifiera le référentiel</b> — la permission
 * {@code CAS_UTILISATION_MODIFIER} existe et n'est encore vérifiée nulle
 * part — cette hypothèse tombera, et il faudra vider ce cache à l'écriture.
 * C'est écrit ici parce que c'est ici qu'on le cherchera.</p>
 */
@Component
public class DroitsParType {

    /** Le module réservé au SuperAdmin : référentiel, surveillance, audit. */
    private static final String MODULE_SECURITE = "SECURITE";

    private final CasUtilisationRepository casUtilisation;

    /**
     * ⚠️ {@code volatile} : ces champs sont écrits par le premier fil qui
     * appelle et lus par tous les autres. Sans lui, un fil pourrait voir une
     * référence non nulle vers un ensemble encore vide.
     *
     * <p>Deux fils peuvent charger en même temps au démarrage. Ce n'est pas un
     * problème : la requête est idempotente, les deux résultats sont
     * identiques, et l'ensemble est immuable. Un verrou coûterait plus cher
     * que la lecture qu'il éviterait.</p>
     */
    private volatile Set<String> tous;
    private volatile Set<String> horsSecurite;

    public DroitsParType(CasUtilisationRepository casUtilisation) {
        this.casUtilisation = casUtilisation;
    }

    /**
     * Ce type reçoit-il ses droits par déduction, plutôt que par attribution ?
     *
     * <p>C'est la question qui décide si le jeton doit les énumérer.</p>
     */
    public boolean seDeduisent(TypeUtilisateur type) {
        return type == TypeUtilisateur.SUPER_ADMIN || type == TypeUtilisateur.ADMIN;
    }

    /**
     * Les droits déduits de ce type.
     *
     * @throws IllegalArgumentException pour un type dont les droits ne se
     *         déduisent pas — appeler ici pour un {@code RESPONSABLE} serait
     *         un contresens : ses droits sont une donnée, et rendre un
     *         ensemble vide l'aurait silencieusement privé de tout.
     */
    public Set<String> pour(TypeUtilisateur type) {
        return switch (type) {
            case SUPER_ADMIN -> tous();
            case ADMIN -> horsSecurite();
            case RESPONSABLE, CLIENT -> throw new IllegalArgumentException(
                    "Les droits d'un " + type + " ne se déduisent pas de son type.");
        };
    }

    private Set<String> tous() {
        Set<String> connus = tous;
        if (connus == null) {
            connus = Set.copyOf(casUtilisation.tousLesCodesActifs());
            tous = connus;
        }
        return connus;
    }

    private Set<String> horsSecurite() {
        Set<String> connus = horsSecurite;
        if (connus == null) {
            connus = Set.copyOf(casUtilisation.codesActifsHorsModule(MODULE_SECURITE));
            horsSecurite = connus;
        }
        return connus;
    }
}

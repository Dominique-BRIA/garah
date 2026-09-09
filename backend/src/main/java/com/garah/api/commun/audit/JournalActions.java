package com.garah.api.commun.audit;

import com.garah.api.commun.securite.ActeurCourant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * « Qui a fait quoi » — le geste d'écriture, en une ligne.
 *
 * <pre>
 * journal.changement("PRIX_MODIFIER", "tarification", grille.getId(),
 *                    "prixUnitaire", ancien, nouveau);
 * </pre>
 *
 * <h2>🎯 Pourquoi cette classe plutôt qu'un appel direct au journal</h2>
 *
 * <p>Sans elle, chaque appelant devrait retrouver l'acteur, son nom, son
 * adresse, puis publier l'événement : quatre lignes à recopier partout, et
 * quatre occasions de se tromper. Là où le geste coûte cher à écrire, on
 * finit par ne plus l'écrire — et un journal à trous ne prouve rien.</p>
 *
 * <h2>⚠️ Ce qui n'est PAS journalisé ici</h2>
 *
 * <ul>
 *   <li>ce que fait un <b>client</b> — son parcours est mesuré à part, et le
 *       verser ici noierait les actions internes sous le trafic de la
 *       boutique ;</li>
 *   <li>les <b>lectures</b> — un journal qui note les consultations grossit
 *       cent fois plus vite et répond cent fois moins bien ;</li>
 *   <li>ce que la base sait déjà dire, comme une date de création.</li>
 * </ul>
 */
@Component
public class JournalActions {

    private final ApplicationEventPublisher evenements;
    private final ActeurCourant acteurs;

    public JournalActions(ApplicationEventPublisher evenements, ActeurCourant acteurs) {
        this.evenements = evenements;
        this.acteurs = acteurs;
    }

    /**
     * Un geste sans état à comparer : une validation, une annulation, un envoi.
     *
     * <p>L'action et la cible suffisent. Recopier l'objet entier pour le
     * plaisir d'avoir un « après » ferait grossir le journal sans rien
     * apprendre à personne.</p>
     */
    public void geste(String action, String entite, Long entiteId) {
        publier(action, entite, entiteId, null, null);
    }

    /** Une création : il n'y a pas d'avant. */
    public void creation(String action, String entite, Long entiteId, Object apres) {
        publier(action, entite, entiteId, null, apres);
    }

    /**
     * Le changement d'un champ unique — le cas le plus fréquent.
     *
     * <p>Les deux valeurs sont converties en texte : le journal doit rester
     * lisible dans dix ans, quand la classe qui portait ce champ aura été
     * renommée ou n'existera plus.</p>
     */
    public void changement(String action, String entite, Long entiteId,
                           String champ, Object avant, Object apres) {
        publier(action, entite, entiteId,
                Map.of(champ, String.valueOf(avant)),
                Map.of(champ, String.valueOf(apres)));
    }

    /** Le changement de plusieurs champs à la fois. */
    public void enregistrer(String action, String entite, Long entiteId,
                            Object avant, Object apres) {
        publier(action, entite, entiteId, avant, apres);
    }

    /**
     * Un cliché de quelques champs, dans l'ordre où on les donne.
     *
     * <pre>
     * journal.enregistrer("MEMBRE_MODIFIER", "responsable", id,
     *         JournalActions.cliche("nom", ancienNom, "telephone", ancienTel),
     *         JournalActions.cliche("nom", nouveauNom, "telephone", nouveauTel));
     * </pre>
     *
     * <p>⚠️ Ne jamais passer une entité JPA à sa place. Jackson suivrait les
     * associations, chargerait la moitié de la base pour écrire une ligne de
     * journal, et finirait par y recopier une empreinte de mot de passe.</p>
     *
     * <p>{@code LinkedHashMap} et non {@code Map.of} : l'ordre des champs est
     * celui qu'on a choisi, et une valeur nulle est une information — « le
     * téléphone était vide » — que {@code Map.of} refuserait de porter.</p>
     */
    public static Map<String, String> cliche(Object... champsEtValeurs) {
        if (champsEtValeurs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Un cliché se donne par paires : nom de champ, valeur.");
        }
        Map<String, String> cliche = new LinkedHashMap<>();
        for (int i = 0; i < champsEtValeurs.length; i += 2) {
            cliche.put(String.valueOf(champsEtValeurs[i]),
                    champsEtValeurs[i + 1] == null ? null
                            : String.valueOf(champsEtValeurs[i + 1]));
        }
        return cliche;
    }

    private void publier(String action, String entite, Long entiteId,
                         Object avant, Object apres) {
        evenements.publishEvent(new ActionAuditee(
                acteurs.maintenant(), action, entite, entiteId, avant, apres));
    }
}

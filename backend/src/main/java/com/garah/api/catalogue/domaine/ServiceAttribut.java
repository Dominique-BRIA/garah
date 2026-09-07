package com.garah.api.catalogue.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.catalogue.infra.AttributRepository;
import com.garah.api.catalogue.infra.ValeurAttributRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Le référentiel des dimensions de déclinaison.
 *
 * <p>C'est le <i>variation theme</i> d'Amazon : l'axe selon lequel un produit
 * se décline. Il est <b>partagé par tout le catalogue</b> — « Taille » est
 * défini une fois et réutilisé par tous les vêtements.</p>
 *
 * <p>🎯 <b>Pourquoi partagé, et non défini par produit.</b> Chaque produit
 * inventerait sinon ses propres valeurs : « M », « m », « Medium »,
 * « Moyen ». Quatre écritures pour une seule taille, et plus aucun filtre
 * transversal possible — « montre-moi tout ce qui existe en taille M » n'aurait
 * pas de réponse.</p>
 */
@Service
public class ServiceAttribut {

    /** Deux affichages, et la base n'en accepte pas d'autres (V4). */
    private static final List<String> AFFICHAGES = List.of("LISTE", "PASTILLE");

    private final AttributRepository attributs;
    private final ValeurAttributRepository valeurs;

    public ServiceAttribut(AttributRepository attributs, ValeurAttributRepository valeurs) {
        this.attributs = attributs;
        this.valeurs = valeurs;
    }

    @Transactional(readOnly = true)
    public List<VueAttribut> lister() {
        return attributs.findAll().stream()
                .sorted(Comparator.comparing(Attribut::getNom, String.CASE_INSENSITIVE_ORDER))
                .map(VueAttribut::de)
                .toList();
    }

    @Transactional(readOnly = true)
    public VueAttribut detail(Long id) {
        return VueAttribut.de(charger(id));
    }

    /**
     * Crée une dimension.
     *
     * <p>Le <b>code</b> est engendré depuis le nom : « Pointure » devient
     * {@code POINTURE}. Il est immuable et sert d'identifiant stable — c'est
     * lui qu'un import de fichier ou une traduction désignera, pas le libellé,
     * qui peut changer.</p>
     */
    @Transactional
    public VueAttribut creer(String nom, String typeAffichage) {
        String propre = nom.strip();
        String code = codeDepuis(propre);

        if (attributs.findByCode(code).isPresent()) {
            throw new ConflitEtat("ATTRIBUT_EXISTANT",
                    "Une dimension porte déjà ce nom.");
        }

        return VueAttribut.de(attributs.save(
                new Attribut(code, propre, valider(typeAffichage))));
    }

    /**
     * Ajoute une valeur à une dimension.
     *
     * <p>L'<b>ordre</b> compte et n'est pas cosmétique : trié
     * alphabétiquement, on obtient « 10, 38, 9 ». Une taille se lit dans
     * l'ordre des tailles. À défaut d'indication, la valeur se range à la
     * fin.</p>
     *
     * <p>{@code valeurAffichage} porte la couleur d'une pastille. Sur une
     * dimension affichée en liste, elle n'a pas de sens et reste nulle.</p>
     */
    @Transactional
    public VueAttribut ajouterValeur(Long attributId, String libelle,
                                     String valeurAffichage, Integer ordre) {
        Attribut attribut = charger(attributId);
        String propre = libelle.strip();
        String code = codeDepuis(propre);

        boolean dejaLa = attribut.getValeurs().stream()
                .anyMatch(v -> v.getCode().equals(code));

        if (dejaLa) {
            throw new ConflitEtat("VALEUR_EXISTANTE",
                    "« " + propre + " » existe déjà dans " + attribut.getNom() + ".");
        }

        String couleur = "PASTILLE".equals(attribut.getTypeAffichage())
                ? (vide(valeurAffichage) ? null : valeurAffichage.strip())
                : null;

        ValeurAttribut valeur = attribut.ajouterValeur(code, propre, couleur);
        valeur.setOrdre(ordre != null ? ordre : prochainOrdre(attribut));

        return VueAttribut.de(attribut);
    }

    /**
     * Retire une valeur du référentiel.
     *
     * <p>⚠️ Refusé si des déclinaisons l'utilisent. La table de liaison a une
     * clé étrangère <b>sans cascade</b> : la base refuserait de toute façon,
     * mais avec un message d'intégrité illisible. On préfère dire combien de
     * déclinaisons sont concernées.</p>
     */
    @Transactional
    public void supprimerValeur(Long attributId, Long valeurId) {
        Attribut attribut = charger(attributId);

        ValeurAttribut valeur = attribut.getValeurs().stream()
                .filter(v -> v.getId().equals(valeurId))
                .findFirst()
                .orElseThrow(() -> RessourceIntrouvable.de("Valeur d'attribut", valeurId));

        long utilisations = valeurs.compterUtilisations(valeurId);
        if (utilisations > 0) {
            throw new ConflitEtat("VALEUR_UTILISEE",
                    utilisations + " déclinaison(s) portent « " + valeur.getLibelle()
                    + " ». Retirez-la d'abord de ces déclinaisons.");
        }

        attribut.getValeurs().remove(valeur);
    }

    // -------------------------------------------------------------------------

    private Attribut charger(Long id) {
        return attributs.findById(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Dimension", id));
    }

    private String valider(String typeAffichage) {
        String propre = typeAffichage == null ? "LISTE" : typeAffichage.strip().toUpperCase(Locale.ROOT);
        if (!AFFICHAGES.contains(propre)) {
            throw new RegleMetierViolee("AFFICHAGE_INVALIDE",
                    "Une dimension s'affiche en liste ou en pastille de couleur.");
        }
        return propre;
    }

    /** La valeur ajoutée se range à la fin, pas au début. */
    private static int prochainOrdre(Attribut attribut) {
        return attribut.getValeurs().stream()
                .mapToInt(ValeurAttribut::getOrdre)
                .max()
                .orElse(-1) + 1;
    }

    /**
     * Le code technique, engendré depuis le libellé.
     *
     * <p>« Bleu ciel » devient {@code BLEU_CIEL}. Immuable : c'est lui qu'un
     * import ou une traduction désigne, jamais le libellé — qui peut changer
     * sans que rien d'autre ne bouge.</p>
     */
    private static String codeDepuis(String texte) {
        String sansAccents = Normalizer.normalize(texte, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        String code = sansAccents.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        if (code.isEmpty()) {
            throw new RegleMetierViolee("LIBELLE_INVALIDE",
                    "Ce libellé ne contient aucune lettre ni aucun chiffre.");
        }
        return code.length() <= 40 ? code : code.substring(0, 40);
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}

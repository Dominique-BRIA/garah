package com.garah.api.catalogue.domaine;

import com.garah.api.catalogue.infra.CategorieProduitRepository;
import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Les catégories de produits, organisées en arbre.
 *
 * <p>Deuxième maillon du catalogue : un produit exige une catégorie autant
 * qu'un marchand.</p>
 */
@Service
public class ServiceCategorie {

    /**
     * Profondeur maximale de l'arbre.
     *
     * <p>Rien ne l'impose techniquement. Mais un catalogue à sept niveaux est
     * un catalogue que personne ne parcourt : le visiteur abandonne avant la
     * feuille, et le back-office devient illisible. Trois niveaux couvrent
     * « Vêtements › Homme › Chemises », ce qui suffit.</p>
     */
    private static final int PROFONDEUR_MAX = 3;

    private final CategorieProduitRepository categories;

    public ServiceCategorie(CategorieProduitRepository categories) {
        this.categories = categories;
    }

    /**
     * L'arbre complet, en <b>une seule</b> requête.
     *
     * <p>⚠️ La tentation serait de charger les racines puis, pour chacune, ses
     * enfants. C'est le problème N+1 déguisé en navigation : dix racines de
     * trois enfants font onze requêtes. On charge tout à plat, puis on
     * assemble en mémoire — un catalogue compte quelques dizaines de
     * catégories, jamais des milliers.</p>
     */
    @Transactional(readOnly = true)
    public List<VueCategorie> arbre() {
        List<CategorieProduit> toutes = categories.findAllByOrderByOrdreAsc();

        Map<Long, List<CategorieProduit>> parParent = toutes.stream()
                .filter(c -> c.getParent() != null)
                .collect(Collectors.groupingBy(c -> c.getParent().getId()));

        return toutes.stream()
                .filter(c -> c.getParent() == null)
                .map(racine -> construire(racine, parParent))
                .toList();
    }

    /** La liste à plat, pour une liste déroulante. */
    @Transactional(readOnly = true)
    public List<VueCategorie> listePlate() {
        return categories.findAllByOrderByOrdreAsc().stream()
                .map(VueCategorie::plate)
                .toList();
    }

    @Transactional
    public VueCategorie creer(String nom, Long parentId, int ordre) {
        String slug = Slug.de(nom);

        if (categories.existsBySlug(slug)) {
            throw new ConflitEtat("CATEGORIE_EXISTANTE",
                    "Une catégorie porte déjà ce nom.");
        }

        CategorieProduit parent = null;
        if (parentId != null) {
            parent = charger(parentId);
            verifierProfondeur(parent);
        }

        CategorieProduit categorie = new CategorieProduit(nom.strip(), parent);
        categorie.setOrdre(ordre);

        return VueCategorie.plate(categories.save(categorie));
    }

    /**
     * Renomme une catégorie et change sa place dans la fratrie.
     *
     * <p>Le parent, lui, n'est pas modifiable ici. Déplacer une branche d'un
     * arbre demande de revérifier la profondeur <b>et</b> l'absence de cycle
     * pour toute la descendance, pas seulement pour le nœud déplacé : c'est
     * une autre opération, et la mélanger à un renommage ferait passer un
     * déplacement pour une correction de faute de frappe.</p>
     */
    @Transactional
    public VueCategorie renommer(Long id, String nom, int ordre) {
        CategorieProduit categorie = charger(id);
        categorie.renommer(nom.strip());
        categorie.setOrdre(ordre);
        return VueCategorie.plate(categorie);
    }

    @Transactional
    public VueCategorie changerStatut(Long id, String statut) {
        if (!"ACTIVE".equals(statut) && !"INACTIVE".equals(statut)) {
            throw new RegleMetierViolee("STATUT_INVALIDE",
                    "Le statut doit être ACTIVE ou INACTIVE.");
        }
        CategorieProduit categorie = charger(id);
        categorie.setStatut(statut);
        return VueCategorie.plate(categorie);
    }

    private VueCategorie construire(CategorieProduit noeud,
                                    Map<Long, List<CategorieProduit>> parParent) {
        List<VueCategorie> enfants = parParent.getOrDefault(noeud.getId(), List.of()).stream()
                .map(enfant -> construire(enfant, parParent))
                .toList();

        return VueCategorie.de(noeud, enfants);
    }

    /**
     * Refuse d'enfoncer l'arbre trop profond.
     *
     * <p>La remontée s'arrête aussi sur un <b>cycle</b>. La base interdit
     * qu'une catégorie soit son propre parent, mais pas qu'une chaîne se
     * referme sur elle-même (V4 le dit explicitement : « les cycles plus
     * longs restent à la charge du service »). Sans la garde, cette boucle
     * tournerait indéfiniment.</p>
     */
    private void verifierProfondeur(CategorieProduit parent) {
        Set<Long> vus = new HashSet<>();
        List<Long> chaine = new ArrayList<>();

        CategorieProduit courant = parent;
        while (courant != null) {
            if (!vus.add(courant.getId())) {
                throw new RegleMetierViolee("CATEGORIE_CYCLIQUE",
                        "Cette hiérarchie de catégories forme une boucle.");
            }
            chaine.add(courant.getId());
            courant = courant.getParent();
        }

        if (chaine.size() >= PROFONDEUR_MAX) {
            throw new RegleMetierViolee("CATEGORIE_TROP_PROFONDE",
                    "Une catégorie ne peut pas dépasser " + PROFONDEUR_MAX + " niveaux.");
        }
    }

    private CategorieProduit charger(Long id) {
        return categories.findById(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Catégorie", id));
    }
}

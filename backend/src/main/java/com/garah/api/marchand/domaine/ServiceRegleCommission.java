package com.garah.api.marchand.domaine;

import com.garah.api.commun.audit.JournalActions;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.marchand.infra.MarchandRepository;
import com.garah.api.marchand.infra.RegleCommissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Le paramétrage des taux de commission.
 *
 * <h2>Pourquoi ce service manquait, et ce que ça coûtait</h2>
 *
 * <p>La table {@code regle_commission} existe depuis V11 et
 * {@link ServiceCommission} la lit à chaque vente. Mais <b>aucune route ne
 * permettait d'y écrire</b> : le taux appliqué à toute commande était donc
 * figé à zéro, faute de pouvoir en poser un.</p>
 *
 * <p>Le symptôme est trompeur — rien n'échoue. Les commandes passent, le grand
 * livre s'écrit, les marchands sont crédités : de 100 % de la vente. La perte
 * ne se voit qu'en lisant les écritures, longtemps après.</p>
 *
 * <h2>On ferme, on ne supprime pas</h2>
 *
 * <p>🎯 Une règle est <b>datée</b>. La retirer du calcul se fait en posant une
 * date de fin, jamais en effaçant la ligne : les commandes passées ont figé
 * leur taux, et l'écriture de commission qui va avec doit rester explicable.
 * « Pourquoi 8 % en mars et 5 % en avril ? » est une question qu'on pose des
 * mois plus tard.</p>
 */
@Service
public class ServiceRegleCommission {

    /**
     * Au-delà, on demande confirmation.
     *
     * <p>Ce n'est pas une limite métier — 60 % peut être légitime. C'est un
     * garde-fou contre la virgule mal placée : « 4,5 » saisi « 45 » multiplie
     * la commission par dix, et rien d'autre ne le signalerait avant le
     * premier règlement.</p>
     */
    private static final BigDecimal TAUX_INVRAISEMBLABLE = new BigDecimal("50");

    private static final BigDecimal CENT = new BigDecimal("100");

    /**
     * L'ordre dans lequel le CALCUL départage les règles.
     *
     * <p>⚠️ Miroir exact du {@code ORDER BY} de
     * {@code RegleCommissionRepository.applicables}. S'ils divergent, l'écran
     * montre une règle en tête et le calcul en applique une autre — un écart
     * qu'on ne remarque qu'en comparant deux factures.</p>
     */
    private static final Comparator<RegleCommission> ORDRE_DE_RESOLUTION =
            Comparator.comparingInt(RegleCommission::getPriorite).reversed()
                    .thenComparingInt(r -> r.getMarchandId() != null ? 0 : 1)
                    .thenComparingInt(r -> r.getCategorieProduitId() != null ? 0 : 1);

    private final RegleCommissionRepository regles;
    private final MarchandRepository marchands;

    /**
     * ⚠️ {@code regle_commission} ne porte aucune colonne d'auteur.
     *
     * <p>Un taux décide de ce que l'entreprise prélève sur chaque vente d'un
     * marchand. « Qui a mis ce taux à 30 % ? » est la première question posée
     * quand le décompte du mois surprend quelqu'un, et la table seule n'y
     * répond pas.</p>
     */
    private final JournalActions journal;

    public ServiceRegleCommission(RegleCommissionRepository regles,
                                  MarchandRepository marchands,
                                  JournalActions journal) {
        this.regles = regles;
        this.marchands = marchands;
        this.journal = journal;
    }

    /**
     * Toutes les règles, portées nommées, la plus spécifique en tête.
     *
     * <p>Les règles <b>expirées et futures y figurent</b>. Les masquer ferait
     * chercher pendant une heure pourquoi un taux qu'on a bien paramétré ne
     * s'applique pas — la réponse étant sa date, qu'on ne verrait plus.</p>
     *
     * <p>Trois requêtes en tout : les règles, les marchands cités, les
     * catégories citées.</p>
     */
    @Transactional(readOnly = true)
    public List<VueRegleCommission> lister() {
        List<RegleCommission> toutes = regles.findAll().stream()
                .sorted(ORDRE_DE_RESOLUTION)
                .toList();

        return habiller(toutes);
    }

    /**
     * Pose une règle.
     *
     * <p>{@code marchandId} et {@code categorieProduitId} sont tous deux
     * facultatifs : nuls, la règle vaut pour tout le monde. C'est le cas le
     * plus courant — un taux général — et il ne doit demander aucune saisie.</p>
     *
     * @param confirmeTauxEleve laisse passer un taux supérieur à 50 %.
     */
    @Transactional
    public VueRegleCommission creer(Long marchandId, Long categorieProduitId, BigDecimal taux,
                                    int priorite, LocalDate dateDebut, LocalDate dateFin,
                                    boolean confirmeTauxEleve) {
        verifierTaux(taux, confirmeTauxEleve);
        verifierPortee(marchandId, categorieProduitId);

        if (dateDebut != null && dateFin != null && dateFin.isBefore(dateDebut)) {
            throw new RegleMetierViolee("DATES_INCOHERENTES",
                    "Une règle ne peut pas se terminer avant d'avoir commencé.");
        }

        RegleCommission regle = new RegleCommission(marchandId, categorieProduitId,
                taux, priorite);
        if (dateDebut != null) {
            regle.commencerLe(dateDebut);
        }
        regle.setDateFin(dateFin);

        RegleCommission enregistree = regles.save(regle);

        journal.creation("COMMISSION_CREER", "regle_commission", enregistree.getId(),
                JournalActions.cliche("marchand", marchandId,
                        "categorie", categorieProduitId, "taux", taux,
                        "priorite", priorite, "dateDebut", enregistree.getDateDebut(),
                        "dateFin", dateFin));

        return habiller(List.of(enregistree)).getFirst();
    }

    /**
     * Ferme une règle à une date donnée.
     *
     * <p>C'est le <b>seul</b> moyen de la retirer du calcul — voir l'en-tête de
     * cette classe.</p>
     *
     * <p>Par défaut aujourd'hui, et elle cesse alors de s'appliquer
     * immédiatement : {@code applicables} teste {@code dateFin > jour}, donc
     * une fin posée aujourd'hui exclut aujourd'hui. C'est ce que veut dire
     * « je ne veux plus de ce taux, dès maintenant ».</p>
     */
    @Transactional
    public VueRegleCommission fermer(Long regleId, LocalDate dateFin) {
        RegleCommission regle = regles.findById(regleId)
                .orElseThrow(() -> RessourceIntrouvable.de("Règle de commission", regleId));

        LocalDate fin = dateFin == null ? LocalDate.now() : dateFin;

        if (fin.isBefore(regle.getDateDebut())) {
            throw new RegleMetierViolee("DATES_INCOHERENTES",
                    "Une règle ne peut pas se terminer avant d'avoir commencé.");
        }

        LocalDate ancienneFin = regle.getDateFin();
        regle.setDateFin(fin);

        journal.changement("COMMISSION_FERMER", "regle_commission", regleId,
                "dateFin", ancienneFin, fin);

        return habiller(List.of(regle)).getFirst();
    }

    /**
     * Le taux qui s'appliquerait à cette vente, aujourd'hui.
     *
     * <p>🎯 <b>Une simulation, pas un second calcul.</b> Elle appelle la même
     * requête que la vente réelle. Réécrire ici la logique de résolution
     * donnerait un simulateur qui finirait par répondre autre chose que la
     * réalité — et c'est justement l'écran auquel on fait confiance pour
     * vérifier un paramétrage avant de le laisser tourner.</p>
     */
    @Transactional(readOnly = true)
    public Simulation simuler(Long marchandId, Long categorieProduitId) {
        return regles.applicables(marchandId, categorieProduitId, LocalDate.now())
                .stream()
                .findFirst()
                .map(r -> new Simulation(r.getTaux(), r.getId()))
                // Zéro et pas une erreur : une commande ne doit jamais échouer
                // faute de taux paramétré (voir ServiceCommission).
                .orElse(new Simulation(BigDecimal.ZERO, null));
    }

    /** @param regleId {@code null} si aucune règle ne couvre ce cas. */
    public record Simulation(BigDecimal taux, Long regleId) {
    }

    // -------------------------------------------------------------------------

    /** Résout les noms de portée pour un lot de règles, en deux requêtes. */
    private List<VueRegleCommission> habiller(List<RegleCommission> toutes) {
        Map<Long, String> nomsMarchands = nomsDes(toutes, RegleCommission::getMarchandId);
        Map<Long, String> nomsCategories = nomsDesCategories(toutes);
        LocalDate jour = LocalDate.now();

        return toutes.stream()
                .map(r -> VueRegleCommission.de(r,
                        nom(nomsMarchands, r.getMarchandId()),
                        nom(nomsCategories, r.getCategorieProduitId()),
                        jour))
                .toList();
    }

    /**
     * Cherche un nom sans jamais interroger la carte avec une clé nulle.
     *
     * <p>⚠️ Ce n'est pas une précaution de style. Une règle <b>générale</b> —
     * le cas le plus courant — a un {@code marchandId} nul, et
     * {@code Map.of().get(null)} lève une {@code NullPointerException} : les
     * cartes immuables de Java refusent la clé nulle même en lecture. La
     * variante mutable, elle, aurait répondu {@code null} sans broncher, et le
     * défaut ne serait apparu qu'un jour où aucune règle ne nomme de
     * marchand.</p>
     */
    private static String nom(Map<Long, String> noms, Long id) {
        return id == null ? null : noms.get(id);
    }

    private Map<Long, String> nomsDes(List<RegleCommission> toutes,
                                      java.util.function.Function<RegleCommission, Long> champ) {
        Set<Long> ids = idsNonNuls(toutes, champ);
        return ids.isEmpty() ? Map.of()
                : marchands.nomsPar(ids).stream()
                        .collect(Collectors.toMap(NomMarchand::id, NomMarchand::nom));
    }

    private Map<Long, String> nomsDesCategories(List<RegleCommission> toutes) {
        Set<Long> ids = idsNonNuls(toutes, RegleCommission::getCategorieProduitId);
        return ids.isEmpty() ? Map.of()
                : regles.nomsCategories(ids).stream()
                        .collect(Collectors.toMap(l -> (Long) l[0], l -> (String) l[1]));
    }

    private static Set<Long> idsNonNuls(List<RegleCommission> toutes,
                                        java.util.function.Function<RegleCommission, Long> champ) {
        return toutes.stream()
                .map(champ)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private void verifierTaux(BigDecimal taux, boolean confirme) {
        if (taux == null || taux.signum() < 0 || taux.compareTo(CENT) > 0) {
            // La base le refuserait aussi (regle_commission_taux_valide), mais
            // avec un message que personne ne peut lire.
            throw new RegleMetierViolee("TAUX_INVALIDE",
                    "Un taux de commission se situe entre 0 et 100 %.");
        }
        if (taux.compareTo(TAUX_INVRAISEMBLABLE) > 0 && !confirme) {
            throw new RegleMetierViolee("TAUX_ELEVE",
                    "Un taux de " + taux.stripTrailingZeros().toPlainString()
                    + " % laisse au marchand moins de la moitié de sa vente. "
                    + "Confirmez si c'est voulu.");
        }
    }

    private void verifierPortee(Long marchandId, Long categorieProduitId) {
        if (marchandId != null && !marchands.existsById(marchandId)) {
            throw RessourceIntrouvable.de("Marchand", marchandId);
        }
        if (categorieProduitId != null && regles.compterCategorie(categorieProduitId) == 0) {
            throw RessourceIntrouvable.de("Catégorie", categorieProduitId);
        }
    }
}

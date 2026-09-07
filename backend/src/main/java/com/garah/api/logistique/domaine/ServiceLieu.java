package com.garah.api.logistique.domaine;

import com.garah.api.commun.erreur.ConflitEtat;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import com.garah.api.logistique.infra.LieuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Les lieux de la chaîne logistique.
 *
 * <pre>
 * ENTREPOT            là où la marchandise est stockée
 * POINT_TRANSIT       une étape sur la route Douala → Bertoua → Bangui
 * POINT_RECUPERATION  là où le client vient chercher sa commande
 * </pre>
 *
 * <h2>Pourquoi cet écran débloque tout le reste</h2>
 *
 * <p>Une commande <b>exige</b> un point de récupération (D-05). Tant qu'aucun
 * n'existe, aucune commande ne peut être passée — et le tunnel de vente entier
 * reste impossible à parcourir, quel que soit l'état du catalogue.</p>
 *
 * <p>Les routes de lecture existaient depuis le début ; la création, non.</p>
 */
@Service
public class ServiceLieu {

    private static final String ACTIF = "ACTIF";
    private static final String INACTIF = "INACTIF";

    private final LieuRepository lieux;

    public ServiceLieu(LieuRepository lieux) {
        this.lieux = lieux;
    }

    @Transactional(readOnly = true)
    public List<VueLieu> lister(TypeLieu type) {
        return lieux.findAll().stream()
                .filter(l -> type == null || l.getType() == type)
                .sorted(Comparator.comparing(Lieu::getVille, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Lieu::getNom, String.CASE_INSENSITIVE_ORDER))
                .map(VueLieu::de)
                .toList();
    }

    @Transactional(readOnly = true)
    public VueLieu detail(Long id) {
        return VueLieu.de(charger(id));
    }

    /**
     * Crée un lieu.
     *
     * <p>⚠️ Les <b>frais d'acheminement</b> n'ont de sens que sur un point de
     * récupération : c'est ce que coûte l'acheminement jusqu'à lui, et c'est ce
     * qui sera <b>figé sur la commande</b> (D-11). Un entrepôt ou un point de
     * transit n'en porte pas — les poser là laisserait croire qu'ils
     * s'additionnent le long de la route, ce qui n'est pas le modèle.</p>
     */
    @Transactional
    public VueLieu creer(TypeLieu type, String nom, String pays, String ville,
                         String adresse, String telephone, String horaires,
                         BigDecimal fraisAcheminement) {

        Lieu lieu = new Lieu(type, nom.strip(), normaliserPays(pays), ville.strip());
        appliquer(lieu, adresse, telephone, horaires, fraisAcheminement);

        return VueLieu.de(lieux.save(lieu));
    }

    /**
     * Corrige un lieu.
     *
     * <p>Le <b>type</b> n'y figure pas. Un point de récupération transformé en
     * point de transit laisserait derrière lui des commandes dont le point de
     * retrait n'est plus un point de retrait — et ces commandes, elles, ne
     * peuvent pas changer d'avis.</p>
     */
    @Transactional
    public VueLieu modifier(Long id, String nom, String pays, String ville,
                            String adresse, String telephone, String horaires,
                            BigDecimal fraisAcheminement) {

        Lieu lieu = charger(id);
        lieu.renommer(nom.strip(), normaliserPays(pays), ville.strip());
        appliquer(lieu, adresse, telephone, horaires, fraisAcheminement);

        return VueLieu.de(lieu);
    }

    /**
     * Active ou désactive.
     *
     * <p><b>Jamais de suppression.</b> Des commandes passées portent
     * l'identifiant de ce lieu comme point de retrait ; des colis y sont
     * peut-être encore. Le supprimer laisserait des lignes orphelines.</p>
     *
     * <p>Désactiver le retire des choix proposés au client, sans rien effacer
     * de ce qui s'y est déjà passé.</p>
     */
    @Transactional
    public VueLieu changerStatut(Long id, boolean actif) {
        Lieu lieu = charger(id);

        if (!actif && lieu.getType() == TypeLieu.POINT_RECUPERATION && dernierPointActif(lieu)) {
            // Sans point de récupération actif, plus AUCUNE commande ne peut
            // être passée : le tunnel de vente se ferme d'un clic, et rien à
            // l'écran ne dirait pourquoi.
            throw new ConflitEtat("DERNIER_POINT_RECUPERATION",
                    "C'est le dernier point de récupération actif. Sans lui, "
                    + "aucun client ne pourrait plus commander.");
        }

        lieu.setStatut(actif ? ACTIF : INACTIF);
        return VueLieu.de(lieu);
    }

    // -------------------------------------------------------------------------

    private boolean dernierPointActif(Lieu lieu) {
        return lieux.findByTypeAndStatutOrderByVilleAscNomAsc(TypeLieu.POINT_RECUPERATION, ACTIF)
                .stream()
                .allMatch(l -> l.getId().equals(lieu.getId()));
    }

    private void appliquer(Lieu lieu, String adresse, String telephone, String horaires,
                           BigDecimal frais) {
        lieu.setAdresse(vide(adresse) ? null : adresse.strip());
        lieu.setTelephone(vide(telephone) ? null : telephone.strip());
        lieu.setHoraires(vide(horaires) ? null : horaires.strip());

        // Les frais ne vivent que sur un point de récupération. Ailleurs, on
        // les remet à zéro plutôt que de les ignorer en silence : une valeur
        // stockée mais jamais lue finit par être lue un jour, par erreur.
        lieu.setFraisAcheminement(
                lieu.getType() == TypeLieu.POINT_RECUPERATION && frais != null
                        ? frais
                        : BigDecimal.ZERO);
    }

    private Lieu charger(Long id) {
        return lieux.findById(id)
                .orElseThrow(() -> RessourceIntrouvable.de("Lieu", id));
    }

    /**
     * Le pays, en deux lettres majuscules.
     *
     * <p>Un CODE, jamais un nom en clair : « Cameroun », « Cameroon » et
     * « cameroun » sont trois saisies du même pays et rendent faux tout
     * regroupement.</p>
     */
    private static String normaliserPays(String pays) {
        String propre = pays == null ? "" : pays.strip().toUpperCase(Locale.ROOT);
        if (!propre.matches("[A-Z]{2}")) {
            throw new RegleMetierViolee("PAYS_INVALIDE",
                    "Le pays doit être un code à deux lettres (CM, CF, TD…).");
        }
        return propre;
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}

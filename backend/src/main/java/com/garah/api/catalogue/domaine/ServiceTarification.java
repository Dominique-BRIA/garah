package com.garah.api.catalogue.domaine;

import com.garah.api.catalogue.infra.TarificationRepository;
import com.garah.api.catalogue.infra.VarianteRepository;
import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.erreur.RessourceIntrouvable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Les prix par palier de quantité.
 *
 * <p>Deux idées structurent tout ce service :</p>
 * <ol>
 *   <li>la base garantit qu'<b>au plus un palier</b> s'applique à une quantité
 *       donnée à une date donnée (contrainte d'exclusion I-10) — donc la
 *       requête de sélection ne peut pas être ambiguë ;</li>
 *   <li>on ne <b>modifie jamais</b> un prix : on ferme l'ancien palier et on en
 *       ouvre un nouveau. Le passé reste calculable.</li>
 * </ol>
 */
@Service
public class ServiceTarification {

    private final TarificationRepository tarifications;
    private final VarianteRepository variantes;

    public ServiceTarification(TarificationRepository tarifications, VarianteRepository variantes) {
        this.tarifications = tarifications;
        this.variantes = variantes;
    }

    /**
     * Le prix unitaire applicable à une quantité.
     *
     * <p>C'est la question centrale du module : « combien coûte l'unité si
     * j'en prends 7 ? ». La réponse doit être <b>unique et non ambiguë</b>,
     * et c'est la contrainte d'exclusion en base qui le garantit — pas ce
     * code.</p>
     *
     * <p>Une quantité sans palier n'est pas une erreur technique mais une
     * <b>erreur de saisie du catalogue</b> : elle signifie que personne n'a
     * prévu de prix pour ce volume. Le message doit le dire clairement, sinon
     * le responsable cherchera le bug dans le mauvais endroit.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal prixUnitaire(Long varianteId, int quantite) {
        if (quantite < 1) {
            throw new RegleMetierViolee("QUANTITE_INVALIDE",
                    "La quantité doit être d'au moins 1.");
        }

        return tarifications
                .palierApplicable(varianteId, quantite, LocalDate.now())
                .map(Tarification::getPrixUnitaire)
                .orElseThrow(() -> new RegleMetierViolee("AUCUN_PALIER",
                        "Aucun prix n'est défini pour une quantité de " + quantite + "."));
    }

    /**
     * Le montant total pour une quantité.
     *
     * <p>⚠️ Le prix dégressif s'applique à <b>toutes</b> les unités, pas
     * seulement à celles au-delà du seuil : 10 unités à 11 500 font 115 000,
     * pas « 4 × 15 000 + 6 × 11 500 ». C'est le fonctionnement attendu d'une
     * remise sur quantité, et c'est une question qu'on se pose toujours une
     * fois — autant qu'elle soit écrite.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal montantPour(Long varianteId, int quantite) {
        return prixUnitaire(varianteId, quantite).multiply(BigDecimal.valueOf(quantite));
    }

    /** La grille complète, telle qu'on l'affiche sur la fiche produit. */
    @Transactional(readOnly = true)
    public List<PalierPrix> grille(Long varianteId) {
        return tarifications.paliersEnVigueur(varianteId, LocalDate.now()).stream()
                .map(PalierPrix::de)
                .toList();
    }

    /**
     * Ajoute un palier.
     *
     * <p>La vérification de chevauchement est faite <b>ici</b> pour produire un
     * message utile, et <b>aussi</b> par la base pour qu'aucun chemin ne
     * l'évite (chapitre 04 §3 : défense en profondeur). Ce n'est pas une
     * redondance inutile — c'est ce qui distingue un message clair d'une
     * erreur SQL brute.</p>
     */
    @Transactional
    public PalierPrix definirPalier(Long varianteId, int quantiteMin, Integer quantiteMax,
                                    BigDecimal prixUnitaire) {
        Variante variante = variantes.findById(varianteId)
                .orElseThrow(() -> RessourceIntrouvable.de("Variante", varianteId));

        if (quantiteMin < 1) {
            throw new RegleMetierViolee("PALIER_INVALIDE",
                    "Un palier commence au minimum à 1 unité.");
        }
        if (quantiteMax != null && quantiteMax < quantiteMin) {
            throw new RegleMetierViolee("PALIER_INVALIDE",
                    "La quantité maximale ne peut pas être inférieure à la quantité minimale.");
        }
        if (prixUnitaire.signum() <= 0) {
            throw new RegleMetierViolee("PRIX_INVALIDE",
                    "Un prix doit être strictement positif.");
        }

        LocalDate aujourdhui = LocalDate.now();
        int max = quantiteMax == null ? Integer.MAX_VALUE : quantiteMax;

        if (tarifications.existeChevauchement(varianteId, quantiteMin, max, aujourdhui)) {
            throw new RegleMetierViolee("PALIER_CHEVAUCHANT",
                    "Ce palier chevauche un palier existant : la question "
                    + "« quel prix pour cette quantité ? » aurait deux réponses.");
        }

        return PalierPrix.de(
                tarifications.save(new Tarification(variante, quantiteMin, quantiteMax, prixUnitaire)));
    }

    /**
     * Change le prix d'un palier existant.
     *
     * <p><b>On ne fait PAS un {@code UPDATE} du prix.</b> On ferme l'ancien
     * palier à aujourd'hui, et on en ouvre un nouveau. Deux raisons :</p>
     *
     * <ul>
     *   <li>l'<b>historique reste calculable</b> : on peut encore répondre à
     *       « quel était le prix le 12 mars ? », ce qui compte lors d'un
     *       litige sur une commande ancienne ;</li>
     *   <li>c'est la seule façon de ne pas violer la contrainte d'exclusion,
     *       qui porte aussi sur la période de validité.</li>
     * </ul>
     *
     * <p>Le prix figé dans {@code ligne_commande} rend déjà les commandes
     * passées correctes ; ceci rend en plus la <b>règle</b> reconstituable.</p>
     */
    @Transactional
    public PalierPrix changerPrix(Long tarificationId, BigDecimal nouveauPrix) {
        Tarification ancien = tarifications.findById(tarificationId)
                .orElseThrow(() -> RessourceIntrouvable.de("Palier de prix", tarificationId));

        if (nouveauPrix.signum() <= 0) {
            throw new RegleMetierViolee("PRIX_INVALIDE",
                    "Un prix doit être strictement positif.");
        }

        LocalDate aujourdhui = LocalDate.now();
        ancien.setDateFin(aujourdhui);
        tarifications.saveAndFlush(ancien);   // la fermeture doit précéder l'ouverture

        Tarification nouveau = new Tarification(ancien.getVariante(),
                ancien.getQuantiteMin(), ancien.getQuantiteMax(), nouveauPrix);

        return PalierPrix.de(tarifications.save(nouveau));
    }
}

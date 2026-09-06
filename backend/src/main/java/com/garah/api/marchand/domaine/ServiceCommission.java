package com.garah.api.marchand.domaine;

import com.garah.api.marchand.infra.RegleCommissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Le calcul du taux de commission applicable à une vente.
 *
 * <p>Ce service est appelé <b>une seule fois par ligne de commande</b>, au
 * moment de la vente. Son résultat est ensuite figé : les commandes passées ne
 * bougent plus quand les règles changent.</p>
 */
@Service
public class ServiceCommission {

    private final RegleCommissionRepository regles;

    public ServiceCommission(RegleCommissionRepository regles) {
        this.regles = regles;
    }

    /**
     * Le taux applicable, ou zéro si aucune règle ne couvre ce cas.
     *
     * <p>⚠️ <b>Zéro par défaut, et pas une erreur.</b> C'est un choix : une
     * commande ne doit jamais échouer parce qu'un taux de commission n'a pas
     * été paramétré. On préfère une commission nulle — visible, corrigeable
     * par une écriture d'ajustement — plutôt qu'un client bloqué au paiement
     * pour une raison qui ne le concerne pas.</p>
     *
     * <p>C'est l'inverse du choix fait pour les prix (chapitre 10), où
     * l'absence de palier <b>doit</b> lever une erreur. La différence :
     * un prix manquant est visible du client, une commission manquante ne
     * l'est pas.</p>
     */
    @Transactional(readOnly = true)
    public BigDecimal tauxPour(Long marchandId, Long categorieProduitId) {
        return regles.applicables(marchandId, categorieProduitId, LocalDate.now())
                .stream()
                .findFirst()
                .map(RegleCommission::getTaux)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Le montant de commission sur une ligne.
     *
     * <p>L'arrondi à 2 décimales doit être <b>identique</b> à celui de la
     * contrainte {@code ligne_commande_commission_coherente} :</p>
     *
     * <pre>CHECK (montant_commission = round(montant_ligne * taux_commission / 100, 2))</pre>
     *
     * <p>Un arrondi différent — bancaire d'un côté, arithmétique de l'autre —
     * ferait rejeter des lignes parfaitement légitimes, sur un centime.
     * {@code HALF_UP} correspond au {@code round()} de PostgreSQL sur des
     * {@code numeric}.</p>
     */
    public BigDecimal montant(BigDecimal montantLigne, BigDecimal taux) {
        return montantLigne.multiply(taux)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}

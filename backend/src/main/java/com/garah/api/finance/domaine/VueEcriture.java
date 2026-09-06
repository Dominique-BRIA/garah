package com.garah.api.finance.domaine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Une écriture du grand livre marchand.
 *
 * <p>Le montant reste <b>signé</b> ici, contrairement à un paiement : c'est un
 * grand livre, et le signe EST l'information. Une vente crédite le marchand,
 * un retour le débite (chapitre 17).</p>
 */
public record VueEcriture(
        Long id,
        Long marchandId,
        String type,
        BigDecimal montant,
        String devise,
        String origineType,
        Long origineId,
        String libelle,
        Instant dateEcriture) {

    public static VueEcriture de(EcritureMarchand e) {
        return new VueEcriture(e.getId(), e.getMarchandId(), e.getType().name(),
                e.getMontant(), e.getDevise(),
                e.getOrigineType() == null ? null : e.getOrigineType().name(),
                e.getOrigineId(), e.getLibelle(), e.getDateEcriture());
    }
}

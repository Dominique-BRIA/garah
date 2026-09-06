package com.garah.api.finance.domaine;

import java.math.BigDecimal;
import java.time.Instant;

/** Un règlement versé à un marchand. */
public record VueReglement(
        Long id,
        String numero,
        Long marchandId,
        BigDecimal montant,
        String moyen,
        String reference,
        String statut,
        Instant dateReglement) {

    public static VueReglement de(ReglementMarchand r) {
        return new VueReglement(r.getId(), r.getNumero(), r.getMarchandId(),
                r.getMontant(), r.getMoyen(), r.getReference(), r.getStatut(),
                r.getDateReglement());
    }
}

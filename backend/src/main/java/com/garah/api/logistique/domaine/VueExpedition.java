package com.garah.api.logistique.domaine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Une expédition et ses colis. */
public record VueExpedition(
        Long id,
        String numero,
        Long commandeId,
        Long marchandId,
        Long lieuDepartId,
        Long pointRecuperationId,
        String statut,
        Instant dateCreation,
        Instant dateExpedition,
        List<VueColis> colis) {

    public static VueExpedition resume(Expedition e) {
        return new VueExpedition(e.getId(), e.getNumero(), e.getCommandeId(),
                e.getMarchandId(), e.getLieuDepartId(), e.getPointRecuperationId(),
                e.getStatut().name(), e.getDateCreation(), e.getDateExpedition(), List.of());
    }

    /** ⚠️ À n'appeler que dans une transaction : {@code getColis()} est paresseux. */
    public static VueExpedition complete(Expedition e) {
        return new VueExpedition(e.getId(), e.getNumero(), e.getCommandeId(),
                e.getMarchandId(), e.getLieuDepartId(), e.getPointRecuperationId(),
                e.getStatut().name(), e.getDateCreation(), e.getDateExpedition(),
                e.getColis().stream().map(VueColis::de).toList());
    }

    public record VueColis(Long id, String numeroSuivi, BigDecimal poidsKg, String statut) {
        public static VueColis de(Colis c) {
            return new VueColis(c.getId(), c.getNumeroSuivi(), c.getPoidsKg(),
                    c.getStatut().name());
        }
    }
}

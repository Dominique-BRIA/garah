package com.garah.api.sav.domaine;

import java.time.Instant;
import java.util.List;

/** Un retour de marchandise et ses lignes. */
public record VueRetour(
        Long id,
        String numero,
        Long commandeId,
        Long clientId,
        Long reclamationId,
        String motif,
        String statut,
        Instant dateCreation,
        Instant dateReception,
        List<VueLigne> lignes) {

    public static VueRetour resume(Retour r) {
        return new VueRetour(r.getId(), r.getNumero(), r.getCommandeId(), r.getClientId(),
                r.getReclamationId(), r.getMotif(), r.getStatut().name(),
                r.getDateCreation(), r.getDateReception(), List.of());
    }

    /** ⚠️ À n'appeler que dans une transaction : {@code getLignes()} est paresseux. */
    public static VueRetour complete(Retour r) {
        return new VueRetour(r.getId(), r.getNumero(), r.getCommandeId(), r.getClientId(),
                r.getReclamationId(), r.getMotif(), r.getStatut().name(),
                r.getDateCreation(), r.getDateReception(),
                r.getLignes().stream().map(VueLigne::de).toList());
    }

    public record VueLigne(Long id, Long ligneCommandeId, int quantite,
                           String etatArticle, java.math.BigDecimal montantRembourse) {
        public static VueLigne de(LigneRetour l) {
            return new VueLigne(l.getId(), l.getLigneCommandeId(), l.getQuantite(),
                    l.getEtatArticle() == null ? null : l.getEtatArticle().name(),
                    l.getMontantRembourse());
        }
    }
}

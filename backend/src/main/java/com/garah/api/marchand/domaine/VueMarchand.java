package com.garah.api.marchand.domaine;

import java.time.Instant;

/** Un marchand tel qu'on l'expose. Pas l'entite (chapitre 06). */
public record VueMarchand(
        Long id,
        String code,
        String nom,
        String type,
        String pays,
        String telephone,
        String email,
        /**
         * L'URL du logo, deja signee, ou null.
         *
         * ⚠️ Une URL, jamais la cle d'objet. Le bucket est prive : le frontend
         * ne peut pas fabriquer d'adresse lui-meme, il faudrait signer (D-21).
         *
         * Null n'est pas un manque a combler cote serveur : le frontend
         * engendre un avatar a partir des initiales, qui ne coute rien et suit
         * le theme.
         */
        String urlLogo,
        String statut,
        Instant dateCreation) {

    public static VueMarchand de(Marchand m) {
        return de(m, null);
    }

    public static VueMarchand de(Marchand m, String urlLogo) {
        return new VueMarchand(m.getId(), m.getCode(), m.getNom(), m.getType().name(),
                m.getPays(), m.getTelephone(), m.getEmail(), urlLogo,
                m.getStatut().name(), m.getDateCreation());
    }
}

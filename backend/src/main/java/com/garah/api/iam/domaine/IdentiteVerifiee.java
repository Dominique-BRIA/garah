package com.garah.api.iam.domaine;

/**
 * Ce qu'on sait d'une personne après avoir <b>vérifié</b> son jeton.
 *
 * <p>⚠️ Le mot compte. Ces champs ne viennent pas du corps de la requête :
 * ils sont extraits d'un jeton dont la <b>signature</b> a été validée contre
 * les clés publiques du fournisseur, et dont l'émetteur et le destinataire ont
 * été contrôlés.</p>
 *
 * <p>C'est la même règle que le webhook de paiement (D-17) : <b>on ne croit
 * jamais ce qu'on nous envoie, on vérifie.</b> Un endpoint qui accepterait un
 * {@code email} posté par le client laisserait n'importe qui se connecter en
 * tant que n'importe qui.</p>
 *
 * @param sujet         identifiant stable chez le fournisseur ({@code sub})
 * @param email         adresse annoncée, ou {@code null} (Facebook, TikTok)
 * @param emailVerifie  le fournisseur atteste-t-il cette adresse ?
 * @param nom           nom affiché, ou {@code null}
 */
public record IdentiteVerifiee(
        FournisseurIdentite fournisseur,
        String sujet,
        String email,
        boolean emailVerifie,
        String nom) {
}

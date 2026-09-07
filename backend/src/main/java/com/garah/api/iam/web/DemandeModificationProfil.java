package com.garah.api.iam.web;

import jakarta.validation.constraints.Size;

/**
 * Ce qu'on peut corriger sur son propre compte.
 *
 * <p>⚠️ <b>Aucun champ n'est obligatoire, et c'est le point.</b> Un champ
 * absent (JSON sans la clé, donc {@code null}) n'est pas modifié ; un champ
 * présent mais vide efface la valeur. Cette distinction permet au formulaire
 * de n'envoyer que ce qui a changé, et à quelqu'un de <b>retirer</b> son
 * numéro de téléphone — ce qu'un « obligatoire » rendrait impossible.</p>
 *
 * <p>Le {@code nom} fait exception : vide, il est ignoré plutôt qu'effacé.
 * Un compte sans nom n'a nulle part où s'afficher.</p>
 *
 * <p>🎯 <b>L'adresse e-mail n'y figure pas.</b> Elle est l'identifiant de
 * connexion et la destination des liens de confirmation (D-23) : la changer
 * est un parcours à part entière — vérifier qu'elle est libre, repasser
 * {@code emailVerifie} à faux, réémettre un lien — et non un champ de
 * formulaire.</p>
 */
public record DemandeModificationProfil(

        @Size(max = 100, message = "Le nom ne peut pas dépasser 100 caractères.")
        String nom,

        @Size(max = 100, message = "Le prénom ne peut pas dépasser 100 caractères.")
        String prenom,

        // 30 comme la colonne. Volontairement permissif sur la FORME : les
        // numéros s'écrivent « +237 6 99 00 00 00 », « 699000000 » ou
        // « 00237699000000 », et c'est la passerelle de paiement qui
        // normalise. Refuser ici ferait échouer une saisie parfaitement
        // exploitable.
        @Size(max = 30, message = "Le numéro de téléphone est trop long.")
        String telephone,

        @Size(max = 2, message = "Le code de langue tient en deux lettres.")
        String langue) {
}

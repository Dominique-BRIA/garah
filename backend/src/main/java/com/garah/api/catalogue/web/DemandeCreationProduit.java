package com.garah.api.catalogue.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ce qu'un responsable envoie pour creer une fiche produit.
 *
 * <p>Aucune reference : elle est ENGENDREE par le serveur a partir du code du
 * marchand, de la categorie et du nom (202020-CHA-ADIDAS). La laisser saisir
 * produisait « AD20 », « 202020 », « test2 » — des codes qui ne disaient rien
 * trois mois plus tard, et que deux marchands finissaient par choisir en
 * meme temps.</p>
 */
public record DemandeCreationProduit(

        @NotNull(message = "Le marchand est obligatoire.")
        Long marchandId,

        @NotNull(message = "La categorie est obligatoire.")
        Long categorieId,

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 200, message = "Le nom ne peut pas depasser 200 caracteres.")
        String nom) {
}

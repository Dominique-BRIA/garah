package com.garah.api.logistique.domaine;

import java.time.Instant;

/**
 * Une expédition telle qu'une liste l'affiche.
 *
 * <p>Volontairement sans ses colis : une page de vingt-cinq expéditions n'a
 * pas à transporter leurs cent colis, que personne ne lit avant d'avoir
 * cliqué.</p>
 *
 * <p>Le <b>numéro de commande</b> et le <b>point de récupération</b> y figurent
 * en revanche. Une liste d'expéditions qui n'afficherait que des identifiants
 * numériques obligerait à ouvrir chaque ligne pour savoir de quoi il s'agit —
 * et « où part ce colis ? » est justement la question qu'on se pose en
 * l'ouvrant.</p>
 *
 * @param commandeNumero {@code null} si la commande a disparu. L'expédition,
 *                       elle, reste : elle décrit un mouvement physique qui a
 *                       bien eu lieu.
 */
public record ResumeExpedition(
        Long id,
        String numero,
        Long commandeId,
        String commandeNumero,
        StatutExpedition statut,
        String pointNom,
        String pointVille,
        long nombreColis,
        Instant dateCreation,
        Instant dateExpedition) {
}

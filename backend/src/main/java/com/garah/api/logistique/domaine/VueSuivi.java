package com.garah.api.logistique.domaine;

import java.time.Instant;
import java.util.List;

/**
 * Le suivi d'un colis, tel qu'un inconnu peut le lire.
 *
 * <h2>Pourquoi une vue à part, et pas {@code VueEvenement.publique}</h2>
 *
 * <p>Un événement porte un {@code lieuId}. Sur une route publique, c'est un
 * nombre : « lieu 12 » ne dit rien à personne, et la liste des lieux, elle,
 * demande une authentification. Le suivi affichait donc une colonne
 * <b>illisible</b> — alors que « où est mon colis ? » est la seule question
 * qu'on vient y poser.</p>
 *
 * <p>Cette vue résout le nom du lieu <b>côté serveur</b>, et ne renvoie que ce
 * qui répond à la question.</p>
 *
 * <h2>Ce qui n'y figure pas, et pourquoi</h2>
 *
 * <p>⚠️ <b>Aucun identifiant, aucun nom de personne, aucun montant.</b> Un
 * numéro de suivi circule par SMS, par WhatsApp, sur un bordereau
 * photographié : il ne prouve rien sur l'identité de celui qui le présente.</p>
 *
 * <p>On donne donc le trajet — où, quand — et rien d'autre. Ni le contenu du
 * colis, ni le destinataire, ni l'agent qui a scanné. Ajouter un seul de ces
 * champs « pour rendre service » transformerait un numéro qui traîne en fuite
 * de données.</p>
 */
public record VueSuivi(
        String numeroSuivi,
        String statut,
        List<Etape> etapes) {

    /**
     * Une étape du trajet.
     *
     * @param lieu  le nom résolu, jamais un identifiant. {@code null} si le
     *              lieu a été supprimé — l'étape reste affichée : elle décrit
     *              un mouvement physique qui a bien eu lieu.
     * @param ville la ville, qui suffit souvent à situer sans connaître le nom
     *              de l'agence.
     */
    public record Etape(
            String type,
            String lieu,
            String ville,
            String observation,
            Instant dateHeure) {
    }
}

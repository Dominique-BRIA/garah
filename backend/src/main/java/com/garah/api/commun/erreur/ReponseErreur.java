package com.garah.api.commun.erreur;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * La forme unique de toutes les erreurs de l'API.
 *
 * <p>Les trois frontends peuvent ainsi écrire <b>un seul</b> gestionnaire
 * d'erreurs. Si chaque endpoint renvoyait sa propre forme, chaque écran
 * devrait gérer la sienne.</p>
 *
 * <pre>
 * {
 *   "code": "STOCK_INSUFFISANT",
 *   "message": "Il ne reste que 2 unités disponibles.",
 *   "champs": { "quantite": "maximum 2" },
 *   "horodatage": "2026-09-06T02:41:30Z",
 *   "chemin": "/api/commandes"
 * }
 * </pre>
 *
 * @param code       identifiant technique STABLE, testé par le frontend et
 *                   utilisé comme clé de traduction ({@code erreur.<code>})
 * @param message    texte lisible ; peut changer sans rien casser
 * @param champs     erreurs par champ, uniquement pour les erreurs de validation
 * @param horodatage instant de l'erreur, utile pour retrouver la trace serveur
 * @param chemin     URL appelée
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReponseErreur(
        String code,
        String message,
        Map<String, String> champs,
        Instant horodatage,
        String chemin) {

    public static ReponseErreur de(String code, String message, String chemin) {
        return new ReponseErreur(code, message, null, Instant.now(), chemin);
    }

    public static ReponseErreur validation(String message, Map<String, String> champs, String chemin) {
        return new ReponseErreur("VALIDATION", message, champs, Instant.now(), chemin);
    }
}

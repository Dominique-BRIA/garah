package com.garah.api.commun.securite;

import com.garah.api.commun.web.AdresseClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Qui agit, en ce moment même.
 *
 * <h2>🎯 Pourquoi la question ne se pose pas dans les contrôleurs</h2>
 *
 * <p>Un contrôleur reçoit le jeton et connaît l'acteur ; il ne connaît pas
 * l'<b>avant</b> de ce qu'on modifie. Un service connaît l'avant et l'après ;
 * il ne reçoit pas le jeton. Faire descendre l'acteur en paramètre aurait
 * ajouté trois arguments à une trentaine de méthodes — et le jour où l'un
 * d'eux serait oublié, la ligne de journal porterait le mauvais nom.</p>
 *
 * <p>L'acteur voyage déjà avec la requête, dans le contexte de sécurité. On le
 * lit là où on en a besoin.</p>
 *
 * <h2>⚠️ Hors requête, l'acteur n'est pas « inconnu » : c'est le système</h2>
 *
 * <p>Une tâche planifiée n'a pas de jeton. Écrire {@code null} laisserait
 * croire à une donnée manquante ; « Système » dit ce qui s'est réellement
 * passé, et distingue une commande expirée toute seule d'une commande annulée
 * par quelqu'un.</p>
 */
@Component
public class ActeurCourant {

    /**
     * L'acteur du geste en cours.
     *
     * <p>Ne lève jamais : appelé depuis le chemin d'écriture de dizaines
     * d'actions, il ne doit pas devenir la raison pour laquelle une vente
     * échoue.</p>
     */
    public Acteur maintenant() {
        String ip = adresseIp();
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();

        // Pas de jeton : tâche planifiée, appel interne, ou requête publique.
        // L'instanceof couvre aussi l'authentification anonyme, dont le
        // principal est une simple chaîne.
        if (authentification == null
                || !(authentification.getPrincipal() instanceof Jwt jeton)) {
            return Acteur.systeme(ip);
        }

        Long id = identifiant(jeton);
        String nom = chaine(jeton, "nom");
        String email = chaine(jeton, "email");

        // ⚠️ « CLIENT » et rien d'autre est un client. Un type inconnu — un
        //    jeton d'une version antérieure, un rôle ajouté demain — est traité
        //    comme interne : mieux vaut une ligne de journal en trop qu'un
        //    geste interne qui ne laisse aucune trace.
        Acteur.Nature nature = "CLIENT".equals(chaine(jeton, "type"))
                ? Acteur.Nature.CLIENT
                : Acteur.Nature.INTERNE;

        // Un jeton sans « nom » exploitable reste un acteur identifié : la
        // colonne du nom est obligatoire, l'identifiant suffit à retrouver qui.
        return new Acteur(id, nom != null ? nom : "Compte n° " + id, email, ip, nature);
    }

    private static Long identifiant(Jwt jeton) {
        try {
            return Long.valueOf(jeton.getSubject());
        } catch (NumberFormatException | NullPointerException illisible) {
            return null;
        }
    }

    private static String chaine(Jwt jeton, String nom) {
        Object valeur = jeton.getClaim(nom);
        return valeur instanceof String texte && !texte.isBlank() ? texte : null;
    }

    /**
     * L'adresse d'où part le geste, ou {@code null} hors requête HTTP.
     *
     * <p>Filtrée par {@link AdresseIp} : l'en-tête d'origine est fourni par le
     * client, et la colonne est de type {@code inet}.</p>
     */
    private static String adresseIp() {
        RequestAttributes attributs = RequestContextHolder.getRequestAttributes();
        if (!(attributs instanceof ServletRequestAttributes servlet)) {
            return null;
        }
        HttpServletRequest requete = servlet.getRequest();
        return AdresseIp.normaliser(AdresseClient.de(requete));
    }
}

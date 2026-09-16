package com.garah.api.iam.domaine;

/**
 * Envoie le code par WhatsApp.
 *
 * <p><b>Pourquoi une interface.</b> L'envoi réel appelle l'API Meta — un appel
 * réseau, facturé, vers un service qui exige un compte d'entreprise vérifié.
 * Les tests substituent une doublure : sans cela, la suite dépendrait de la
 * disponibilité de Meta et deviendrait <i>intermittente</i>, ce que le
 * chapitre 20 range au-dessus de tous les autres échecs en nuisance.</p>
 *
 * <p>C'est aussi ce qui permet de développer l'écran <b>avant</b> d'avoir
 * obtenu la vérification Meta Business — laquelle prend des semaines, et
 * bloquerait tout le reste si le code en dépendait.</p>
 */
public interface EnvoiWhatsApp {

    /**
     * @param telephone numéro normalisé E.164
     * @param code      le code en clair — la seule fois où il existe ainsi
     * @return {@code true} si le message est parti
     */
    boolean envoyerLeCode(String telephone, String code);

    /**
     * L'envoi est-il configuré ?
     *
     * <p>Faux tant que les identifiants Meta manquent. Le bouton n'est alors
     * pas proposé du tout : <b>on n'affiche pas une porte murée</b>.</p>
     */
    boolean estActif();
}

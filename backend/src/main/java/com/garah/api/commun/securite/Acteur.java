package com.garah.api.commun.securite;

/**
 * Qui fait le geste en cours, et depuis où.
 *
 * <h2>🎯 Pourquoi une copie et non un identifiant</h2>
 *
 * <p>Le nom est <b>recopié</b>, pas seulement référencé. Un journal qu'on
 * efface en supprimant un compte n'est pas un journal : six mois plus tard,
 * « qui a annulé cette commande ? » doit trouver une réponse même si la
 * personne est partie.</p>
 *
 * <h2>⚠️ Trois natures d'acteur, et elles ne vont pas au même endroit</h2>
 *
 * <pre>
 * SYSTEME   une tâche planifiée, sans personne derrière  → audit_log
 * INTERNE   un responsable, un admin                     → audit_log
 * CLIENT    quelqu'un qui achète                         → PAS audit_log
 * </pre>
 *
 * <p>Le journal d'audit répond à « qui, chez nous, a touché à cette donnée ? ».
 * Y verser le parcours des clients le noierait sous le trafic normal de la
 * boutique, et la question n'aurait plus de réponse exploitable — c'est
 * exactement la confusion que la séparation des trois journaux évite.</p>
 */
public record Acteur(Long id, String nom, String email, String adresseIp, Nature nature) {

    /** Le nom porté par les actions qu'aucune personne n'a déclenchées. */
    public static final String NOM_SYSTEME = "Système";

    public enum Nature {
        SYSTEME,
        INTERNE,
        CLIENT
    }

    /**
     * L'acteur d'une tâche planifiée : personne, mais pas rien.
     *
     * <p>« Le système » est une réponse, et une bonne : elle distingue une
     * commande expirée toute seule d'une commande annulée par quelqu'un. Un
     * acteur nul aurait laissé les deux cas se ressembler.</p>
     */
    public static Acteur systeme(String adresseIp) {
        return new Acteur(null, NOM_SYSTEME, null, adresseIp, Nature.SYSTEME);
    }

    /** Vrai pour ce qui a sa place dans le journal des actions internes. */
    public boolean estInterne() {
        return nature != Nature.CLIENT;
    }
}

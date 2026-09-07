package com.garah.api.logistique.domaine;

/**
 * Ce que l'agent voit après avoir saisi le code présenté par le client.
 *
 * <h2>Pourquoi cette vue existe</h2>
 *
 * <p>Sans elle, confirmer une remise serait un geste <b>aveugle</b> : l'agent
 * taperait huit caractères et cliquerait « Confirmer » sans jamais savoir
 * quels colis il est censé sortir du rayonnage. La confirmation dirait alors
 * seulement que le code est valide — pas que la bonne marchandise est
 * partie.</p>
 *
 * <p>L'ordre des gestes au comptoir est donc : saisir le code, <b>voir ce
 * qu'il désigne</b>, sortir les colis, puis confirmer. Cette vue est l'étape
 * du milieu, et c'est la seule qui empêche la remise du mauvais paquet.</p>
 *
 * <h2>Ce qu'elle ne contient pas</h2>
 *
 * <p>⚠️ Le {@code codeRetrait} est <b>toujours nul</b> ici : la vue est
 * construite à partir d'un code déjà connu de celui qui la demande. Le
 * renvoyer n'apprendrait rien à l'agent — mais le ferait apparaître dans une
 * réponse de plus, donc dans un journal de plus.</p>
 *
 * @param dejaRemis vrai si la marchandise est déjà partie. L'agent doit le
 *                  voir <b>avant</b> de cliquer, pas le découvrir dans un
 *                  message d'erreur : c'est la situation où un client insiste,
 *                  et où l'agent a besoin de la date pour lui répondre.
 */
public record VueComptoir(
        VueRetrait retrait,
        VueExpedition expedition,
        boolean dejaRemis) {

    public static VueComptoir de(RetraitMarchandise r, Expedition e) {
        return new VueComptoir(VueRetrait.sansCode(r), VueExpedition.complete(e),
                r.estConfirme());
    }
}

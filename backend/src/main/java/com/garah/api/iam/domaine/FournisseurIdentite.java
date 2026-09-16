package com.garah.api.iam.domaine;

/**
 * Les fournisseurs d'identité acceptés pour « Continuer avec… ».
 *
 * <p>⚠️ <b>Ils ne se valent pas</b>, et la différence n'est pas technique
 * mais métier : GARAH exige une adresse e-mail — {@code utilisateur.email}
 * est {@code NOT NULL} et unique, et commander exige une adresse
 * <b>confirmée</b> (D-23).</p>
 *
 * <pre>
 * GOOGLE     jeton OIDC standard, avec email ET email_verified
 *            → le compte est utilisable immédiatement
 *
 * FACEBOOK   la permission « email » exige App Review + verification
 *            d entreprise, et un compte cree par telephone n a AUCUNE
 *            adresse → il faudra la demander
 *
 * TIKTOK     aucune adresse n est fournie, jamais
 *            → il faudra la demander, et la faire confirmer
 * </pre>
 *
 * <p>Autrement dit : Google supprime une étape, les deux autres la déplacent.
 * C'est la raison pour laquelle Google est livré seul d'abord — les valeurs
 * existent déjà dans l'énumération et dans la contrainte de la base, pour que
 * les ajouter plus tard ne demande aucune migration.</p>
 */
public enum FournisseurIdentite {

    /**
     * L'émetteur annoncé dans le jeton.
     *
     * <p>C'est cette valeur, et elle seule, qui désigne les clés publiques
     * servant à vérifier la signature. On ne fait jamais confiance à un
     * {@code iss} envoyé par le client : on vérifie que celui du jeton
     * correspond à celui qu'on attendait.</p>
     */
    GOOGLE("https://accounts.google.com", true),

    /** Non implémenté : voir la javadoc de la classe. */
    FACEBOOK("https://www.facebook.com", false),

    /** Non implémenté : voir la javadoc de la classe. */
    TIKTOK("https://www.tiktok.com", false);

    private final String emetteur;
    private final boolean fournitUnEmailVerifie;

    FournisseurIdentite(String emetteur, boolean fournitUnEmailVerifie) {
        this.emetteur = emetteur;
        this.fournitUnEmailVerifie = fournitUnEmailVerifie;
    }

    public String emetteur() {
        return emetteur;
    }

    /**
     * Ce fournisseur atteste-t-il lui-même l'adresse ?
     *
     * <p>Seul un fournisseur qui l'atteste permet de rattacher un compte
     * existant sans mot de passe. Pour les autres, une adresse déjà connue
     * impose de passer par le mot de passe — sinon n'importe qui déclarant
     * l'adresse de quelqu'un d'autre prendrait son compte.</p>
     */
    public boolean fournitUnEmailVerifie() {
        return fournitUnEmailVerifie;
    }
}

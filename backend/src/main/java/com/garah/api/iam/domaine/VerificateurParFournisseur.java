package com.garah.api.iam.domaine;

/**
 * La vérification propre à <b>un</b> fournisseur.
 *
 * <p>Il y en a une implémentation par fournisseur, parce qu'ils ne se
 * ressemblent pas du tout — et c'est le fait le plus important de toute cette
 * fonctionnalité :</p>
 *
 * <pre>
 * GOOGLE     jeton OIDC signe     → on verifie une SIGNATURE, hors ligne
 * FACEBOOK   jeton d acces opaque → on INTERROGE Meta, en ligne, deux fois
 * TIKTOK     code d autorisation  → on l ECHANGE contre un jeton, puis on lit
 * WHATSAPP   (aucun)              → GARAH engendre et verifie son propre code
 * </pre>
 *
 * <p>🎯 « Continuer avec X » se ressemble sur un écran, et ne se ressemble
 * nulle part en dessous. Une seule classe qui les traiterait tous finirait en
 * cascade de {@code if}, et le contrôle le plus important — celui qui vérifie
 * que le jeton a bien été émis <b>pour nous</b> — n'est pas le même dans les
 * trois cas.</p>
 */
public interface VerificateurParFournisseur {

    FournisseurIdentite fournisseur();

    /**
     * @param preuve ce que le client transmet — un jeton d'identité, un jeton
     *               d'accès ou un code d'autorisation, selon le fournisseur
     * @throws VerificateurIdentiteSociale.JetonSocialInvalide si rien ne prouve rien
     */
    IdentiteVerifiee verifier(String preuve);

    /** Configuré ? Sinon le bouton n'est pas proposé. */
    boolean estActif();
}

package com.garah.api.iam.domaine;

import com.garah.api.commun.erreur.ErreurMetier;
import org.springframework.http.HttpStatus;

/**
 * Normalise un numéro de téléphone au format international E.164.
 *
 * <h2>🎯 Pourquoi c'est la classe la plus importante de cette fonctionnalité</h2>
 *
 * <p>Le numéro normalisé est la <b>clé d'identité</b> d'un compte WhatsApp :
 * c'est lui qu'on stocke dans {@code identite_sociale.sujet}, sous un index
 * unique. Si la normalisation n'est pas <b>déterministe</b>, la même personne
 * obtient plusieurs comptes :</p>
 *
 * <pre>
 * « 699 00 00 00 »      →  +237699000000
 * « +237 699 000 000 »  →  +237699000000     la MEME chaine, toujours
 * « 00237699000000 »    →  +237699000000
 * « 237699000000 »      →  +237699000000
 * </pre>
 *
 * <p>⚠️ Sans cela, quelqu'un qui tape son numéro avec des espaces un jour et
 * sans espaces le lendemain se retrouve avec <b>deux comptes</b>, et ses
 * commandes réparties entre les deux. Le défaut ne se voit pas à la connexion
 * — elle réussit — mais au moment où l'historique est vide.</p>
 *
 * <h2>Deux pays, et pas un de plus</h2>
 *
 * <p>GARAH opère sur l'axe Douala → Bangui. On accepte donc le
 * <b>Cameroun (+237)</b> et la <b>République centrafricaine (+236)</b>, et on
 * refuse le reste — plutôt que d'accepter n'importe quel indicatif et de
 * découvrir à la facturation qu'on envoie des messages à l'autre bout du
 * monde.</p>
 *
 * <p>📌 Un indicatif implicite serait un piège : supposer +237 devant un
 * numéro centrafricain à huit chiffres produirait un numéro <b>valide</b>
 * appartenant à quelqu'un d'autre. On exige donc l'indicatif dès que le numéro
 * ne fait pas exactement neuf chiffres camerounais.</p>
 */
public final class NumeroTelephone {

    /** Cameroun : 9 chiffres après l'indicatif, commençant par 6 ou 2. */
    private static final String CAMEROUN = "237";

    /** RCA : 8 chiffres après l'indicatif. */
    private static final String CENTRAFRIQUE = "236";

    private NumeroTelephone() {
    }

    public static class NumeroInvalide extends ErreurMetier {

        public NumeroInvalide() {
            super("NUMERO_INVALIDE",
                    "Ce numéro n'est pas reconnu. Entrez un numéro camerounais "
                            + "(+237) ou centrafricain (+236).");
        }

        @Override
        public HttpStatus getStatut() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }

    /**
     * @return le numéro au format {@code +237699000000}
     * @throws NumeroInvalide si le pays n'est pas desservi, ou la longueur fausse
     */
    public static String normaliser(String saisi) {
        if (saisi == null || saisi.isBlank()) {
            throw new NumeroInvalide();
        }

        // On ne garde que les chiffres. Espaces, points, tirets, parenthèses et
        // le « + » lui-même disparaissent : ce sont des habitudes d'écriture,
        // pas de l'information.
        String chiffres = saisi.replaceAll("[^0-9]", "");

        // Le préfixe international « 00 » est la façon dont on compose depuis
        // un poste fixe, et beaucoup de gens l'écrivent ainsi.
        if (chiffres.startsWith("00")) {
            chiffres = chiffres.substring(2);
        }

        if (chiffres.startsWith(CAMEROUN)) {
            return valider(CAMEROUN, chiffres.substring(CAMEROUN.length()));
        }
        if (chiffres.startsWith(CENTRAFRIQUE)) {
            return valider(CENTRAFRIQUE, chiffres.substring(CENTRAFRIQUE.length()));
        }

        // Aucun indicatif : on ne suppose QUE le Cameroun, et seulement si la
        // longueur ne laisse aucun doute (9 chiffres).
        //
        // ⚠️ Un numéro centrafricain fait 8 chiffres. Lui coller +237 devant
        //    donnerait un numéro camerounais court — refusé plus bas — mais
        //    ajouter un chiffre au hasard pour « réparer » donnerait un numéro
        //    valide APPARTENANT A QUELQU UN D AUTRE. On refuse donc, et
        //    l'interface demande l'indicatif.
        if (chiffres.length() == 9) {
            return valider(CAMEROUN, chiffres);
        }

        throw new NumeroInvalide();
    }

    private static String valider(String indicatif, String national) {
        int attendu = CAMEROUN.equals(indicatif) ? 9 : 8;

        if (national.length() != attendu) {
            throw new NumeroInvalide();
        }

        return "+" + indicatif + national;
    }

    /**
     * Masque un numéro pour l'affichage : {@code +237 6•• •• •• 00}.
     *
     * <p>🎯 L'écran doit confirmer « le code part sur ce numéro » sans
     * <b>révéler</b> le numéro complet. Sur un téléphone partagé ou une capture
     * d'écran envoyée au support, afficher le numéro entier le divulgue à qui
     * regarde — alors que les deux derniers chiffres suffisent à reconnaître le
     * sien.</p>
     */
    public static String masquer(String normalise) {
        if (normalise == null || normalise.length() < 6) {
            return "";
        }
        String debut = normalise.substring(0, 5);
        String fin = normalise.substring(normalise.length() - 2);
        return debut + "•• •• •• " + fin;
    }
}

package com.garah.api.commun.securite;

import java.util.regex.Pattern;

/**
 * Une adresse IP qu'on peut écrire en base sans risque.
 *
 * <h2>⚠️ Ce qui arrive ici est fourni par le client</h2>
 *
 * <p>{@code X-Forwarded-For} est un en-tête HTTP : n'importe qui peut y mettre
 * n'importe quoi. La colonne, elle, est de type {@code inet} — PostgreSQL
 * refuse ce qui n'est pas une adresse, et la refuse au moment de l'écriture.</p>
 *
 * <p>Sans ce filtre, un en-tête fantaisiste ferait échouer l'écriture du
 * journal, donc l'action elle-même : <b>une chaîne de dix caractères dans un
 * en-tête suffirait à empêcher une vente</b>. Le journal des actions se pose
 * sur des chemins qui rapportent de l'argent ; il ne doit jamais devenir le
 * maillon qui les casse.</p>
 *
 * <p>⚠️ Aucune résolution de nom. {@code InetAddress.getByName} interroge le
 * DNS quand la chaîne n'est pas une adresse littérale : un en-tête bien choisi
 * ferait alors partir une requête réseau à chaque appel — vers un serveur
 * choisi par l'appelant.</p>
 */
public final class AdresseIp {

    /**
     * IPv4 en quatre nombres, ou IPv6 en hexadécimal et deux-points.
     *
     * <p>Délibérément permissif <b>dans les bornes du type</b> : on écarte ce
     * qui ne peut pas être une adresse, et on laisse PostgreSQL trancher le
     * reste. Réécrire ici la grammaire complète d'IPv6 serait une seconde
     * implémentation à tenir d'accord avec la première.</p>
     */
    private static final Pattern PLAUSIBLE =
            Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f:]{2,45})$");

    private AdresseIp() {
    }

    /** L'adresse si elle peut en être une, {@code null} sinon. */
    public static String normaliser(String brute) {
        if (brute == null) {
            return null;
        }
        String propre = brute.trim();
        if (propre.isEmpty() || propre.length() > 45 || !PLAUSIBLE.matcher(propre).matches()) {
            return null;
        }
        // Un IPv6 doit contenir « : », un IPv4 des points. « abcdef » passerait
        // le motif hexadécimal sans être une adresse.
        if (!propre.contains(".") && !propre.contains(":")) {
            return null;
        }
        return propre;
    }
}

package com.garah.api.commun.web;

import com.garah.api.commun.stockage.StockageObjet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Ce que les trois frontends ont besoin de savoir avant tout appel.
 *
 * <p><b>Pourquoi une route plutôt qu'un fichier de configuration Angular.</b>
 * Le préfixe des médias change entre le local, Backblaze B2 et le futur VPS
 * (D-14). S'il était écrit dans les trois applications Angular, un changement
 * d'hébergeur imposerait trois modifications, trois compilations et trois
 * déploiements — et le jour où l'un des trois est oublié, ses images
 * disparaissent sans que rien ne signale l'erreur.</p>
 *
 * <p>Ici, <b>l'API est la source unique</b> : elle connaît son propre
 * déploiement, elle l'annonce.</p>
 *
 * <p>⚠️ Cette route est <b>publique</b> : le site vitrine l'appelle avant même
 * qu'un visiteur ait un compte. Elle ne doit donc contenir que des
 * informations qu'on accepterait d'afficher sur une affiche — jamais une clé
 * d'API, jamais un identifiant de bucket privé.</p>
 */
@RestController
@RequestMapping("/api/configuration")
public class ControleurConfiguration {

    private final StockageObjet stockage;
    private final String version;

    /**
     * Le paiement tourne-t-il en DÉMONSTRATION ?
     *
     * <h2>🎯 Personne ne pouvait le savoir</h2>
     *
     * <p>L'opérateur mobile money a deux environnements : une démonstration,
     * où aucun argent ne circule, et la production. Ils se distinguent par une
     * seule variable — {@code GARAH_CAMPAY_BASE_URL} — que rien n'affichait.</p>
     *
     * <p>⚠️ Le danger est asymétrique et silencieux dans les deux sens :</p>
     * <ul>
     *   <li>une boutique restée en démonstration <b>n'encaisse rien</b>, et
     *       le découvre au premier client qui réclame sa marchandise ;</li>
     *   <li>une démonstration passée en production <b>débite pour de vrai</b>
     *       des essais qu'on croyait sans conséquence.</li>
     * </ul>
     *
     * <p>⚠️ Publier ce fait est sans risque : « démonstration » ou
     * « production » n'est pas un secret, et c'est exactement le genre
     * d'information qu'on accepterait d'afficher — la règle de cette route.
     * Ni l'adresse, ni les identifiants ne sortent d'ici.</p>
     *
     * <p>⚠️ Lu comme une PROPRIÉTÉ, pas injecté depuis le module de paiement :
     * {@code commun} ne doit dépendre d'aucun domaine, et le test
     * d'architecture refuserait ce lien.</p>
     */
    private final boolean paiementDemonstration;

    /**
     * L'identifiant du client Google à utiliser côté navigateur.
     *
     * <h2>Pourquoi l'API l'annonce, plutôt que chaque frontend le porter</h2>
     *
     * <p>Trois applications l'utiliseraient : la boutique, le mobile, et
     * demain le back-office. Écrit dans chacune, une rotation d'identifiant
     * imposerait trois modifications, trois compilations, trois déploiements —
     * et le jour où l'une est oubliée, son bouton « Continuer avec Google »
     * échoue sans que rien ne le signale.</p>
     *
     * <p>Ici, l'API est la source unique : elle sait avec quel identifiant elle
     * vérifiera les jetons, donc elle sait lequel annoncer. On ne peut pas se
     * désynchroniser d'avec soi-même.</p>
     *
     * <p>⚠️ <b>Ce n'est pas un secret</b>, et le publier est sans risque : il
     * voyage déjà dans chaque page et dans chaque APK. Le <i>secret</i> client,
     * lui, n'existe même pas dans ce projet — GARAH ne fait que
     * <b>vérifier</b> des jetons, il n'en demande jamais au nom de
     * l'utilisateur.</p>
     *
     * <p>📌 {@code GARAH_GOOGLE_CLIENT_IDS} accepte plusieurs valeurs : ce sont
     * les destinataires <b>acceptés</b> à la vérification (iOS a le sien, et
     * une rotation impose d'accepter l'ancien et le nouveau quelque temps). La
     * <b>première</b> est celle qu'on annonce — donc celle qu'on met à jour en
     * tête le jour d'une rotation.</p>
     *
     * <p>Chaîne vide = le bouton ne doit pas être affiché. Le frontend s'en
     * sert pour ne pas proposer une connexion qui échouerait de toute façon
     * (« dire ce qui manque AVANT le clic »).</p>
     */
    private final String identifiantClientGoogle;

    /**
     * « Continuer avec WhatsApp » peut-il être proposé ?
     *
     * <p>Vrai seulement quand les identifiants Meta sont posés. Le frontend
     * s'en sert pour ne pas afficher un bouton qui échouerait — même règle que
     * pour Google, et même raison : <b>dire ce qui manque avant le clic</b>.</p>
     *
     * <p>⚠️ Un booléen, et non l'identifiant : contrairement à Google, le
     * navigateur n'a <b>rien</b> à connaître de notre configuration Meta. Tout
     * se passe entre le serveur et l'API WhatsApp. Publier le moindre élément
     * ici serait donner sans aucune contrepartie.</p>
     *
     * <p>⚠️ Lu comme une PROPRIÉTÉ, et non injecté depuis le module IAM :
     * {@code commun} ne doit dépendre d'aucun domaine, et le test
     * d'architecture refuserait ce lien. C'est la même raison qui fait lire
     * {@code GARAH_CAMPAY_BASE_URL} ici plutôt que d'appeler le paiement.</p>
     */
    private final boolean whatsappDisponible;

    /**
     * Les boutons « Continuer avec… » que l'écran peut proposer.
     *
     * <p>🎯 <b>L'écran n'affiche que ce qui est là.</b> Un bouton Facebook sur
     * une application dont la configuration Meta n'est pas faite échouerait au
     * clic, et la personne chercherait la panne chez elle — son compte, sa
     * connexion — alors que le manque est chez nous.</p>
     *
     * <p>⚠️ Déduit de la <b>présence des variables</b>, et non injecté depuis le
     * module IAM : {@code commun} ne doit dépendre d'aucun domaine, et le test
     * d'architecture refuserait ce lien. Le prix est une duplication de la
     * règle « configuré = ces deux variables sont posées », qui doit rester
     * d'accord avec chaque {@code VerificateurParFournisseur}.</p>
     */
    private final List<String> fournisseursSociaux;

    public ControleurConfiguration(StockageObjet stockage,
                                   @Value("${GARAH_VERSION:dev}") String version,
                                   @Value("${GARAH_CAMPAY_BASE_URL:}") String urlPaiement,
                                   @Value("${GARAH_GOOGLE_CLIENT_IDS:}") String clientsGoogle,
                                   @Value("${GARAH_WHATSAPP_PHONE_NUMBER_ID:}") String numeroWhatsApp,
                                   @Value("${GARAH_WHATSAPP_TOKEN:}") String jetonWhatsApp,
                                   @Value("${GARAH_FACEBOOK_APP_ID:}") String facebookId,
                                   @Value("${GARAH_FACEBOOK_APP_SECRET:}") String facebookSecret,
                                   @Value("${GARAH_TIKTOK_CLIENT_KEY:}") String tiktokCle,
                                   @Value("${GARAH_TIKTOK_CLIENT_SECRET:}") String tiktokSecret) {
        this.whatsappDisponible =
                !premier(numeroWhatsApp).isEmpty() && !premier(jetonWhatsApp).isEmpty();

        List<String> actifs = new java.util.ArrayList<>();
        if (!premier(clientsGoogle).isEmpty()) {
            actifs.add("GOOGLE");
        }
        if (pose(facebookId) && pose(facebookSecret)) {
            actifs.add("FACEBOOK");
        }
        if (pose(tiktokCle) && pose(tiktokSecret)) {
            actifs.add("TIKTOK");
        }
        if (this.whatsappDisponible) {
            actifs.add("WHATSAPP");
        }
        this.fournisseursSociaux = List.copyOf(actifs);
        this.stockage = stockage;
        this.version = version;
        this.identifiantClientGoogle = premier(clientsGoogle);

        String url = urlPaiement == null ? "" : urlPaiement.toLowerCase(java.util.Locale.ROOT);
        // ⚠️ Non configuré compte AUSSI comme démonstration : une boutique sans
        //    opérateur n'encaisse pas davantage qu'une boutique en bac à sable.
        //    Annoncer « production » dans ce cas serait le pire des deux.
        this.paiementDemonstration = url.isBlank() || url.contains("demo");
    }

    @GetMapping
    public Map<String, Object> configuration() {
        return Map.of(
                "version", version,
                "baseUrlMedias", stockage.baseUrl(),
                // ⚠️ Quand c'est vrai, le frontend NE PEUT PAS fabriquer une URL
                // à partir d'une clé : il faudrait signer, donc détenir la clé
                // secrète. Il doit utiliser les URL complètes renvoyées par
                // l'API, et ne pas les mettre en cache au-delà de quelques
                // jours — elles expirent (D-21).
                "urlsMediasSignees", stockage.urlsSignees(),
                // La devise est fixée par le pays d'exploitation. Elle est
                // annoncée pour que les trois frontends formatent les montants
                // de la même façon, sans la recopier chacun de leur côté.
                "devise", "XAF",
                // D-08 : trois langues. La première est celle par défaut.
                //
                // ⚠️ « sg » et NON « sag ». Le sängö a deux codes normalisés —
                //    `sg` en ISO 639-1, `sag` en 639-2 — et c'est le premier
                //    qui fait foi ici : la table `langue` a `sg` pour clé
                //    primaire, et la colonne `utilisateur.langue` est un
                //    `char(2)` avec une clé étrangère dessus.
                //
                //    Annoncer « sag » n'aurait produit AUCUNE erreur visible.
                //    Un frontend qui construit son sélecteur à partir de cette
                //    liste aurait proposé « sag » ; `langueValide()` l'aurait
                //    rejeté en silence et enregistré « fr ». Le symptôme :
                //    choisir le sängö ne fait rien, indéfiniment, sans une
                //    ligne dans les journaux.
                "langues", List.of("fr", "en", "sg"),

                // ⚠️ Vrai quand AUCUN argent ne circule : bac à sable, ou
                //    opérateur non configuré. Voir le champ du même nom.
                "paiementDemonstration", paiementDemonstration,

                // Vide = ne pas afficher « Continuer avec Google ». Voir le
                // champ du même nom.
                "identifiantClientGoogle", identifiantClientGoogle,

                // Vrai quand Meta est configure. Le frontend s en sert pour
                // ne pas proposer un bouton qui echouerait — meme regle que
                // pour Google, meme raison.
                "whatsappDisponible", whatsappDisponible,

                // Les boutons « Continuer avec… » réellement utilisables.
                // Voir le champ du même nom.
                "fournisseursSociaux", fournisseursSociaux);
    }

    /**
     * La première valeur d'une liste séparée par des virgules.
     *
     * <p>Tolère les espaces et une liste vide : une configuration incomplète
     * doit désactiver le bouton, jamais empêcher l'API de démarrer. Le
     * catalogue n'a rien à voir avec Google et doit continuer de se servir.</p>
     */
    /** Une variable réellement renseignée — vide ne compte pas (leçon du chapitre 21). */
    private static boolean pose(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    private static String premier(String liste) {
        if (liste == null || liste.isBlank()) {
            return "";
        }
        return liste.strip().split("\\s*,\\s*")[0];
    }
}

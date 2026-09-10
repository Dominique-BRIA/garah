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

    public ControleurConfiguration(StockageObjet stockage,
                                   @Value("${GARAH_VERSION:dev}") String version,
                                   @Value("${GARAH_CAMPAY_BASE_URL:}") String urlPaiement) {
        this.stockage = stockage;
        this.version = version;

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
                "paiementDemonstration", paiementDemonstration);
    }
}

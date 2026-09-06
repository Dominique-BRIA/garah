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

    public ControleurConfiguration(StockageObjet stockage,
                                   @Value("${GARAH_VERSION:dev}") String version) {
        this.stockage = stockage;
        this.version = version;
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
                "langues", List.of("fr", "en", "sag"));
    }
}

package com.garah.api.catalogue.domaine;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.commun.stockage.DepotFichiers;
import com.garah.api.commun.stockage.StockageObjet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.List;
import java.util.Set;

/**
 * Téléverser une photo ou une vidéo de produit, de bout en bout.
 *
 * <p>Deux mondes à coordonner, et l'ordre entre eux est <b>le</b> sujet :</p>
 *
 * <pre>
 * 1. le fichier part sur Backblaze B2   (hors transaction, réseau)
 * 2. la ligne `media` est écrite         (transaction courte)
 * </pre>
 *
 * <p>🎯 <b>Pourquoi cet ordre, et pas l'inverse.</b> Les deux systèmes ne
 * partagent aucune transaction : l'un des deux échouera un jour seul. Il faut
 * donc choisir quelle incohérence on préfère.</p>
 *
 * <pre>
 * fichier d'abord   panne après l'étape 1 → un fichier orphelin sur B2
 *                   coût : quelques octets payés pour rien
 *
 * ligne d'abord     panne après l'étape 1 → une ligne `media` sans fichier
 *                   coût : une image cassée sur la fiche produit, visible
 *                   par tous les visiteurs, et impossible à distinguer
 *                   d'un bug d'affichage
 * </pre>
 *
 * <p>On préfère payer du stockage inutile plutôt que d'afficher une image
 * cassée. C'est la règle générale quand deux systèmes ne peuvent pas être
 * transactionnels ensemble : <b>faire d'abord ce dont l'échec coûte le
 * moins</b>.</p>
 *
 * <h2>⚠️ Aucune méthode {@code @Transactional} ici</h2>
 *
 * <p>Et c'est volontaire. Une méthode {@code @Transactional} appelée depuis
 * <b>la même classe</b> ne passe pas par le proxy Spring : l'annotation est
 * purement décorative, et aucune transaction ne s'ouvre. Le piège est
 * silencieux — le code compile, les tests simples passent, et l'atomicité
 * n'existe pas.</p>
 *
 * <p>Toutes les écritures sont donc déléguées à {@link ServiceCatalogue}, qui
 * est un <b>autre</b> bean : ses transactions, elles, sont réelles.</p>
 */
@Service
public class ServiceMediaProduit {

    private static final Logger log = LoggerFactory.getLogger(ServiceMediaProduit.class);

    /** Les types qui font une PHOTO. Le reste est une VIDEO. */
    private static final Set<String> TYPES_IMAGE =
            Set.of("image/jpeg", "image/png", "image/webp", "image/avif");

    /** Assez d'octets pour reconnaître tous les formats acceptés. */
    private static final int OCTETS_SIGNATURE = 16;

    private final ServiceCatalogue catalogue;
    private final DepotFichiers depot;
    private final StockageObjet urls;

    public ServiceMediaProduit(ServiceCatalogue catalogue, DepotFichiers depot,
                               StockageObjet urls) {
        this.catalogue = catalogue;
        this.depot = depot;
        this.urls = urls;
    }

    /**
     * Téléverse un fichier et l'attache au produit.
     *
     * <p><b>Le type déclaré par le client est ignoré.</b> Il est relu dans les
     * premiers octets du fichier : {@code Content-Type} se falsifie en une
     * ligne, et un HTML annoncé {@code image/png} serait ensuite servi depuis
     * le domaine des médias.</p>
     *
     * @param typeDeclare ce que le navigateur annonce — conservé pour le
     *                    diagnostic, jamais pour décider
     */
    public ResumeMedia televerser(Long produitId, String typeDeclare, long taille,
                                  InputStream contenu, boolean principal) {

        // Le produit doit exister AVANT tout téléversement : sinon on paierait
        // du stockage pour un fichier aussitôt jeté, et un identifiant tiré au
        // hasard suffirait à remplir notre bucket.
        catalogue.ficheAdministration(produitId);

        byte[] debut;
        InputStream flux;
        try {
            debut = DepotFichiers.premiersOctets(contenu, OCTETS_SIGNATURE);
            // Les octets lus pour l'inspection sont remis en tête du flux :
            // sans cela le fichier déposé serait amputé de ses 16 premiers
            // octets — donc corrompu, silencieusement.
            flux = new SequenceInputStream(new ByteArrayInputStream(debut), contenu);
        } catch (IOException e) {
            throw new RegleMetierViolee("FICHIER_ILLISIBLE",
                    "Le fichier n'a pas pu être lu.");
        }

        String typeReel = DepotFichiers.typeReel(debut);
        if (typeReel == null) {
            log.warn("Televersement refuse sur le produit {} : contenu non reconnu "
                    + "(type declare : {})", produitId, typeDeclare);
            throw new RegleMetierViolee("TYPE_FICHIER_REFUSE",
                    "Ce fichier n'est pas une image ou une vidéo reconnue. "
                    + "Formats admis : JPEG, PNG, WebP, MP4, WebM.");
        }

        // Étape 1 : le fichier. Hors transaction — un appel réseau qui peut
        // durer trente secondes ne doit jamais retenir une connexion du pool
        // PostgreSQL, réduit à 5 sur Neon (D-14).
        String cle = depot.deposer("produits/" + produitId, typeReel, taille, flux);

        // Étape 2 : la ligne, via l'autre bean (transaction réelle).
        try {
            TypeMedia type = TYPES_IMAGE.contains(typeReel) ? TypeMedia.PHOTO : TypeMedia.VIDEO;
            Media media = catalogue.ajouterMedia(produitId, type, cle, principal);
            return ResumeMedia.de(media, produitId, urls.urlPublique(cle));

        } catch (RuntimeException e) {
            // On tente le nettoyage sans jamais masquer l'erreur d'origine :
            // c'est elle que l'appelant doit voir.
            try {
                depot.supprimer(cle);
            } catch (RuntimeException nettoyage) {
                log.warn("Fichier orphelin sur le stockage : {} ({})",
                        cle, nettoyage.getClass().getSimpleName());
            }
            throw e;
        }
    }

    /** Les médias d'un produit, avec leurs URL publiques. */
    public List<ResumeMedia> lister(Long produitId) {
        return catalogue.mediasDe(produitId).stream()
                .map(m -> ResumeMedia.de(m, produitId, urls.urlPublique(m.getCleObjet())))
                .toList();
    }

    /**
     * Détache un média et supprime son fichier.
     *
     * <p>⚠️ <b>La ligne d'abord, le fichier ensuite</b> — l'inverse du
     * téléversement, et pour la même raison. Si la suppression du fichier
     * échoue, il reste un orphelin ; si on supprimait le fichier d'abord et que
     * la transaction échouait, la fiche afficherait une image cassée.</p>
     *
     * <p>On préfère toujours l'orphelin invisible à l'image cassée.</p>
     */
    public void supprimer(Long mediaId) {
        String cle = catalogue.detacherMedia(mediaId);
        try {
            depot.supprimer(cle);
        } catch (RuntimeException e) {
            // La ligne est déjà partie : le média a disparu de la fiche, ce que
            // l'utilisateur demandait. On ne lui renvoie pas une erreur pour un
            // fichier résiduel qu'il ne peut pas voir.
            log.warn("Fichier orphelin apres suppression du media {} : {}", mediaId, cle);
        }
    }
}

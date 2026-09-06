package com.garah.api.catalogue.domaine;

/**
 * Un média tel qu'on le renvoie après téléversement.
 *
 * <p>Porte <b>les deux</b> : la clé d'objet, qui est ce que la base stocke, et
 * l'URL publique, qui est ce que le navigateur affiche (D-14).</p>
 *
 * <p>Renvoyer l'URL évite au frontend de la reconstruire lui-même — s'il le
 * faisait, le préfixe {@code GARAH_MEDIA_BASE_URL} serait dupliqué dans les
 * trois applications, et changer d'hébergeur de fichiers demanderait quatre
 * déploiements coordonnés au lieu d'un redémarrage.</p>
 */
public record ResumeMedia(
        Long id,
        Long produitId,
        String type,
        String cleObjet,
        String url,
        boolean principal,
        int ordre) {

    /**
     * ⚠️ {@code produitId} est passé en paramètre, <b>jamais lu via
     * {@code media.getProduit()}</b>.
     *
     * <p>{@link Media#getProduit()} est {@code LAZY} : l'appeler après la
     * fermeture de la transaction lèverait un
     * {@code LazyInitializationException}. Et comme cette conversion a
     * justement lieu au retour d'un service, hors transaction, elle échouerait
     * systématiquement — mais seulement à l'exécution, jamais à la
     * compilation.</p>
     *
     * <p>C'est la contrepartie de {@code open-in-view: false} : le chargement
     * paresseux ne s'étend plus jusqu'à la sérialisation JSON, donc tout ce
     * dont on a besoin doit être lu <b>pendant</b> la transaction.</p>
     */
    public static ResumeMedia de(Media media, Long produitId, String url) {
        return new ResumeMedia(
                media.getId(),
                produitId,
                media.getType().name(),
                media.getCleObjet(),
                url,
                media.estPrincipal(),
                media.getOrdre());
    }
}

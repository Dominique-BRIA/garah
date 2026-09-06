package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.JetonRafraichissement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface JetonRafraichissementRepository
        extends JpaRepository<JetonRafraichissement, Long> {

    /**
     * La lecture faite a CHAQUE rafraichissement.
     *
     * <p>On cherche par empreinte SANS filtrer sur la revocation, et c est
     * VOLONTAIRE. Un jeton revoque doit etre TROUVE pour qu on puisse
     * distinguer deux situations que tout oppose :</p>
     *
     * <pre>
     * empreinte absente      jeton inconnu ou jamais emis      → simple refus
     * empreinte revoquee     jeton deja consomme, represente   → VOL PRESUME
     * </pre>
     *
     * <p>Filtrer ici confondrait les deux, et la detection de reutilisation
     * — le seul mecanisme qui reagit a un vol — disparaitrait en silence.</p>
     */
    Optional<JetonRafraichissement> findByEmpreinte(String empreinte);

    /**
     * Revoque toute une famille : la reponse a un vol detecte.
     *
     * <p>{@code date_revocation IS NULL} dans le WHERE protege les traces
     * existantes : un jeton deja marque REUTILISATION garde son motif, sinon
     * on effacerait la preuve du vol en y reagissant.</p>
     */
    @Modifying
    @Query("""
            UPDATE JetonRafraichissement j
               SET j.dateRevocation = :maintenant, j.motifRevocation = :motif
             WHERE j.famille = :famille
               AND j.dateRevocation IS NULL
            """)
    int revoquerFamille(@Param("famille") UUID famille,
                        @Param("motif") JetonRafraichissement.Motif motif,
                        @Param("maintenant") Instant maintenant);

    /**
     * Revoque toutes les sessions d un utilisateur.
     *
     * <p>Alimente « se deconnecter partout », et surtout le blocage de compte :
     * sans cet appel, bloquer un compte n empeche que les NOUVELLES connexions
     * — les sessions ouvertes continuent de se rafraichir pendant deux
     * semaines.</p>
     */
    @Modifying
    @Query("""
            UPDATE JetonRafraichissement j
               SET j.dateRevocation = :maintenant, j.motifRevocation = :motif
             WHERE j.utilisateurId = :utilisateurId
               AND j.dateRevocation IS NULL
            """)
    int revoquerPourUtilisateur(@Param("utilisateurId") Long utilisateurId,
                                @Param("motif") JetonRafraichissement.Motif motif,
                                @Param("maintenant") Instant maintenant);

    /**
     * Supprime les jetons expires depuis un moment.
     *
     * <p>La table ne fait que grossir : un jeton par connexion, puis un de plus
     * toutes les 15 minutes par rotation. Sans purge, elle devient la plus
     * grosse table de la base — le meme piege que {@code vue_produit} (D-15),
     * en plus rapide.</p>
     *
     * <p>On garde une marge apres l expiration : les lignes recentes servent a
     * comprendre un incident (« ce jeton a-t-il ete reutilise ? »).</p>
     */
    @Modifying
    @Query("DELETE FROM JetonRafraichissement j WHERE j.dateExpiration < :limite")
    int purger(@Param("limite") Instant limite);
}

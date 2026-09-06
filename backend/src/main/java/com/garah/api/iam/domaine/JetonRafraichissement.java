package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un jeton de rafraîchissement, stocké sous forme d'empreinte (D-19).
 *
 * <p>C'est l'inverse exact du JWT d'accès :</p>
 *
 * <pre>
 * accès              15 min   signé, aucun état serveur, JAMAIS révocable
 * rafraîchissement   14 j     une ligne ici, révocable à tout instant
 * </pre>
 *
 * <p><b>C'est le stockage qui rend la révocation possible.</b> Un jeton
 * autoporteur ne peut pas être annulé — c'est sa définition. En gardant une
 * ligne, « déconnecter » et « bloquer un compte » cessent d'être des vœux
 * pieux : au pire 15 minutes plus tard, l'accès est réellement coupé.</p>
 *
 * <p>⚠️ La valeur en clair n'existe qu'une fois, dans la réponse HTTP qui la
 * pose en cookie. Elle n'est jamais écrite en base, ni dans un journal.</p>
 */
@Entity
@Table(name = "jeton_rafraichissement")
public class JetonRafraichissement {

    /** Motifs de révocation — doivent correspondre au CHECK de V21. */
    public enum Motif {
        /** Remplacé par un nouveau jeton lors d'un rafraîchissement normal. */
        ROTATION,
        /** L'utilisateur s'est déconnecté. */
        DECONNEXION,
        /** Un jeton déjà consommé a été represénté : vol présumé. */
        REUTILISATION,
        /** Le compte a été bloqué ou désactivé. */
        COMPTE_FERME
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "utilisateur_id", nullable = false, updatable = false)
    private Long utilisateurId;

    /** SHA-256 hexadécimal du jeton. Jamais le jeton lui-même. */
    @Column(nullable = false, length = 64, updatable = false)
    private String empreinte;

    /**
     * Relie tous les jetons issus d'une même connexion.
     *
     * <p>La rotation remplace le jeton mais garde la famille. Si un jeton déjà
     * consommé revient, on révoque la famille entière — ce qui déconnecte le
     * voleur <b>et</b> la victime, plutôt que la victime seule.</p>
     */
    @Column(nullable = false, updatable = false)
    private UUID famille;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_expiration", nullable = false, updatable = false)
    private Instant dateExpiration;

    @Column(name = "date_revocation")
    private Instant dateRevocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "motif_revocation", length = 30)
    private Motif motifRevocation;

    /** Pour l'audit uniquement. Jamais pour autoriser : un en-tête se falsifie. */
    @Column(name = "adresse_ip", length = 45)
    private String adresseIp;

    protected JetonRafraichissement() {
    }

    public JetonRafraichissement(Long utilisateurId, String empreinte, UUID famille,
                                 Instant dateExpiration, String adresseIp) {
        this.utilisateurId = utilisateurId;
        this.empreinte = empreinte;
        this.famille = famille;
        this.dateExpiration = dateExpiration;
        this.adresseIp = adresseIp;
    }

    /**
     * Utilisable = ni révoqué, ni expiré.
     *
     * <p>Les deux conditions sont vérifiées ici, jamais par la requête SQL
     * seule : une requête qui filtrerait sur {@code date_expiration > now()}
     * comparerait l'horloge de la <b>base</b>, et non celle de l'application.
     * L'écart est minime, mais la règle doit vivre à un seul endroit.</p>
     */
    public boolean estUtilisable() {
        return dateRevocation == null && Instant.now().isBefore(dateExpiration);
    }

    /**
     * Révoque, sans jamais écraser une révocation antérieure.
     *
     * <p>⚠️ La garde n'est pas cosmétique : c'est elle qui préserve la
     * détection de vol. Si une rotation pouvait réécrire le motif d'un jeton
     * déjà révoqué pour {@code REUTILISATION}, la trace du vol disparaîtrait —
     * et avec elle la seule preuve qu'il faut alerter l'utilisateur.</p>
     */
    public void revoquer(Motif motif) {
        if (dateRevocation != null) {
            return;
        }
        this.dateRevocation = Instant.now();
        this.motifRevocation = motif;
    }

    public Long getId() { return id; }
    public Long getUtilisateurId() { return utilisateurId; }
    public String getEmpreinte() { return empreinte; }
    public UUID getFamille() { return famille; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateExpiration() { return dateExpiration; }
    public Instant getDateRevocation() { return dateRevocation; }
    public Motif getMotifRevocation() { return motifRevocation; }
    public String getAdresseIp() { return adresseIp; }
}

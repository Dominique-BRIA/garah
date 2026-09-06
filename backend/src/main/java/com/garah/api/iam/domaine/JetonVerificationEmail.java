package com.garah.api.iam.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Un jeton de confirmation d'adresse e-mail (D-23).
 *
 * <p>Même principe que {@link JetonRafraichissement} : on stocke une
 * <b>empreinte</b> SHA-256, jamais la valeur en clair. Qui lirait cette table
 * pourrait sinon confirmer l'adresse de n'importe qui.</p>
 *
 * <p>⚠️ L'adresse visée est <b>figée à l'émission</b>. La relire sur
 * l'utilisateur au moment du clic ouvrirait une faille nette : je m'inscris
 * avec mon adresse, je reçois le lien, je change mon e-mail pour celui de
 * quelqu'un d'autre, puis je clique — et je viens de « confirmer » une adresse
 * que je ne contrôle pas.</p>
 */
@Entity
@Table(name = "jeton_verification_email")
public class JetonVerificationEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "utilisateur_id", nullable = false, updatable = false)
    private Long utilisateurId;

    /** SHA-256 hexadécimal du jeton. Jamais le jeton lui-même. */
    @Column(nullable = false, length = 64, updatable = false)
    private String empreinte;

    /** L'adresse au moment de l'émission — voir l'avertissement de classe. */
    @Column(name = "adresse_visee", nullable = false, length = 255, updatable = false)
    private String adresseVisee;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    @Column(name = "date_expiration", nullable = false, updatable = false)
    private Instant dateExpiration;

    /** Un jeton ne sert qu'une fois. */
    @Column(name = "date_utilisation")
    private Instant dateUtilisation;

    protected JetonVerificationEmail() {
    }

    public JetonVerificationEmail(Long utilisateurId, String empreinte,
                                  String adresseVisee, Instant dateExpiration) {
        this.utilisateurId = utilisateurId;
        this.empreinte = empreinte;
        this.adresseVisee = adresseVisee;
        this.dateExpiration = dateExpiration;
    }

    public boolean estUtilisable() {
        return dateUtilisation == null && Instant.now().isBefore(dateExpiration);
    }

    /**
     * Consomme le jeton.
     *
     * <p>Ne réécrit jamais une utilisation antérieure : la date du <b>premier</b>
     * clic est celle qui compte, et c'est elle qu'on veut retrouver si l'on
     * doit un jour comprendre un incident.</p>
     */
    public void consommer() {
        if (dateUtilisation == null) {
            this.dateUtilisation = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getUtilisateurId() { return utilisateurId; }
    public String getEmpreinte() { return empreinte; }
    public String getAdresseVisee() { return adresseVisee; }
    public Instant getDateCreation() { return dateCreation; }
    public Instant getDateExpiration() { return dateExpiration; }
    public Instant getDateUtilisation() { return dateUtilisation; }
}

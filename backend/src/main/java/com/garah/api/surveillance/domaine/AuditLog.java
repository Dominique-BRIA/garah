package com.garah.api.surveillance.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Qui a modifie quoi, et ce que ca valait avant. IMMUABLE (I-44).
 *
 * <p>Deux details de conception qui font toute la difference.</p>
 *
 * <p><b>1. Le nom de l acteur est COPIE.</b> La cle etrangere est en
 * {@code ON DELETE SET NULL}, jamais CASCADE : un audit qu on efface en
 * supprimant un utilisateur n est pas un audit. Le nom copie survit.</p>
 *
 * <p><b>2. Les valeurs sont en jsonb</b>, pas en texte. On veut pouvoir
 * demander « qui a change le prix de ce produit ? », pas relire des chaines.</p>
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Peut devenir NULL si le compte est supprime. Le nom, lui, reste. */
    @Column(name = "utilisateur_id")
    private Long utilisateurId;

    @Column(name = "acteur_nom", nullable = false, length = 200)
    private String acteurNom;

    @Column(name = "acteur_email", length = 255)
    private String acteurEmail;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(nullable = false, length = 60)
    private String entite;

    @Column(name = "entite_id")
    private Long entiteId;

    @Column(name = "ancienne_valeur", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String ancienneValeur;

    @Column(name = "nouvelle_valeur", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String nouvelleValeur;

    @Column(name = "adresse_ip", length = 45)
    private String adresseIp;

    @Column(name = "date_heure", nullable = false, updatable = false)
    private Instant dateHeure = Instant.now();

    protected AuditLog() {
    }

    public AuditLog(Long utilisateurId, String acteurNom, String acteurEmail,
                    String action, String entite, Long entiteId,
                    String ancienneValeur, String nouvelleValeur, String adresseIp) {
        this.utilisateurId = utilisateurId;
        this.acteurNom = acteurNom;
        this.acteurEmail = acteurEmail;
        this.action = action;
        this.entite = entite;
        this.entiteId = entiteId;
        this.ancienneValeur = ancienneValeur;
        this.nouvelleValeur = nouvelleValeur;
        this.adresseIp = adresseIp;
    }

    public Long getId() { return id; }
    public Long getUtilisateurId() { return utilisateurId; }
    public String getActeurNom() { return acteurNom; }
    public String getActeurEmail() { return acteurEmail; }
    public String getAction() { return action; }
    public String getEntite() { return entite; }
    public Long getEntiteId() { return entiteId; }
    public String getAncienneValeur() { return ancienneValeur; }
    public String getNouvelleValeur() { return nouvelleValeur; }
    public String getAdresseIp() { return adresseIp; }
    public Instant getDateHeure() { return dateHeure; }
}

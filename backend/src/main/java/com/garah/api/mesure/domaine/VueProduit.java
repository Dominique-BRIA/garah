package com.garah.api.mesure.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une consultation de fiche produit.
 *
 * <p>Ce domaine etait TOTALEMENT absent de la specification (correction A2),
 * alors que sa section 20 reclamait « vues, favoris, paniers, produits
 * tendance ».</p>
 *
 * <p>Pourquoi c etait BLOQUANT et pas « a faire plus tard » :</p>
 *
 * <blockquote>
 * Une vue non enregistree est DEFINITIVEMENT perdue.
 * </blockquote>
 *
 * <p>Un ecran se code apres coup. Une donnee non collectee ne se rattrape
 * jamais.</p>
 */
@Entity
@Table(name = "vue_produit")
public class VueProduit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "produit_id", nullable = false)
    private Long produitId;

    /** Null pour un visiteur non connecte : la vitrine est publique. */
    @Column(name = "client_id")
    private Long clientId;

    /**
     * Identifiant de session.
     *
     * <p>C est lui qui permet de compter les vues UNIQUES. Sans lui, un
     * client qui rafraichit dix fois une page compterait pour dix visiteurs —
     * et le classement des produits tendance serait faux.</p>
     */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(length = 20)
    private String source;

    @Column(name = "adresse_ip", length = 45)
    private String adresseIp;

    @Column(name = "date_heure", nullable = false, updatable = false)
    private Instant dateHeure = Instant.now();

    protected VueProduit() {
    }

    public VueProduit(Long produitId, Long clientId, String sessionId,
                      String source, String adresseIp) {
        this.produitId = produitId;
        this.clientId = clientId;
        this.sessionId = sessionId;
        this.source = source;
        this.adresseIp = adresseIp;
    }

    public Long getId() { return id; }
    public Long getProduitId() { return produitId; }
    public Long getClientId() { return clientId; }
    public String getSessionId() { return sessionId; }
    public String getSource() { return source; }
    public Instant getDateHeure() { return dateHeure; }
}

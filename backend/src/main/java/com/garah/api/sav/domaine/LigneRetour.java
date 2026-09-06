package com.garah.api.sav.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * Une ligne de retour : QUOI, COMBIEN, dans QUEL ETAT (correction A4).
 *
 * <p>Sans cette table, le retour portait sur la commande entiere et le cas le
 * plus courant — « il renvoie 3 chemises sur 10, dont 1 abimee » — etait
 * inexprimable.</p>
 *
 * <p>Un TRIGGER (I-40) empeche de retourner plus qu on n a achete, en tenant
 * compte des retours PRECEDENTS : un client peut renvoyer 2 articles en mars
 * et 2 en avril sur les 3 achetes.</p>
 */
@Entity
@Table(name = "ligne_retour")
public class LigneRetour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retour_id")
    private Retour retour;

    @Column(name = "ligne_commande_id", nullable = false)
    private Long ligneCommandeId;

    @Column(nullable = false)
    private int quantite;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_article", nullable = false, length = 20)
    private EtatArticle etatArticle;

    @Column(name = "montant_rembourse", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantRembourse = BigDecimal.ZERO;

    protected LigneRetour() {
    }

    LigneRetour(Retour retour, Long ligneCommandeId, int quantite, EtatArticle etatArticle) {
        this.retour = retour;
        this.ligneCommandeId = ligneCommandeId;
        this.quantite = quantite;
        this.etatArticle = etatArticle;
    }

    void definirRemboursement(BigDecimal montant) {
        this.montantRembourse = montant;
    }

    public Long getId() { return id; }
    public Retour getRetour() { return retour; }
    public Long getLigneCommandeId() { return ligneCommandeId; }
    public int getQuantite() { return quantite; }
    public EtatArticle getEtatArticle() { return etatArticle; }
    public BigDecimal getMontantRembourse() { return montantRembourse; }
}

package com.garah.api.commerce.domaine;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Une ligne de commande — et le meilleur exemple de la <b>règle de la
 * photographie</b> (règle fondatrice n°4).
 *
 * <p>Cinq champs sont des <b>copies</b>, volontairement :</p>
 *
 * <pre>
 * designation        le produit peut être renommé demain
 * attributs          « M / Bleu » au moment de l'achat
 * marchandId         le produit peut CHANGER de marchand
 * prixUnitaire       le tarif évoluera
 * tauxCommission     la règle de commission évoluera
 * </pre>
 *
 * <p>Sans ces copies, une facture de mars affichée en septembre montrerait le
 * nom actuel, le prix actuel et le marchand actuel — et on devrait de l'argent
 * au mauvais partenaire (correction A13).</p>
 */
@Entity
@Table(name = "ligne_commande")
public class LigneCommande {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commande_id")
    private Commande commande;

    @Column(name = "variante_id", nullable = false)
    private Long varianteId;

    /** PHOTO. Le produit peut changer de marchand après la vente. */
    @Column(name = "marchand_id", nullable = false)
    private Long marchandId;

    /** Justifie un prix inférieur au tarif public (chapitre 14). */
    @Column(name = "proposition_prix_id")
    private Long propositionPrixId;

    @Column(nullable = false, length = 250)
    private String designation;

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String attributs = "{}";

    @Column(nullable = false)
    private int quantite;

    @Column(name = "prix_unitaire", nullable = false, precision = 15, scale = 2)
    private BigDecimal prixUnitaire;

    @Column(name = "montant_ligne", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantLigne;

    @Column(name = "taux_tva", nullable = false, precision = 5, scale = 2)
    private BigDecimal tauxTva = BigDecimal.ZERO;

    @Column(name = "montant_tva", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantTva = BigDecimal.ZERO;

    @Column(name = "taux_commission", nullable = false, precision = 5, scale = 2)
    private BigDecimal tauxCommission = BigDecimal.ZERO;

    @Column(name = "montant_commission", nullable = false, precision = 15, scale = 2)
    private BigDecimal montantCommission = BigDecimal.ZERO;

    protected LigneCommande() {
    }

    public LigneCommande(Commande commande, Long varianteId, Long marchandId, String designation,
                         String attributs, int quantite, BigDecimal prixUnitaire,
                         BigDecimal tauxTva, BigDecimal tauxCommission) {
        this.commande = commande;
        this.varianteId = varianteId;
        this.marchandId = marchandId;
        this.designation = designation;
        this.attributs = attributs == null ? "{}" : attributs;
        this.quantite = quantite;
        this.prixUnitaire = prixUnitaire;
        this.tauxTva = tauxTva;
        this.tauxCommission = tauxCommission;

        this.montantLigne = prixUnitaire.multiply(BigDecimal.valueOf(quantite));
        this.montantTva = extraireTva(montantLigne, tauxTva);
        this.montantCommission = calculerCommission(montantLigne, tauxCommission);
    }

    /**
     * La TVA est <b>extraite</b> d'un prix TTC, jamais ajoutée (D-11).
     *
     * <pre>montant_tva = TTC × taux ÷ (100 + taux)</pre>
     *
     * <p>Sur 15 000 TTC à 19,25 % : 2 421,38 — et non 2 887,50, qui serait le
     * résultat d'une TVA ajoutée à un prix HT.</p>
     *
     * <p>⚠️ L'arrondi doit être <b>exactement</b> celui de la contrainte
     * {@code ligne_commande_tva_coherente}. Un {@code HALF_EVEN} ici contre un
     * {@code round()} PostgreSQL là ferait rejeter des lignes légitimes pour
     * un centime.</p>
     */
    private static BigDecimal extraireTva(BigDecimal montantTtc, BigDecimal taux) {
        if (taux.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return montantTtc.multiply(taux)
                .divide(BigDecimal.valueOf(100).add(taux), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculerCommission(BigDecimal montantLigne, BigDecimal taux) {
        return montantLigne.multiply(taux)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public Long getId() { return id; }
    public Commande getCommande() { return commande; }
    public Long getVarianteId() { return varianteId; }
    public Long getMarchandId() { return marchandId; }
    public Long getPropositionPrixId() { return propositionPrixId; }
    public String getDesignation() { return designation; }
    public String getAttributs() { return attributs; }
    public int getQuantite() { return quantite; }
    public BigDecimal getPrixUnitaire() { return prixUnitaire; }
    public BigDecimal getMontantLigne() { return montantLigne; }
    public BigDecimal getTauxTva() { return tauxTva; }
    public BigDecimal getMontantTva() { return montantTva; }
    public BigDecimal getTauxCommission() { return tauxCommission; }
    public BigDecimal getMontantCommission() { return montantCommission; }

    public void setPropositionPrixId(Long id) { this.propositionPrixId = id; }
}

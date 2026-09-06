package com.garah.api.catalogue.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Une photo ou une vidéo de produit.
 *
 * <p><b>On stocke une CLÉ D'OBJET, jamais une URL complète</b> (D-14) :</p>
 *
 * <pre>
 * ✅ produits/42/photo-1.jpg
 * ❌ https://f003.backblazeb2.com/file/garah-medias/produits/42/photo-1.jpg
 * </pre>
 *
 * <p>L'URL est reconstruite à l'affichage à partir d'une variable
 * d'environnement. Sinon, passer de Backblaze B2 à MinIO sur le VPS obligerait
 * à réécrire toutes les lignes de la table — et les anciennes lignes
 * pointeraient vers un hébergeur qu'on ne paie plus.</p>
 */
@Entity
@Table(name = "media")
public class Media {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produit_id")
    private Produit produit;

    /** {@code null} = média du produit ; renseigné = média propre à une déclinaison. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variante_id")
    private Variante variante;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TypeMedia type;

    @Column(name = "cle_objet", nullable = false, length = 500)
    private String cleObjet;

    @Column(name = "cle_miniature", length = 500)
    private String cleMiniature;

    @Column(nullable = false)
    private int ordre = 0;

    /** Une seule à {@code true} par produit — index unique partiel (I-13). */
    @Column(nullable = false)
    private boolean principal = false;

    @Column(name = "date_creation", nullable = false, updatable = false)
    private Instant dateCreation = Instant.now();

    protected Media() {
    }

    Media(Produit produit, TypeMedia type, String cleObjet, boolean principal) {
        this.produit = produit;
        this.type = type;
        this.cleObjet = cleObjet;
        this.principal = principal;
    }

    public Long getId() { return id; }
    public Produit getProduit() { return produit; }
    public Variante getVariante() { return variante; }
    public TypeMedia getType() { return type; }
    public String getCleObjet() { return cleObjet; }
    public String getCleMiniature() { return cleMiniature; }
    public int getOrdre() { return ordre; }
    public boolean estPrincipal() { return principal; }

    public void setVariante(Variante variante) { this.variante = variante; }
    public void setCleMiniature(String cle) { this.cleMiniature = cle; }
    public void setOrdre(int ordre) { this.ordre = ordre; }
    void definirPrincipal(boolean principal) { this.principal = principal; }
}

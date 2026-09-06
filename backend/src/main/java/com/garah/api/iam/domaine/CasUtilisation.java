package com.garah.api.iam.domaine;

import jakarta.persistence.*;

/**
 * Une fonctionnalité réellement implémentée dans le SI.
 *
 * <p>Ces 188 lignes sont un <b>référentiel</b>, pas des données : elles sont
 * posées par la migration {@code V14} et le code Java s'appuie dessus.</p>
 *
 * <p>Le {@code code} est <b>immuable</b>. Il sert à trois choses :</p>
 * <ol>
 *   <li>l'autorisation dans le code Java ;</li>
 *   <li>la clé de traduction dans les fichiers i18n ({@code perm.<code>}) ;</li>
 *   <li>la référence des exceptions individuelles.</li>
 * </ol>
 * <p>Le renommer casserait les trois d'un coup. Le {@code nom} et la
 * {@code description}, eux, sont libres.</p>
 */
@Entity
@Table(name = "cas_utilisation")
public class CasUtilisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 150)
    private String nom;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 50)
    private String module;

    @Column(nullable = false, length = 20)
    private String statut = "ACTIF";

    protected CasUtilisation() {
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getNom() { return nom; }
    public String getDescription() { return description; }
    public String getModule() { return module; }
    public String getStatut() { return statut; }

    /** Le libellé et la description sont modifiables par le SuperAdmin ; le code, jamais. */
    public void renommer(String nom, String description) {
        this.nom = nom;
        this.description = description;
    }

    public void setStatut(String statut) { this.statut = statut; }

    @Override
    public boolean equals(Object autre) {
        return autre instanceof CasUtilisation c && id != null && id.equals(c.id);
    }

    @Override
    public int hashCode() {
        return CasUtilisation.class.hashCode();
    }
}

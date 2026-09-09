package com.garah.api.surveillance.domaine;

import com.garah.api.commun.audit.GesteClient.TypeGesteClient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Ce que fait un CLIENT, sur son parcours.
 *
 * <h2>🎯 La table existait depuis V12, personne ne l'écrivait</h2>
 *
 * <p>Déclarée avec ses huit types et ses deux index, elle attendait <b>trois
 * versions majeures</b> sans entité ni écriture. Une table vide ne se plaint
 * pas : elle se lit comme « aucun client n'a rien fait », ce qui est
 * indiscernable de « personne n'écrit ici ».</p>
 *
 * <h2>Trois journaux, et celui-ci n'est pas les deux autres</h2>
 *
 * <pre>
 * activite_client      ce que fait LE CLIENT          → comprendre son parcours
 * evenement_securite   les faits d'AUTHENTIFICATION   → détecter une anomalie
 * audit_log            les actions INTERNES           → responsabilité interne
 * </pre>
 *
 * <p>⚠️ Les fondre rendrait les trois inexploitables : les quelques gestes
 * internes d'une journée disparaîtraient sous le trafic de la boutique. C'est
 * écrit en tête de V12, et c'est la raison d'être de cette table.</p>
 */
@Entity
@Table(name = "activite_client")
public class ActiviteClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ⚠️ C'est l'identifiant de la ligne {@code client}, pas de la ligne
     * {@code utilisateur} — les deux partagent la même valeur, mais la clé
     * étrangère porte sur {@code client}. Un compte interne n'en a pas : ses
     * gestes n'ont donc rien à faire ici, et l'écouteur les écarte avant
     * d'arriver jusqu'à cette classe.
     */
    @Column(name = "client_id", nullable = false, updatable = false)
    private Long clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private TypeGesteClient type;

    @Column(name = "appareil_id")
    private Long appareilId;

    /**
     * ⚠️ La colonne est de type {@code inet} en base. Hibernate l'écrit
     * volontiers depuis une chaîne, mais une chaîne <b>vide</b> la fait
     * échouer : PostgreSQL n'accepte pas « » comme adresse. On garde donc
     * {@code null} quand on ne sait pas.
     */
    @Column(name = "adresse_ip", columnDefinition = "inet")
    private String adresseIp;

    /**
     * Ce qui rend la ligne lisible six mois plus tard.
     *
     * <p>Le numéro d'une commande, le nom d'un article. ⚠️ Jamais de donnée
     * sensible : ce journal se relit depuis le back-office, et ce qu'on y
     * écrit y reste.</p>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> contexte = Map.of();

    @Column(name = "date_heure", nullable = false, updatable = false)
    private Instant dateHeure = Instant.now();

    protected ActiviteClient() {
    }

    public ActiviteClient(Long clientId, TypeGesteClient type,
                          Map<String, String> contexte, String adresseIp) {
        this.clientId = clientId;
        this.type = type;
        this.contexte = contexte == null ? Map.of() : contexte;
        this.adresseIp = adresseIp == null || adresseIp.isBlank() ? null : adresseIp;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public TypeGesteClient getType() {
        return type;
    }

    public Map<String, String> getContexte() {
        return contexte;
    }

    public String getAdresseIp() {
        return adresseIp;
    }

    public Instant getDateHeure() {
        return dateHeure;
    }
}

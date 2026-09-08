package com.garah.api.messagerie.domaine;

import jakarta.persistence.*;

import java.time.Instant;

/** Un message dans un fil interne. */
@Entity
@Table(name = "message_interne")
public class MessageInterne {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fil_id", nullable = false)
    private Long filId;

    @Column(name = "expediteur_id", nullable = false)
    private Long expediteurId;

    @Column(nullable = false, columnDefinition = "text")
    private String contenu;

    /**
     * ⚠️ La DATE de lecture, et non un booléen « lu ».
     *
     * <p>« Quand ? » se pose toujours après « est-ce lu ? », et un booléen ne
     * sait pas y répondre. La question arrive exactement le jour où quelqu'un
     * affirme n'avoir jamais reçu la consigne.</p>
     */
    @Column(name = "date_lecture")
    private Instant dateLecture;

    @Column(name = "date_envoi", nullable = false)
    private Instant dateEnvoi = Instant.now();

    protected MessageInterne() {
    }

    public MessageInterne(Long filId, Long expediteurId, String contenu) {
        this.filId = filId;
        this.expediteurId = expediteurId;
        this.contenu = contenu;
    }

    /** Idempotent : relire un message ne rajeunit pas sa première lecture. */
    public void lu() {
        if (dateLecture == null) {
            dateLecture = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getFilId() { return filId; }
    public Long getExpediteurId() { return expediteurId; }
    public String getContenu() { return contenu; }
    public Instant getDateLecture() { return dateLecture; }
    public Instant getDateEnvoi() { return dateEnvoi; }
}

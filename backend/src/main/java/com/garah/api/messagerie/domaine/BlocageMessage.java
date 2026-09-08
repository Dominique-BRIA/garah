package com.garah.api.messagerie.domaine;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Un blocage : {@code bloqueur} ne veut plus recevoir de messages de
 * {@code bloque}.
 *
 * <h2>⚠️ UNILATÉRAL ET ORIENTÉ, et c'est tout le sujet</h2>
 *
 * <p>Le bloqueur peut toujours écrire au bloqué. C'est délibéré : le contraire
 * ferait du blocage une rupture de la chaîne de commandement, alors que ce
 * qu'on veut est seulement se protéger d'une sollicitation.</p>
 */
@Entity
@Table(name = "blocage_message")
@IdClass(BlocageMessage.Cle.class)
public class BlocageMessage {

    @Id
    @Column(name = "bloqueur_id")
    private Long bloqueurId;

    @Id
    @Column(name = "bloque_id")
    private Long bloqueId;

    @Column(length = 300)
    private String motif;

    @Column(name = "date_blocage", nullable = false)
    private Instant dateBlocage = Instant.now();

    protected BlocageMessage() {
    }

    public BlocageMessage(Long bloqueurId, Long bloqueId, String motif) {
        this.bloqueurId = bloqueurId;
        this.bloqueId = bloqueId;
        this.motif = motif;
    }

    public Long getBloqueurId() { return bloqueurId; }
    public Long getBloqueId() { return bloqueId; }
    public String getMotif() { return motif; }
    public Instant getDateBlocage() { return dateBlocage; }

    public static class Cle implements Serializable {
        private Long bloqueurId;
        private Long bloqueId;

        public Cle() {
        }

        public Cle(Long bloqueurId, Long bloqueId) {
            this.bloqueurId = bloqueurId;
            this.bloqueId = bloqueId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Cle autre)) return false;
            return Objects.equals(bloqueurId, autre.bloqueurId)
                    && Objects.equals(bloqueId, autre.bloqueId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bloqueurId, bloqueId);
        }
    }
}

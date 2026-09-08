package com.garah.api.messagerie.infra;

import com.garah.api.messagerie.domaine.BlocageMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BlocageMessageRepository
        extends JpaRepository<BlocageMessage, BlocageMessage.Cle> {

    /** « Celui a qui j ecris m a-t-il bloque ? » — la question de chaque envoi. */
    boolean existsByBloqueurIdAndBloqueId(Long bloqueurId, Long bloqueId);

    /** Ceux que J AI bloques : c est la liste qu on affiche dans ses reglages. */
    List<BlocageMessage> findByBloqueurId(Long bloqueurId);
}

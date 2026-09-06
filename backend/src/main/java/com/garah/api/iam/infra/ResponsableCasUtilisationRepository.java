package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.ResponsableCasUtilisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResponsableCasUtilisationRepository
        extends JpaRepository<ResponsableCasUtilisation, ResponsableCasUtilisation.Cle> {

    List<ResponsableCasUtilisation> findByResponsableId(Long responsableId);
}

package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByCodeClient(String codeClient);
}

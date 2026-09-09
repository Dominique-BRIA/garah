package com.garah.api.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La lecture du journal, PAR HTTP.
 *
 * <p>Un test qui appellerait ServiceAudit directement ne traverserait ni la
 * garde de permission, ni la conversion des parametres, ni la serialisation de
 * la page — les trois endroits ou l ecran a echoue.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Journal : la lecture par HTTP")
class LectureJournalTest {

    @Autowired MockMvc mvc;

    private static RequestPostProcessor avec(String... droits) {
        SimpleGrantedAuthority[] autorites = new SimpleGrantedAuthority[droits.length];
        for (int i = 0; i < droits.length; i++) {
            autorites[i] = new SimpleGrantedAuthority(droits[i]);
        }
        return jwt().jwt(j -> j.subject("1")).authorities(autorites);
    }

    @Test
    @DisplayName("le journal se lit sans aucun filtre")
    void sansFiltre() throws Exception {
        mvc.perform(get("/api/surveillance/audit").with(avec("AUDIT_CONSULTER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("le journal se lit avec les parametres que l ecran envoie")
    void avecLesParametresDeLEcran() throws Exception {
        mvc.perform(get("/api/surveillance/audit")
                        .param("page", "0")
                        .param("taille", "50")
                        .with(avec("AUDIT_CONSULTER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("les listes de filtres se lisent")
    void lesFiltres() throws Exception {
        mvc.perform(get("/api/surveillance/audit/filtres").with(avec("AUDIT_CONSULTER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sans le droit, c est refuse")
    void sansLeDroit() throws Exception {
        mvc.perform(get("/api/surveillance/audit").with(avec("RESPONSABLE_CONSULTER")))
                .andExpect(status().isForbidden());
    }
}

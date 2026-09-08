package com.garah.api.iam;

import com.garah.api.commun.erreur.RegleMetierViolee;
import com.garah.api.iam.domaine.*;
import com.garah.api.iam.infra.CategorieResponsableRepository;
import com.garah.api.iam.infra.ResponsableRepository;
import com.garah.api.iam.infra.UtilisateurRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La hiérarchie : Super Admin → Admin → Chef de service → Membre.
 *
 * <p>C'est du <b>contrôle d'accès</b> : une erreur n'y produit aucun symptôme
 * visible — elle laisse simplement passer un geste qui aurait dû être refusé.
 * Ces cas-là ne se vérifient pas à l'œil sur un écran.</p>
 *
 * <p>Contre une vraie base PostgreSQL : l'unicité du chef par service est un
 * index unique partiel, qui n'existe pas en H2.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Hiérarchie et chef de service")
class ServiceHierarchieTest {

    @Autowired ServiceHierarchie hierarchie;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ResponsableRepository responsables;
    @Autowired CategorieResponsableRepository categories;
    @Autowired EntityManager em;

    private CategorieResponsable logistique;
    private CategorieResponsable commercial;

    private Responsable awa;      // chef de la logistique
    private Responsable bello;    // membre de la logistique
    private Responsable ndzana;   // membre du commercial
    private Utilisateur patron;   // ADMIN

    @BeforeEach
    void preparerDeuxServices() {
        logistique = categories.save(new CategorieResponsable("Test Logistique H"));
        commercial = categories.save(new CategorieResponsable("Test Commercial H"));

        awa = responsable("Awa", "awa.h@garah.cm", "RESP-H-001", logistique);
        bello = responsable("Bello", "bello.h@garah.cm", "RESP-H-002", logistique);
        ndzana = responsable("Ndzana", "ndzana.h@garah.cm", "RESP-H-003", commercial);

        patron = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.ADMIN, "Patron", "patron.h@garah.cm", "empreinte"));

        em.flush();

        hierarchie.nommerChef(logistique.getId(), awa.getId());
        em.flush();
        em.clear();
    }

    private Responsable responsable(String nom, String email, String matricule,
                                    CategorieResponsable categorie) {
        Utilisateur u = utilisateurs.save(new Utilisateur(
                TypeUtilisateur.RESPONSABLE, nom, email, "empreinte"));
        Responsable r = responsables.save(new Responsable(u, matricule));
        r.ajouterCategorie(categorie, true);
        return responsables.save(r);
    }

    // -------------------------------------------------------------------------
    // Le rang
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("le rang se déduit : diriger un service fait le chef")
    void leRangSeDeduit() {
        // Rien n'est stocké : c'est le drapeau `chef` d'une affectation qui
        // fait passer de MEMBRE à CHEF_DE_SERVICE. Une colonne `rang` aurait
        // été une troisième source de vérité, à tenir d'accord avec les deux
        // autres.
        assertThat(hierarchie.rangDe(awa.getId())).isEqualTo(RangHierarchique.CHEF_DE_SERVICE);
        assertThat(hierarchie.rangDe(bello.getId())).isEqualTo(RangHierarchique.MEMBRE);
        assertThat(hierarchie.rangDe(patron.getId())).isEqualTo(RangHierarchique.ADMIN);
    }

    // -------------------------------------------------------------------------
    // L'autorité
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un chef commande les membres de SON service")
    void leChefCommandeSonService() {
        hierarchie.exigerAutoriteSur(awa.getId(), bello.getId());
    }

    @Test
    @DisplayName("⚠️ un chef ne touche PAS le service d'à côté")
    void leChefNeTouchePasLAutreService() {
        // Le rang suffirait — chef (3) contre membre (4) — et c'est
        // exactement pourquoi la PORTÉE est un second contrôle : sans elle, le
        // chef de la logistique désactiverait un commercial.
        assertThatThrownBy(() -> hierarchie.exigerAutoriteSur(awa.getId(), ndzana.getId()))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("service");
    }

    @Test
    @DisplayName("⚠️ un chef ne touche pas un Admin, même affecté à son service")
    void leChefNeToucheePasUnAdmin() {
        // La portée seule laisserait passer : il suffirait qu'un Admin soit
        // affecté au service. C'est le RANG qui l'arrête.
        assertThatThrownBy(() -> hierarchie.exigerAutoriteSur(awa.getId(), patron.getId()))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("supérieur");
    }

    @Test
    @DisplayName("⚠️ deux pairs ne se désactivent pas l'un l'autre")
    void deuxPairsNeSeTouchentPas() {
        // Strictement inférieur, et pas « inférieur ou égal » : le premier à
        // cliquer gagnerait, et le second se retrouverait dehors sans recours.
        assertThatThrownBy(() -> hierarchie.exigerAutoriteSur(bello.getId(), ndzana.getId()))
                .isInstanceOf(RegleMetierViolee.class);
    }

    @Test
    @DisplayName("⚠️ personne n'agit sur son propre compte")
    void personneNAgitSurSoi() {
        // Le seul geste qu'aucune hiérarchie ne rattrape : plus personne pour
        // rendre la main.
        assertThatThrownBy(() -> hierarchie.exigerAutoriteSur(awa.getId(), awa.getId()))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("propre compte");
    }

    // -------------------------------------------------------------------------
    // Nommer
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ nommer un second chef démet le premier")
    void nommerUnSecondChefDemetLePremier() {
        // Un index unique interdit deux chefs par service. Sans la démission
        // dans la même transaction, l'écriture échouerait sur une contrainte
        // de base, avec un message que personne ne peut lire.
        hierarchie.nommerChef(logistique.getId(), bello.getId());
        em.flush();
        em.clear();

        assertThat(hierarchie.rangDe(bello.getId())).isEqualTo(RangHierarchique.CHEF_DE_SERVICE);
        assertThat(hierarchie.rangDe(awa.getId())).isEqualTo(RangHierarchique.MEMBRE);
    }

    @Test
    @DisplayName("on ne dirige que le service dont on est membre")
    void onNeDirigeQueSonService() {
        assertThatThrownBy(() -> hierarchie.nommerChef(commercial.getId(), bello.getId()))
                .isInstanceOf(RegleMetierViolee.class)
                .hasMessageContaining("membre");
    }

    @Test
    @DisplayName("démettre est idempotent : un service sans chef est normal")
    void demettreEstIdempotent() {
        hierarchie.demettreLeChef(logistique.getId());
        hierarchie.demettreLeChef(logistique.getId());
        em.flush();
        em.clear();

        assertThat(hierarchie.rangDe(awa.getId())).isEqualTo(RangHierarchique.MEMBRE);
        assertThat(hierarchie.servicesDiriges(awa.getId())).isEmpty();
    }
}

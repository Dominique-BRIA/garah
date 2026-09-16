package com.garah.api.iam.infra;

import com.garah.api.iam.domaine.FournisseurIdentite;
import com.garah.api.iam.domaine.IdentiteVerifiee;
import com.garah.api.iam.domaine.VerificateurIdentiteSociale;
import com.garah.api.iam.domaine.VerificateurParFournisseur;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aiguille vers le vérificateur du bon fournisseur.
 *
 * <p>Une <b>seule</b> implémentation de {@link VerificateurIdentiteSociale}
 * dans tout le contexte, et c'est délibéré : les tests remplacent l'interface
 * par une doublure, ce qui deviendrait ambigu s'il y avait plusieurs candidats.
 * Les vérificateurs par fournisseur, eux, sont des collaborateurs — pas des
 * alternatives.</p>
 *
 * <p>📌 Ajouter un fournisseur ne touche pas cette classe : il suffit d'un
 * nouveau {@link VerificateurParFournisseur}, que Spring injecte dans la liste.
 * C'est ce qui évite la cascade de {@code if} que ce genre de fonctionnalité
 * finit toujours par accumuler.</p>
 */
@Component
public class VerificateurSocial implements VerificateurIdentiteSociale {

    private final Map<FournisseurIdentite, VerificateurParFournisseur> parFournisseur =
            new EnumMap<>(FournisseurIdentite.class);

    public VerificateurSocial(List<VerificateurParFournisseur> verificateurs) {
        verificateurs.forEach(v -> parFournisseur.put(v.fournisseur(), v));
    }

    @Override
    public IdentiteVerifiee verifier(FournisseurIdentite fournisseur, String preuve) {
        VerificateurParFournisseur verificateur = parFournisseur.get(fournisseur);

        // ⚠️ Non configuré est traité comme invalide, et le message ne change
        //    pas. Répondre « ce fournisseur n'est pas activé » apprendrait à
        //    un inconnu quelle partie de notre configuration est incomplète.
        if (verificateur == null || !verificateur.estActif()
                || preuve == null || preuve.isBlank()) {
            throw new JetonSocialInvalide();
        }

        return verificateur.verifier(preuve);
    }

    /** Les fournisseurs réellement utilisables — pour que l'écran n'en propose pas d'autres. */
    public List<String> actifs() {
        return parFournisseur.values().stream()
                .filter(VerificateurParFournisseur::estActif)
                .map(v -> v.fournisseur().name())
                .sorted()
                .toList();
    }
}

package com.garah.api.iam.securite;

import com.garah.api.iam.domaine.Utilisateur;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Fabrique les jetons d'accès.
 *
 * <p><b>Ce qu'on met dans un jeton, et ce qu'on n'y met jamais.</b></p>
 *
 * <p>Un JWT est <b>signé</b>, donc infalsifiable — mais il n'est <b>pas
 * chiffré</b> : n'importe qui peut lire son contenu en le collant dans un
 * décodeur en ligne. Il ne doit donc contenir que des informations qu'on
 * accepterait d'afficher publiquement.</p>
 *
 * <pre>
 * ✅ identifiant, type d'acteur, nom affiché, permissions, expiration
 * ❌ mot de passe (même haché), e-mail, téléphone, données personnelles
 * </pre>
 */
@Service
public class ServiceJeton {

    private final JwtEncoder encodeur;
    private final Duration duree;

    public ServiceJeton(JwtEncoder encodeur,
                        @Value("${GARAH_JWT_EXPIRATION_MINUTES:60}") long minutes) {
        this.encodeur = encodeur;
        this.duree = Duration.ofMinutes(minutes);
    }

    public String creer(Utilisateur utilisateur, Set<String> permissions) {
        Instant maintenant = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("garah")
                .issuedAt(maintenant)
                .expiresAt(maintenant.plus(duree))
                .subject(String.valueOf(utilisateur.getId()))
                .claim("type", utilisateur.getType().name())
                .claim("nom", utilisateur.getNom())
                .claim("langue", utilisateur.getLangue())
                // Les permissions voyagent DANS le jeton : aucune requête en
                // base n'est nécessaire pour autoriser un appel (D-16).
                .claim("permissions", permissions.stream().toList())
                .build();

        // ⚠️ L'en-tête DOIT déclarer HS256.
        //
        // Sans lui, Nimbus suppose RS256 (signature asymétrique), cherche une
        // clé RSA, n'en trouve pas, et échoue avec un message peu parlant :
        // « Failed to select a JWK signing key ». Notre clé est un secret
        // partagé, donc symétrique : il faut le dire explicitement.
        JwsHeader entete = JwsHeader.with(MacAlgorithm.HS256).build();

        return encodeur.encode(JwtEncoderParameters.from(entete, claims)).getTokenValue();
    }

    public long dureeEnSecondes() {
        return duree.toSeconds();
    }
}

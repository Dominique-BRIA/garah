-- =============================================================================
-- V22 — Verification de l adresse e-mail (D-23)
-- =============================================================================
-- L inscription creait un compte sans AUCUNE verification d identite :
-- n importe qui pouvait s inscrire avec l adresse de quelqu un d autre.
--
-- Pour GARAH ce n est pas un detail de securite, c est un defaut METIER. Le
-- suivi de commande, le code de retrait et les notifications de livraison
-- partent tous a cette adresse (D-07). Une adresse fausse, et la marchandise
-- arrive a Bangui sans que personne ne puisse etre prevenu.
-- =============================================================================

-- --- Le drapeau sur le compte ------------------------------------------------
--
-- DEFAULT false : tout nouveau compte est non verifie.
ALTER TABLE utilisateur
    ADD COLUMN email_verifie boolean NOT NULL DEFAULT false;

-- ⚠️ Les comptes EXISTANTS sont marques verifies.
--
-- Sans cette ligne, le SuperAdmin amorce au demarrage — et tout responsable
-- deja cree — basculerait d un coup en « non verifie ». Une migration ne doit
-- jamais degrader l etat de donnees qui etaient valides avant elle.
--
-- La regle qui en decoule : une colonne NOT NULL ajoutee a une table peuplee
-- demande TOUJOURS de decider ce que valent les lignes du passe. Le DEFAULT
-- repond pour les futures, jamais pour les anciennes.
UPDATE utilisateur SET email_verifie = true;

COMMENT ON COLUMN utilisateur.email_verifie IS
    'L adresse a-t-elle ete confirmee par un lien ? Seuls les CLIENT s inscrivent eux-memes ; les comptes crees par un administrateur naissent verifies. Voir D-23.';

-- --- Les jetons de verification ----------------------------------------------
--
-- Meme raisonnement qu en V21 pour le rafraichissement : on stocke une
-- EMPREINTE SHA-256, jamais le jeton. Qui lit cette table pourrait sinon
-- verifier l adresse de n importe qui.
--
-- SHA-256 et non BCrypt : le jeton est 256 bits tires au sort, il n existe
-- aucun dictionnaire contre lui. BCrypt pour ce qu un humain choisit, hachage
-- rapide pour ce que la machine tire au sort.
CREATE TABLE jeton_verification_email (
    id                bigserial   PRIMARY KEY,

    utilisateur_id    bigint      NOT NULL REFERENCES utilisateur (id) ON DELETE CASCADE,

    empreinte         char(64)    NOT NULL UNIQUE,

    -- L adresse VISEE au moment de l emission.
    --
    -- Elle est figee ici, et pas relue sur l utilisateur au moment de la
    -- verification. Sinon : je m inscris avec mon adresse, je recois le lien,
    -- je change mon e-mail pour celui d un autre, puis je clique — et je
    -- viens de « verifier » une adresse que je ne controle pas.
    adresse_visee     varchar(255) NOT NULL,

    date_creation     timestamptz NOT NULL DEFAULT now(),
    date_expiration   timestamptz NOT NULL,

    -- Un jeton ne sert QU UNE FOIS.
    date_utilisation  timestamptz,

    CONSTRAINT jeton_verification_expiration_posterieure
        CHECK (date_expiration > date_creation)
);

-- La lecture faite a chaque clic sur le lien.
-- (L unicite sur empreinte cree deja l index.)

-- « Cet utilisateur a-t-il un jeton encore valide ? » — evite d en emettre un
-- nouveau a chaque demande de renvoi.
CREATE INDEX jeton_verification_utilisateur_idx
    ON jeton_verification_email (utilisateur_id)
    WHERE date_utilisation IS NULL;

-- La purge. Cette table grossit d une ligne par inscription et par renvoi ;
-- sans purge elle ne redescend jamais (la lecon de vue_produit, D-15).
CREATE INDEX jeton_verification_purge_idx
    ON jeton_verification_email (date_expiration);

COMMENT ON TABLE jeton_verification_email IS
    'Jetons de confirmation d adresse, stockes en SHA-256. Usage unique. Voir D-23.';

COMMENT ON COLUMN jeton_verification_email.adresse_visee IS
    'L adresse au moment de l emission. Figee : sans cela, changer d e-mail entre la reception et le clic ferait verifier l adresse d autrui.';

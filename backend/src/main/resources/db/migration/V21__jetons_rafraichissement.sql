-- =============================================================================
-- V21 — Jetons de rafraichissement (D-19, remplace une partie de D-16)
-- =============================================================================
-- Jusqu ici, un seul jeton de 60 minutes, sans etat cote serveur. Consequence
-- assumee par D-16 : un droit retire, ou meme un compte BLOQUE, restait
-- utilisable jusqu a l expiration.
--
-- On coupe la duree de vie de l acces a 15 minutes, et on ajoute un jeton de
-- rafraichissement a longue duree. Celui-ci, contrairement au JWT, est STOCKE :
-- c est precisement ce qui permet de le revoquer.
--
--   acces          15 min   JWT signe, aucun etat serveur, jamais revocable
--   rafraichissement 14 j   ligne en base, revocable a tout instant
--
-- =============================================================================
-- Pourquoi on stocke une EMPREINTE et pas le jeton
-- =============================================================================
-- Cette table est une table de mots de passe deguisee : qui lit son contenu
-- peut se faire passer pour n importe quel utilisateur pendant deux semaines.
-- Une fuite de sauvegarde, un dump envoye au support, un SELECT de trop.
--
-- On stocke donc SHA-256 du jeton. A la presentation, on rehache et on compare.
--
-- ⚠️ SHA-256 et NON BCrypt — c est contre-intuitif apres le chapitre 08.
--
--    BCrypt est lent EXPRES, parce qu un mot de passe humain a peu d entropie
--    et doit resister a une attaque par dictionnaire. Un jeton de
--    rafraichissement est 256 bits tires au hasard : il n existe aucun
--    dictionnaire, et le forcer est deja impossible. Le ralentir n apporte
--    rien et couterait 250 ms a CHAQUE rafraichissement, toutes les 15 minutes,
--    pour chaque utilisateur connecte.
--
--    La regle : BCrypt pour ce qu un humain a choisi, hachage rapide pour ce
--    que la machine a tire au sort.
-- =============================================================================

CREATE TABLE jeton_rafraichissement (
    id                bigserial   PRIMARY KEY,

    utilisateur_id    bigint      NOT NULL REFERENCES utilisateur (id) ON DELETE CASCADE,

    -- SHA-256 en hexadecimal : 64 caracteres, toujours.
    empreinte         char(64)    NOT NULL UNIQUE,

    -- La FAMILLE relie tous les jetons issus d une meme connexion.
    --
    -- A chaque rafraichissement le jeton est REMPLACE (rotation) et reste dans
    -- la meme famille. Si un jeton deja consomme est represente, c est qu il a
    -- ete vole : on revoque alors la famille ENTIERE, ce qui deconnecte aussi
    -- le voleur. Sans famille, on ne pourrait revoquer que le jeton presente
    -- — c est-a-dire punir la victime et laisser courir le voleur.
    famille           uuid        NOT NULL,

    date_creation     timestamptz NOT NULL DEFAULT now(),
    date_expiration   timestamptz NOT NULL,

    -- Revoque : par rotation normale, par deconnexion, ou par detection de vol.
    date_revocation   timestamptz,
    motif_revocation  varchar(30),

    -- Pour l audit : d ou venait la session. Jamais pour autoriser.
    adresse_ip        varchar(45),

    CONSTRAINT jeton_rafraichissement_motif_valide
        CHECK (motif_revocation IS NULL OR motif_revocation IN
               ('ROTATION', 'DECONNEXION', 'REUTILISATION', 'COMPTE_FERME')),

    -- Les deux colonnes vont ensemble ou pas du tout. Une revocation sans
    -- motif serait indistinguable d un bug, et un motif sans date rendrait
    -- le jeton encore valide tout en pretendant le contraire.
    CONSTRAINT jeton_rafraichissement_revocation_coherente
        CHECK ((date_revocation IS NULL) = (motif_revocation IS NULL)),

    CONSTRAINT jeton_rafraichissement_expiration_posterieure
        CHECK (date_expiration > date_creation)
);

-- La lecture faite a CHAQUE rafraichissement : elle doit etre instantanee.
-- (L unicite sur empreinte cree deja l index ; on n en ajoute pas un second.)

-- Revoquer une famille entiere en une requete, le jour d un vol detecte.
CREATE INDEX jeton_rafraichissement_famille_idx
    ON jeton_rafraichissement (famille);

-- « Deconnecter partout » et « fermer un compte » passent par la.
CREATE INDEX jeton_rafraichissement_utilisateur_idx
    ON jeton_rafraichissement (utilisateur_id)
    WHERE date_revocation IS NULL;

-- La purge. Index PARTIEL : seuls les jetons encore vivants nous interessent,
-- et la table ne cesse de grossir de jetons morts entre deux purges.
CREATE INDEX jeton_rafraichissement_purge_idx
    ON jeton_rafraichissement (date_expiration);

COMMENT ON TABLE jeton_rafraichissement IS
    'Jetons de rafraichissement, stockes en SHA-256. Revocables, contrairement au JWT d acces. Voir D-19.';

COMMENT ON COLUMN jeton_rafraichissement.famille IS
    'Relie les jetons issus d une meme connexion. Une reutilisation revoque toute la famille.';

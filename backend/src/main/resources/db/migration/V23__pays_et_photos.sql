-- =============================================================================
-- V23 — Pays du marchand, photos, et code marchand engendre
-- =============================================================================

-- --- Le code marchand n est plus saisi -------------------------------------
--
-- Il etait tape a la main, et cela produisait « 202020 » : une valeur qui ne
-- dit rien, impossible a dicter sans se tromper, et qu il fallait inventer a
-- chaque creation.
--
-- Meme raison qu en V17 et V20 : une sequence est atomique et ne bloque
-- personne. `count(*) + 1` donnerait le meme code a deux creations
-- simultanees, et la contrainte UNIQUE ferait echouer la seconde.
CREATE SEQUENCE marchand_code_seq START 1;

COMMENT ON SEQUENCE marchand_code_seq IS
    'Alimente marchand.code au format MAR-<5 chiffres>. Peut sauter des numeros : voir V17.';

-- --- Le pays -----------------------------------------------------------------
--
-- GARAH achemine de Douala vers Bangui : le pays d un marchand n est pas une
-- donnee decorative, c est ce qui determine si sa marchandise franchit une
-- frontiere.
--
-- Code ISO 3166-1 alpha-2 plutot qu un nom en clair. Un nom se saisit de dix
-- facons — « Cameroun », « Cameroon », « CMR », « cameroun » — et rend tout
-- regroupement faux. Le code est stable, court, et se traduit a l affichage.
ALTER TABLE marchand
    ADD COLUMN pays char(2) NOT NULL DEFAULT 'CM';

-- /!\ Deux lettres MAJUSCULES, verifie par la base.
--
--     Sans cette contrainte, « cm » et « CM » coexisteraient et compteraient
--     pour deux pays differents dans la moindre statistique.
ALTER TABLE marchand
    ADD CONSTRAINT marchand_pays_valide CHECK (pays ~ '^[A-Z]{2}$');

COMMENT ON COLUMN marchand.pays IS
    'Code ISO 3166-1 alpha-2. Determine si la marchandise franchit une frontiere.';

-- --- Les photos --------------------------------------------------------------
--
-- On stocke une CLE D OBJET, jamais une URL (D-14, D-21). Le bucket est prive :
-- les URL sont signees et expirent au bout de sept jours. Une URL stockee
-- serait morte avant d avoir servi.
ALTER TABLE marchand
    ADD COLUMN logo_cle varchar(500);

ALTER TABLE utilisateur
    ADD COLUMN photo_cle varchar(500);

COMMENT ON COLUMN marchand.logo_cle IS
    'Cle d objet du logo, jamais une URL : le bucket est prive et les URL expirent (D-21).';

COMMENT ON COLUMN utilisateur.photo_cle IS
    'Cle d objet de la photo de profil. NULL = on engendre un avatar a partir des initiales.';

-- =============================================================================
-- Pourquoi AUCUNE photo par defaut n est stockee
-- =============================================================================
-- La tentation serait d engendrer une image au moment de l inscription et de
-- la deposer sur le stockage.
--
-- Ce serait payer trois fois :
--   - un fichier par utilisateur, a stocker et a servir indefiniment ;
--   - un appel reseau supplementaire a chaque affichage ;
--   - une image figee, qui ne suivrait ni le theme ni un changement de nom.
--
-- L avatar par defaut est donc engendre A L AFFICHAGE, a partir des initiales.
-- Il ne coute rien, s adapte au theme, et disparait des qu une vraie photo est
-- deposee.
-- =============================================================================

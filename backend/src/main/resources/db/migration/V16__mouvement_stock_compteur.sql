-- =============================================================================
-- V16 — Le mouvement de stock precise QUEL compteur il fait bouger
-- =============================================================================
-- La lacune est apparue en implementant le service (chapitre 11).
--
-- La table `stock` porte TROIS compteurs :
--     quantite_disponible, quantite_reservee, quantite_endommagee
--
-- Or `mouvement_stock` n'avait que quantite_avant / quantite_apres, sans dire
-- de QUEL compteur il parlait. La contrainte arithmetique
--     quantite_apres = quantite_avant + quantite
-- etait donc invérifiable des qu'une operation touchait un autre compteur que
-- `disponible`.
--
-- Et surtout : une RESERVATION fait bouger DEUX compteurs a la fois
--     disponible -3   et   reservee +3
-- Une seule ligne ne peut pas decrire ca honnetement.
--
-- LA CORRECTION retenue : un mouvement decrit UN compteur. Une reservation
-- s'ecrit donc en DEUX lignes, dans la meme transaction. C'est exactement le
-- principe du grand livre `ecriture_marchand` (chapitre 03, domaine 9) :
--
--     on n'ecrit pas un etat, on ecrit des mouvements, et l'etat est leur somme.
--
-- Le stock devient un journal a double entree, et il devient reconciliable :
-- on peut recalculer chaque compteur depuis son historique et alerter en cas
-- d'ecart.
-- =============================================================================

ALTER TABLE mouvement_stock
    ADD COLUMN compteur varchar(20) NOT NULL DEFAULT 'DISPONIBLE';

ALTER TABLE mouvement_stock
    ADD CONSTRAINT mouvement_stock_compteur_valide
    CHECK (compteur IN ('DISPONIBLE', 'RESERVEE', 'ENDOMMAGEE'));

COMMENT ON COLUMN mouvement_stock.compteur IS
    'Le compteur de stock que cette ligne fait bouger. Une reservation produit DEUX lignes : DISPONIBLE puis RESERVEE.';
COMMENT ON COLUMN mouvement_stock.quantite_avant IS
    'Valeur du compteur nomme par la colonne compteur, AVANT le mouvement.';

-- La lecture la plus frequente : l'historique d'un compteur pour une variante.
CREATE INDEX mouvement_stock_compteur_idx
    ON mouvement_stock (stock_id, compteur, date_operation DESC);

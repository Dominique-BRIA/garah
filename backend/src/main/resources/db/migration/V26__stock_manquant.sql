-- =============================================================================
-- V26 — Repare les declinaisons sans ligne de stock
-- =============================================================================
-- L invariant I-15 dit « une variante a EXACTEMENT UN stock », et le tableau
-- des invariants precisait meme « creation automatique ». Elle n existait pas :
-- `ServiceStock.creerPour` n etait appele que depuis les tests. Toute
-- declinaison creee par l interface est donc restee sans stock.
--
-- ⚠️ Le defaut ne se voyait NULLE PART avant le pire moment. Une declinaison
--    sans stock traverse la creation du produit, la publication, l affichage
--    en vitrine, l ajout au panier — et echoue a la RESERVATION, c est-a-dire
--    quand un client valide sa commande. Le symptome apparait aussi loin que
--    possible de sa cause.
--
-- A partir de maintenant, l evenement `VarianteCreee` fait naitre le stock avec
-- la declinaison, dans la meme transaction. Cette migration rattrape celles qui
-- existent deja.
--
-- Les compteurs partent a zero : c est la verite. Personne n a jamais declare
-- de reception pour ces articles, et inventer une quantite ferait vendre de la
-- marchandise qui n existe pas.
-- =============================================================================

INSERT INTO stock (variante_id)
SELECT v.id
  FROM variante v
 WHERE NOT EXISTS (SELECT 1 FROM stock s WHERE s.variante_id = v.id);

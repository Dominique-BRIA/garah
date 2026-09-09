/**
 * Configuration de DEVELOPPEMENT — l'API tournant sur le poste.
 *
 * <h2>🎯 Pourquoi cette ligne a change</h2>
 *
 * Elle visait Azure, donc la base Neon, donc les VRAIES donnees. C'etait
 * assume et documente : cela exercait pour de bon le CORS, les cookies
 * inter-sites et les URL signees. Mais la contrepartie a fini par se voir —
 * deux lignes du journal d'audit de PRODUCTION portent des gestes faits
 * pendant un developpement, et une suppression depuis le back-office
 * supprimait pour de bon.
 *
 * Le commentaire qui vivait ici disait : « le jour ou l'application aura de
 * vrais clients, il faudra une base de recette et cette ligne devra changer ».
 * C'est ce jour.
 *
 * <h2>Comment lancer l'API en face</h2>
 *
 *     cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=recette
 *
 * Le profil est documente dans `application-recette.yml`, qui dit aussi les
 * trois variables a poser dans `.env`.
 *
 * <h2>⚠️ CE QUE CETTE LIGNE NE VERIFIE PLUS</h2>
 *
 * En local, le navigateur et l'API partagent `localhost` : ils sont sur le
 * MEME site. Trois mecanismes cessent donc d'etre exerces :
 *
 *   - le CORS inter-sites, qui n'a plus rien a arbitrer ;
 *   - le cookie `SameSite=None; Secure`, remplace par un `Lax` en clair ;
 *   - les URL de medias signees, si le stockage n'est pas configure.
 *
 * Ce sont exactement les trois qui, en production, echouent SILENCIEUSEMENT :
 * l'API repond correctement et le navigateur jette la reponse. Un passage sur
 * l'environnement deploye reste donc obligatoire avant de livrer.
 */
export const environnement = {
  production: false,
  urlApi: 'http://localhost:8080',
};

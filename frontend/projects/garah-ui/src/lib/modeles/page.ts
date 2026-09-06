/**
 * Une page renvoyee par l'API.
 *
 * ⚠️ LA STRUCTURE EST IMBRIQUEE, et c'est un piege.
 *
 * Spring Boot serialisait autrefois `Page` a plat — `totalElements` au meme
 * niveau que `content`. Depuis la version 3.3, les metadonnees sont regroupees
 * dans un objet `page` :
 *
 *     { "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }
 *
 * Le defaut ne casse RIEN visiblement : `page.totalElements` vaut simplement
 * `undefined`, et l'interface affiche « marchand(s) » sans nombre devant. Ni
 * erreur, ni avertissement — juste une information qui manque, qu'on met du
 * temps a remarquer parce que la liste, elle, s'affiche parfaitement.
 *
 * Seuls les champs reellement utilises sont declares : recopier les vingt
 * champs de Page obligerait a les maintenir sans jamais s'en servir.
 */
export interface Page<T> {
  readonly content: readonly T[];
  readonly page: MetadonneesPage;
}

export interface MetadonneesPage {
  readonly size: number;
  readonly number: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

/**
 * Une page renvoyée par Spring Data.
 *
 * <p>Seuls les champs réellement utilisés sont déclarés. Recopier les vingt
 * champs de `Page` obligerait à les maintenir sans jamais s'en servir.</p>
 */
export interface Page<T> {
  readonly content: readonly T[];
  readonly totalElements: number;
  readonly totalPages: number;
  readonly number: number;
  readonly size: number;
  readonly first: boolean;
  readonly last: boolean;
}

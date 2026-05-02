import { useMemo, useState } from 'react';

/**
 * Generic per-column filter + sort hook for table-shaped data.
 *
 * <p>Pulled out of the original MembersPage implementation so MapsPage, AuditPage, and any
 * future table can share the same idiom. The hook is intentionally string-keyed and
 * minimal — for richer features (column hide/show, virtualised rows, server-side paging)
 * graduate to @mui/x-data-grid; for the volume of rows an admin tool surfaces inline,
 * this hook is enough and avoids the bundle-size cost.
 *
 * <p>Filter behaviour: case-insensitive substring match per column, AND-combined across
 * columns. Empty filter = match-all on that column.
 *
 * <p>Sort behaviour: numeric columns sort numerically when both values are numbers;
 * everything else falls back to {@code String.localeCompare}. Click the same column to
 * toggle direction.
 */
export type SortDir = 'asc' | 'desc';

export type FilterableColumn<Row> = {
  /** Stable key used to address filter state and sort state. */
  key: string;
  /** Picks the value out of the row used for filter matching and sorting. */
  accessor: (r: Row) => string | number | boolean | null | undefined;
};

export function useFilterSort<Row>(rows: Row[], cols: FilterableColumn<Row>[], initialSortKey?: string) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [sort, setSort] = useState<{ key: string; dir: SortDir }>({
    key: initialSortKey ?? cols[0]?.key ?? '',
    dir: 'asc',
  });

  const colByKey = useMemo(() => {
    const m = new Map<string, FilterableColumn<Row>>();
    for (const c of cols) m.set(c.key, c);
    return m;
  }, [cols]);

  const visible = useMemo(() => {
    const filterEntries = Object.entries(filters).filter(([, v]) => v && v.trim());

    const matches = (r: Row) => filterEntries.every(([k, needle]) => {
      const c = colByKey.get(k);
      if (!c) return true;
      const haystack = String(c.accessor(r) ?? '').toLowerCase();
      return haystack.includes(needle.trim().toLowerCase());
    });

    const sortCol = colByKey.get(sort.key);
    const cmp = (a: Row, b: Row): number => {
      if (!sortCol) return 0;
      const av = sortCol.accessor(a);
      const bv = sortCol.accessor(b);
      if (typeof av === 'number' && typeof bv === 'number') return av - bv;
      return String(av ?? '').localeCompare(String(bv ?? ''));
    };

    const filtered = rows.filter(matches).slice().sort(cmp);
    return sort.dir === 'asc' ? filtered : filtered.reverse();
  }, [rows, filters, sort, colByKey]);

  const setFilter = (key: string, value: string) =>
    setFilters(f => ({ ...f, [key]: value }));

  const clearFilters = () => setFilters({});

  const toggleSort = (key: string) =>
    setSort(s => s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' });

  return { visible, filters, setFilter, clearFilters, sort, toggleSort };
}

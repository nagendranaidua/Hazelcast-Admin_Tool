import { IconButton, InputAdornment, TableCell, TableSortLabel, TextField } from '@mui/material';
import { SortDir } from './useFilterSort';

/**
 * Small shared building blocks for the filterable / sortable tables. Keeps the table-row
 * markup in each page focused on data rather than UI plumbing, and means consistency is
 * cheap: improve these two components and every table improves at once.
 */

/** Sort-aware column header. Click to toggle direction; arrow shows current direction. */
export function SortableHeader({
  label, col, sort, onClick, align,
}: {
  label: string; col: string; sort: { key: string; dir: SortDir };
  onClick: (c: string) => void; align?: 'right';
}) {
  const active = sort.key === col;
  return (
    <TableCell align={align} sortDirection={active ? sort.dir : false}
               sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
      <TableSortLabel active={active} direction={active ? sort.dir : 'asc'} onClick={() => onClick(col)}>
        {label}
      </TableSortLabel>
    </TableCell>
  );
}

/** Compact filter input that lives in the second header row above each column. */
export function FilterCell({
  value, onChange, placeholder = 'filter…',
}: {
  value: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <TableCell sx={{ pt: 0, pb: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
      <TextField
        size="small" fullWidth variant="standard"
        value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        InputProps={{
          disableUnderline: !value,
          endAdornment: value ? (
            <InputAdornment position="end">
              <IconButton size="small" onClick={() => onChange('')} sx={{ p: 0, fontSize: 14 }}>×</IconButton>
            </InputAdornment>
          ) : undefined,
        }}
      />
    </TableCell>
  );
}

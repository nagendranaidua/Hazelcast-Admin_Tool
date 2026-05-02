import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Box, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useFilterSort } from '../lib/useFilterSort';
import { FilterCell, SortableHeader } from '../lib/TableBits';

type MapSummary = {
  name:                 string;
  ownedEntries:         number | null;
  backupEntries:        number | null;
  ownedMemoryBytes:     number | null;
  backupMemoryBytes:    number | null;
  hits:                 number | null;
  lockedEntries:        number | null;
  dirtyEntries:         number | null;
  getOpsCumulative:     number | null;
  putOpsCumulative:     number | null;
  removeOpsCumulative:  number | null;
  sampleTs:             number | null;
  reachable:            boolean | null;
};

/**
 * IMaps overview for a cluster. One row per map with current entries / memory / hits /
 * locked / op counts, all sourced from the periodic MapMetricsBuffer sampler so this
 * page renders in a single round-trip rather than N+1 calls.
 *
 * <p>Every column has its own filter input and is sortable; click a row to drill into the
 * map browse page (where the per-map mini-charts live).
 */
export function MapsPage() {
  const { id } = useParams();
  const nav = useNavigate();

  const q = useQuery<MapSummary[]>({
    queryKey: ['cluster-maps-summary', id],
    queryFn: async () => (await api.get<MapSummary[]>(`/api/clusters/${id}/maps/summary`)).data,
    refetchInterval: 30_000,
  });

  const cols = [
    { key: 'name',             accessor: (r: MapSummary) => r.name },
    { key: 'ownedEntries',     accessor: (r: MapSummary) => r.ownedEntries     ?? -1 },
    { key: 'backupEntries',    accessor: (r: MapSummary) => r.backupEntries    ?? -1 },
    { key: 'ownedMemoryBytes', accessor: (r: MapSummary) => r.ownedMemoryBytes ?? -1 },
    { key: 'hits',             accessor: (r: MapSummary) => r.hits             ?? -1 },
    { key: 'lockedEntries',    accessor: (r: MapSummary) => r.lockedEntries    ?? -1 },
  ];
  const { visible, filters, setFilter, sort, toggleSort } = useFilterSort<MapSummary>(q.data ?? [], cols, 'name');

  return (
    <>
      <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 2 }}>
        <Typography variant="h4">Maps</Typography>
        <Box sx={{ flexGrow: 1 }} />
        <Chip size="small" label={`${visible.length} of ${q.data?.length ?? 0}`} />
        <Button size="small" variant="outlined" onClick={() => nav(`/clusters/${id}/sql`)}>SQL console</Button>
      </Stack>

      {q.isLoading && <LinearProgress sx={{ mb: 2 }} />}
      {q.error && <Alert severity="error" sx={{ mb: 2 }}>Failed to list maps. The cluster may be unreachable.</Alert>}

      {q.data && q.data.length === 0 && (
        <Alert severity="info" sx={{ mb: 2 }}>
          No IMaps yet on this cluster. Create one from any client (Java, Node, Python) and the
          map will appear here once it has at least one entry. Hazelcast lazily registers
          distributed objects on first use.
        </Alert>
      )}

      {q.data && q.data.length > 0 && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow sx={{ bgcolor: 'action.hover' }}>
                <SortableHeader label="Name"            col="name"             sort={sort} onClick={toggleSort} />
                <SortableHeader label="Entries"         col="ownedEntries"     sort={sort} onClick={toggleSort} align="right" />
                <SortableHeader label="Backups"         col="backupEntries"    sort={sort} onClick={toggleSort} align="right" />
                <SortableHeader label="Memory"          col="ownedMemoryBytes" sort={sort} onClick={toggleSort} align="right" />
                <SortableHeader label="Hits"            col="hits"             sort={sort} onClick={toggleSort} align="right" />
                <SortableHeader label="Locked"          col="lockedEntries"    sort={sort} onClick={toggleSort} align="right" />
                <TableCell />
              </TableRow>
              <TableRow>
                <FilterCell value={filters.name             ?? ''} onChange={v => setFilter('name', v)} />
                <FilterCell value={filters.ownedEntries     ?? ''} onChange={v => setFilter('ownedEntries', v)} />
                <FilterCell value={filters.backupEntries    ?? ''} onChange={v => setFilter('backupEntries', v)} />
                <FilterCell value={filters.ownedMemoryBytes ?? ''} onChange={v => setFilter('ownedMemoryBytes', v)} />
                <FilterCell value={filters.hits             ?? ''} onChange={v => setFilter('hits', v)} />
                <FilterCell value={filters.lockedEntries    ?? ''} onChange={v => setFilter('lockedEntries', v)} />
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {visible.map(r => (
                <TableRow key={r.name} hover sx={{ cursor: 'pointer' }}
                          onClick={() => nav(`/clusters/${id}/maps/${encodeURIComponent(r.name)}`)}>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{r.name}</TableCell>
                  <TableCell align="right">{fmtNum(r.ownedEntries)}</TableCell>
                  <TableCell align="right">{fmtNum(r.backupEntries)}</TableCell>
                  <TableCell align="right">{fmtBytes(r.ownedMemoryBytes)}</TableCell>
                  <TableCell align="right">{fmtNum(r.hits)}</TableCell>
                  <TableCell align="right">
                    {r.lockedEntries == null ? '—' :
                     r.lockedEntries === 0   ? <Typography variant="body2" color="text.disabled">0</Typography> :
                                               <Chip size="small" color="warning" label={r.lockedEntries} />}
                  </TableCell>
                  <TableCell align="right">
                    <Button size="small" onClick={(e) => { e.stopPropagation();
                      nav(`/clusters/${id}/maps/${encodeURIComponent(r.name)}`); }}>
                      Browse →
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {visible.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} align="center">
                    <Typography variant="body2" color="text.secondary">
                      No maps match the current filters.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </>
  );
}

function fmtNum(n: number | null): string {
  if (n == null || n < 0) return '—';
  return n.toLocaleString();
}

function fmtBytes(n: number | null): string {
  if (n == null || n < 0) return '—';
  if (n === 0) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0; let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${u[i]}`;
}

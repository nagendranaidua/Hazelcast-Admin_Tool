import { useState } from 'react';
import {
  Alert, Box, Button, Chip, IconButton, LinearProgress, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useFilterSort } from '../lib/useFilterSort';
import { FilterCell, SortableHeader } from '../lib/TableBits';

type AuditEvent = {
  id:           number;
  timestamp:    string;
  actor:        string;
  actorRole:    string | null;
  cluster:      string | null;
  action:       string;
  target:       string | null;
  reason:       string | null;
  outcome:      'PENDING' | 'SUCCESS' | 'FAILED' | 'DENIED';
  errorMessage: string | null;
};
type AuditPageDto = {
  page:       number;
  size:       number;
  totalPages: number;
  totalCount: number;
  items:      AuditEvent[];
};

/**
 * SUPER_ADMIN-only feed of every privileged action recorded by AuditService.
 *
 * <p>Two-tier filtering:
 *  - <b>Server-side</b>: actor + cluster filters scope which rows the DB returns.
 *    Required because audit can hold millions of rows; we never fetch them all.
 *  - <b>Client-side</b>: per-column filters + sort on the current page (≤ 50 rows).
 *    Lets operators slice a busy page (action=SQL_QUERY, outcome=FAILED) without a
 *    server round-trip.
 *
 * <p>Pagination is server-driven (Prev/Next buttons re-issue the query). The client
 * filter/sort applies to the current page only; that's the right tradeoff for an
 * append-only log where most operators only need recent rows.
 */
export function AuditPage() {
  const [page, setPage]       = useState(0);
  const [actor, setActor]     = useState('');
  const [cluster, setCluster] = useState('');

  const q = useQuery<AuditPageDto>({
    queryKey: ['audit', page, actor, cluster],
    queryFn: async () => (await api.get('/api/audit', {
      params: { page, size: 50, actor: actor || undefined, cluster: cluster || undefined },
    })).data,
    placeholderData: keepPreviousData,
  });

  // Per-column filter + sort over the *current page*. Numeric columns aren't relevant
  // here so all accessors return strings; the hook handles localeCompare correctly.
  const cols = [
    { key: 'timestamp', accessor: (e: AuditEvent) => e.timestamp },
    { key: 'actor',     accessor: (e: AuditEvent) => e.actor },
    { key: 'action',    accessor: (e: AuditEvent) => e.action },
    { key: 'cluster',   accessor: (e: AuditEvent) => e.cluster ?? '' },
    { key: 'target',    accessor: (e: AuditEvent) => e.target ?? '' },
    { key: 'reason',    accessor: (e: AuditEvent) => e.reason ?? '' },
    { key: 'outcome',   accessor: (e: AuditEvent) => e.outcome },
    { key: 'detail',    accessor: (e: AuditEvent) => e.errorMessage ?? '' },
  ];
  const { visible, filters, setFilter, sort, toggleSort } =
    useFilterSort<AuditEvent>(q.data?.items ?? [], cols, 'timestamp');

  return (
    <>
      <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 2 }}>
        <Typography variant="h4">Audit log</Typography>
        <Box sx={{ flexGrow: 1 }} />
        {q.data && (
          <Chip size="small"
                label={`${visible.length} of ${q.data.items.length} on page · ${q.data.totalCount} total`} />
        )}
        <Tooltip title="Reload current page">
          <span>
            <IconButton onClick={() => q.refetch()} disabled={q.isFetching}>
              <RefreshIcon />
            </IconButton>
          </span>
        </Tooltip>
      </Stack>

      <Stack direction="row" spacing={2} sx={{ mb: 2 }}>
        <TextField size="small" label="Actor (server filter)" value={actor}
                   onChange={e => { setPage(0); setActor(e.target.value); }} />
        <TextField size="small" label="Cluster (server filter)" value={cluster}
                   onChange={e => { setPage(0); setCluster(e.target.value); }} />
        <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center' }}>
          Server-scoped filters re-query the DB. Per-column filters in the table are client-side
          on the current page.
        </Typography>
      </Stack>

      {q.isLoading && <LinearProgress />}
      {q.error && <Alert severity="error">Failed to load audit events.</Alert>}

      {q.data && (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <SortableHeader label="Time"    col="timestamp" sort={sort} onClick={toggleSort} />
                  <SortableHeader label="Actor"   col="actor"     sort={sort} onClick={toggleSort} />
                  <SortableHeader label="Action"  col="action"    sort={sort} onClick={toggleSort} />
                  <SortableHeader label="Cluster" col="cluster"   sort={sort} onClick={toggleSort} />
                  <SortableHeader label="Target"  col="target"    sort={sort} onClick={toggleSort} />
                  <SortableHeader label="Reason"  col="reason"    sort={sort} onClick={toggleSort} />
                  <SortableHeader label="Outcome" col="outcome"   sort={sort} onClick={toggleSort} />
                  <SortableHeader label="Detail"  col="detail"    sort={sort} onClick={toggleSort} />
                </TableRow>
                <TableRow>
                  <FilterCell value={filters.timestamp ?? ''} onChange={v => setFilter('timestamp', v)} />
                  <FilterCell value={filters.actor     ?? ''} onChange={v => setFilter('actor', v)} />
                  <FilterCell value={filters.action    ?? ''} onChange={v => setFilter('action', v)} />
                  <FilterCell value={filters.cluster   ?? ''} onChange={v => setFilter('cluster', v)} />
                  <FilterCell value={filters.target    ?? ''} onChange={v => setFilter('target', v)} />
                  <FilterCell value={filters.reason    ?? ''} onChange={v => setFilter('reason', v)} />
                  <FilterCell value={filters.outcome   ?? ''} onChange={v => setFilter('outcome', v)} />
                  <FilterCell value={filters.detail    ?? ''} onChange={v => setFilter('detail', v)} />
                </TableRow>
              </TableHead>
              <TableBody>
                {visible.map(e => (
                  <TableRow key={e.id} hover>
                    <TableCell><Typography variant="caption">{new Date(e.timestamp).toLocaleString()}</Typography></TableCell>
                    <TableCell>{e.actor}</TableCell>
                    <TableCell><Chip size="small" variant="outlined" label={e.action} /></TableCell>
                    <TableCell>{e.cluster ?? '—'}</TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      <Tooltip title={e.target ?? ''}><span>{e.target ?? '—'}</span></Tooltip>
                    </TableCell>
                    <TableCell sx={{ maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      <Tooltip title={e.reason ?? ''}><span>{e.reason ?? '—'}</span></Tooltip>
                    </TableCell>
                    <TableCell>
                      <Chip size="small"
                            color={e.outcome === 'SUCCESS' ? 'success'
                                 : e.outcome === 'FAILED'  ? 'error'
                                 : e.outcome === 'DENIED'  ? 'warning' : 'default'}
                            label={e.outcome} />
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', maxWidth: 320, whiteSpace: 'pre-wrap', fontSize: 11 }}>
                      <Tooltip title={e.errorMessage ?? ''}><span>{truncate(e.errorMessage)}</span></Tooltip>
                    </TableCell>
                  </TableRow>
                ))}
                {visible.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={8} align="center">
                      <Typography variant="body2" color="text.secondary">
                        {q.data.items.length === 0 ? 'No matching events on the server.' : 'No rows match the per-column filters on this page.'}
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>

          <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 2 }}>
            <Button size="small" variant="outlined"
                    disabled={page === 0} onClick={() => setPage(p => p - 1)}>
              ← Prev
            </Button>
            <Typography variant="body2">
              Page {q.data.page + 1} of {q.data.totalPages || 1} · {q.data.totalCount} events
            </Typography>
            <Button size="small" variant="outlined"
                    disabled={page + 1 >= q.data.totalPages} onClick={() => setPage(p => p + 1)}>
              Next →
            </Button>
          </Stack>
        </>
      )}
    </>
  );
}

function truncate(s: string | null | undefined, n = 80): string {
  if (!s) return '—';
  return s.length <= n ? s : s.substring(0, n) + '…';
}

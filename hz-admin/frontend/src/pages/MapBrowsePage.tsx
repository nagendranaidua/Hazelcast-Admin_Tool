import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Alert, Box, Button, Card, CardContent, Chip, IconButton, LinearProgress,
  Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  TextField, Tooltip, Typography,
} from '@mui/material';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { EditEntryDialog } from './EditEntryDialog';
import { MapMetricsStrip } from './MapMetricsStrip';

type EntryView = {
  keyJson:        string | null;
  valueJson:      string | null;
  valueClassName: string | null;
  valueSizeBytes: number | null;
};
type BrowsePage = {
  mapName:    string;
  pageIndex:  number;
  pageSize:   number;
  totalSize:  number;
  hasMore:    boolean;
  entries:    EntryView[];
};
type MapStats = {
  name:                  string;
  ownedEntryCount:       number;
  ownedEntryMemoryCost:  number;
  hits:                  number;
  putOperationCount:     number;
  getOperationCount:     number;
  removeOperationCount:  number;
  lockedEntryCount:      number;
};

export function MapBrowsePage() {
  const { id, name: rawName } = useParams();
  const name = rawName ? decodeURIComponent(rawName) : '';
  const qc = useQueryClient();
  const nav = useNavigate();
  const { hasAnyRole } = useAuth();
  const canWrite = hasAnyRole('SUPER_ADMIN', 'CLUSTER_OPERATOR');

  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize] = useState(50);
  const [lookupKey, setLookupKey] = useState('');
  const [lookupResult, setLookupResult] = useState<EntryView | null>(null);
  const [editing, setEditing] = useState<{ key: string; value: string | null } | null>(null);

  const stats = useQuery<MapStats>({
    queryKey: ['map-stats', id, name],
    queryFn: async () => (await api.get(`/api/clusters/${id}/maps/${encodeURIComponent(name)}/stats`)).data,
    refetchInterval: 15_000,
  });

  const page = useQuery<BrowsePage>({
    queryKey: ['map-browse', id, name, pageIndex, pageSize],
    queryFn: async () => (await api.get(
      `/api/clusters/${id}/maps/${encodeURIComponent(name)}/entry/browse`,
      { params: { pageIndex, pageSize, includeValues: true } })).data,
    placeholderData: keepPreviousData,
  });

  const lookup = useMutation({
    mutationFn: async () => (await api.get(
      `/api/clusters/${id}/maps/${encodeURIComponent(name)}/entry`,
      { params: { key: lookupKey } })).data as EntryView,
    onSuccess: (v) => setLookupResult(v),
  });

  const remove = useMutation({
    mutationFn: async (args: { key: string; reason: string }) => (await api.delete(
      `/api/clusters/${id}/maps/${encodeURIComponent(name)}/entry`,
      { data: args })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['map-browse', id, name] });
      qc.invalidateQueries({ queryKey: ['map-stats',  id, name] });
    },
  });

  return (
    <>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Button size="small" onClick={() => nav(`/clusters/${id}/maps`)}>← All maps</Button>
        <Typography variant="h4">{name}</Typography>
        <Box sx={{ flexGrow: 1 }} />
        {canWrite && (
          <Button variant="outlined" size="small" onClick={() => setEditing({ key: '', value: '' })}>
            New entry
          </Button>
        )}
      </Stack>

      {/* Stage 3: per-map mini-charts (Entries / Operations/s / Memory / Backups).
          Sourced from MetricsCollector.sampleMaps; populates within ~30s of first load. */}
      <MapMetricsStrip clusterId={Number(id)} mapName={name} />

      {stats.data && (
        <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap">
          <Chip size="small" label={`${stats.data.ownedEntryCount.toLocaleString()} entries`} />
          <Chip size="small" label={`heap ~${formatBytes(stats.data.ownedEntryMemoryCost)}`} />
          <Chip size="small" label={`hits ${stats.data.hits}`} />
          <Chip size="small" label={`puts ${stats.data.putOperationCount}`} />
          <Chip size="small" label={`gets ${stats.data.getOperationCount}`} />
          <Chip size="small" label={`removes ${stats.data.removeOperationCount}`} />
          {stats.data.lockedEntryCount > 0 &&
            <Chip size="small" color="warning" label={`${stats.data.lockedEntryCount} locked`} />}
        </Stack>
      )}

      {/* Look up by key */}
      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="subtitle2" sx={{ minWidth: 140 }}>Look up by key</Typography>
            <TextField
              size="small"
              fullWidth
              placeholder='e.g. "user-42" or {"id":42}'
              value={lookupKey}
              onChange={e => setLookupKey(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && lookupKey && lookup.mutate()}
            />
            <Button variant="outlined" disabled={!lookupKey || lookup.isPending}
                    onClick={() => lookup.mutate()}>Find</Button>
          </Stack>
          {lookup.error && <Alert severity="error" sx={{ mt: 1 }}>Lookup failed.</Alert>}
          {lookupResult && (
            <Box sx={{ mt: 2 }}>
              <Typography variant="caption" color="text.secondary">
                class: {lookupResult.valueClassName ?? '—'} · {lookupResult.valueSizeBytes ?? 0}B
              </Typography>
              <Paper variant="outlined" sx={{ mt: 1, p: 1, fontFamily: 'monospace', whiteSpace: 'pre-wrap' }}>
                {lookupResult.valueJson ?? '(no value)'}
              </Paper>
              {canWrite && lookupResult.valueJson != null && (
                <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                  <Button size="small" onClick={() => setEditing({ key: lookupKey, value: lookupResult.valueJson })}>
                    Edit
                  </Button>
                  <DeleteButton onConfirm={(reason) => remove.mutate({ key: lookupKey, reason })} />
                </Stack>
              )}
            </Box>
          )}
        </CardContent>
      </Card>

      {/* Browse */}
      {page.isLoading && <LinearProgress />}
      {page.error && <Alert severity="error">Failed to load entries.</Alert>}

      {page.data && (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Key</TableCell>
                  <TableCell>Value</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell align="right">Bytes</TableCell>
                  {canWrite && <TableCell align="right">Actions</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {page.data.entries.map((e, i) => (
                  <TableRow key={`${e.keyJson}-${i}`} hover>
                    <TableCell sx={{ fontFamily: 'monospace', maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {e.keyJson}
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', maxWidth: 480, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      <Tooltip title={e.valueJson ?? ''}><span>{e.valueJson ?? '(opaque)'}</span></Tooltip>
                    </TableCell>
                    <TableCell><Chip size="small" label={shortType(e.valueClassName)} /></TableCell>
                    <TableCell align="right">{e.valueSizeBytes ?? 0}</TableCell>
                    {canWrite && (
                      <TableCell align="right">
                        <Stack direction="row" spacing={1} justifyContent="flex-end">
                          <Button size="small" onClick={() => setEditing({ key: e.keyJson ?? '', value: e.valueJson })}>
                            Edit
                          </Button>
                          <DeleteButton onConfirm={(reason) => remove.mutate({ key: e.keyJson ?? '', reason })} />
                        </Stack>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
                {page.data.entries.length === 0 && (
                  <TableRow><TableCell colSpan={canWrite ? 5 : 4} align="center">
                    <Typography variant="body2" color="text.secondary">No entries on this page.</Typography>
                  </TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>

          <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 2 }}>
            <Button size="small" disabled={pageIndex === 0} onClick={() => setPageIndex(p => p - 1)}>Prev</Button>
            <Typography variant="body2">
              Page {page.data.pageIndex + 1} · {page.data.entries.length} of {page.data.totalSize.toLocaleString()}
            </Typography>
            <Button size="small" disabled={!page.data.hasMore} onClick={() => setPageIndex(p => p + 1)}>Next</Button>
          </Stack>
        </>
      )}

      {editing && (
        <EditEntryDialog
          mapName={name}
          clusterId={Number(id)}
          initialKey={editing.key}
          initialValue={editing.value}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            qc.invalidateQueries({ queryKey: ['map-browse', id, name] });
            qc.invalidateQueries({ queryKey: ['map-stats',  id, name] });
          }}
        />
      )}
    </>
  );
}

function shortType(fqn?: string | null): string {
  if (!fqn) return '—';
  const dot = fqn.lastIndexOf('.');
  return dot < 0 ? fqn : fqn.substring(dot + 1);
}

function formatBytes(n: number): string {
  if (!n) return '0B';
  const u = ['B', 'KB', 'MB', 'GB']; let i = 0; let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(1)}${u[i]}`;
}

/** Inline two-step delete: click → reason input → confirm. Avoids opening a full dialog for each row. */
function DeleteButton({ onConfirm }: { onConfirm: (reason: string) => void }) {
  const [armed, setArmed] = useState(false);
  const [reason, setReason] = useState('');
  if (!armed) {
    return <Button size="small" color="error" onClick={() => setArmed(true)}>Delete</Button>;
  }
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <TextField size="small" placeholder="Reason (required)" value={reason}
                 onChange={e => setReason(e.target.value)} sx={{ width: 200 }} />
      <IconButton size="small" color="error" disabled={!reason}
                  onClick={() => { onConfirm(reason); setArmed(false); setReason(''); }}>✓</IconButton>
      <IconButton size="small" onClick={() => { setArmed(false); setReason(''); }}>✕</IconButton>
    </Stack>
  );
}

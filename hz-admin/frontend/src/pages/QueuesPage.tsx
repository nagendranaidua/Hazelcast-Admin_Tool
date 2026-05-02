import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Box, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useFilterSort } from '../lib/useFilterSort';
import { FilterCell, SortableHeader } from '../lib/TableBits';
import QueueIcon from '@mui/icons-material/Inbox';

type QueueSummary = {
  name:  string;
  size:  number | null;
  error?: string;
};

/**
 * IQueues overview for a cluster. One row per queue with current depth.
 * Click a row to drill into the QueueBrowsePage (peek, offer, poll, drain).
 */
export function QueuesPage() {
  const { id } = useParams();
  const nav = useNavigate();

  const q = useQuery<QueueSummary[]>({
    queryKey: ['cluster-queues-summary', id],
    queryFn: async () => (await api.get<QueueSummary[]>(`/api/clusters/${id}/queues/summary`)).data,
    refetchInterval: 15_000,
  });

  const cols = [
    { key: 'name', accessor: (r: QueueSummary) => r.name },
    { key: 'size', accessor: (r: QueueSummary) => r.size ?? -1 },
  ];
  const { visible, filters, setFilter, sort, toggleSort } = useFilterSort<QueueSummary>(q.data ?? [], cols, 'name');

  return (
    <>
      <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 2 }}>
        <QueueIcon color="primary" sx={{ fontSize: 32 }} />
        <Typography variant="h4">Queues</Typography>
        <Box sx={{ flexGrow: 1 }} />
        <Chip size="small" label={`${visible.length} of ${q.data?.length ?? 0}`} />
        <Button size="small" variant="outlined" onClick={() => nav(`/clusters/${id}/sql`)}>SQL console</Button>
      </Stack>

      {q.isLoading && <LinearProgress sx={{ mb: 2 }} />}
      {q.error && <Alert severity="error" sx={{ mb: 2 }}>Failed to list queues. The cluster may be unreachable.</Alert>}

      {q.data && q.data.length === 0 && (
        <Paper
          variant="outlined"
          sx={{
            textAlign: 'center', py: 8, px: 4,
            borderStyle: 'dashed', borderColor: 'divider',
            backgroundColor: 'action.hover', borderRadius: 3,
          }}
        >
          <QueueIcon sx={{ fontSize: 72, color: 'text.disabled', mb: 2 }} />
          <Typography variant="h5" color="text.secondary" gutterBottom>
            No IQueues found on this cluster
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ maxWidth: 520, mx: 'auto' }}>
            Queues appear here once created from any Hazelcast client (Java, Node, Python).
            Hazelcast lazily registers distributed objects on first use.
          </Typography>
        </Paper>
      )}

      {q.data && q.data.length > 0 && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow sx={{ bgcolor: 'action.hover' }}>
                <SortableHeader label="Queue Name" col="name" sort={sort} onClick={toggleSort} />
                <SortableHeader label="Depth" col="size" sort={sort} onClick={toggleSort} align="right" />
                <TableCell />
              </TableRow>
              <TableRow>
                <FilterCell value={filters.name ?? ''} onChange={v => setFilter('name', v)} />
                <FilterCell value={filters.size ?? ''} onChange={v => setFilter('size', v)} />
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {visible.map(r => (
                <TableRow key={r.name} hover sx={{ cursor: 'pointer' }}
                          onClick={() => nav(`/clusters/${id}/queues/${encodeURIComponent(r.name)}`)}>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{r.name}</TableCell>
                  <TableCell align="right">
                    {r.size == null ? '—' :
                     r.size === 0 ? <Typography variant="body2" color="text.disabled">0</Typography> :
                     <Chip size="small" color={r.size > 100 ? 'warning' : 'default'} label={r.size.toLocaleString()} />}
                  </TableCell>
                  <TableCell align="right">
                    <Button size="small" onClick={(e) => { e.stopPropagation();
                      nav(`/clusters/${id}/queues/${encodeURIComponent(r.name)}`); }}>
                      Browse →
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {visible.length === 0 && (
                <TableRow>
                  <TableCell colSpan={3} align="center">
                    <Typography variant="body2" color="text.secondary">
                      No queues match the current filters.
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

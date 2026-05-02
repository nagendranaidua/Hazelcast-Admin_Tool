import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent,
  DialogTitle, IconButton, LinearProgress, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import QueueIcon from '@mui/icons-material/Inbox';
import SendIcon from '@mui/icons-material/Send';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';

type EntryView = {
  keyJson:        string | null;
  valueJson:      string | null;
  valueClassName: string | null;
  valueSizeBytes: number | null;
};

type PeekResult = {
  queueName:    string;
  size:         number;
  peekedCount:  number;
  items:        EntryView[];
};

type QueueStats = {
  name: string;
  size: number;
};

/**
 * Browse (peek) the contents of a single IQueue. Supports:
 * - Non-destructive peek (view items without removing)
 * - Offer (enqueue) new JSON items
 * - Poll (dequeue) items from head
 * - Drain (remove all items)
 */
export function QueueBrowsePage() {
  const { id, name: rawName } = useParams();
  const name = rawName ? decodeURIComponent(rawName) : '';
  const qc = useQueryClient();
  const nav = useNavigate();
  const { hasAnyRole } = useAuth();
  const canWrite = hasAnyRole('SUPER_ADMIN', 'CLUSTER_OPERATOR');

  const [peekLimit, setPeekLimit] = useState(100);
  const [offerOpen, setOfferOpen] = useState(false);
  const [offerValue, setOfferValue] = useState('');
  const [offerReason, setOfferReason] = useState('');
  const [pollOpen, setPollOpen] = useState(false);
  const [pollCount, setPollCount] = useState(1);
  const [pollReason, setPollReason] = useState('');
  const [drainOpen, setDrainOpen] = useState(false);
  const [drainReason, setDrainReason] = useState('');

  const stats = useQuery<QueueStats>({
    queryKey: ['queue-stats', id, name],
    queryFn: async () => (await api.get(`/api/clusters/${id}/queues/${encodeURIComponent(name)}/stats`)).data,
    refetchInterval: 10_000,
  });

  const peek = useQuery<PeekResult>({
    queryKey: ['queue-peek', id, name, peekLimit],
    queryFn: async () => (await api.get(
      `/api/clusters/${id}/queues/${encodeURIComponent(name)}/entries/peek`,
      { params: { limit: peekLimit } })).data,
    refetchInterval: 15_000,
  });

  const invalidateAll = () => {
    qc.invalidateQueries({ queryKey: ['queue-peek', id, name] });
    qc.invalidateQueries({ queryKey: ['queue-stats', id, name] });
    qc.invalidateQueries({ queryKey: ['cluster-queues-summary', id] });
  };

  const offerMut = useMutation({
    mutationFn: async () => (await api.post(
      `/api/clusters/${id}/queues/${encodeURIComponent(name)}/entries/offer`,
      { value: offerValue, reason: offerReason })).data,
    onSuccess: () => { setOfferOpen(false); setOfferValue(''); setOfferReason(''); invalidateAll(); },
  });

  const pollMut = useMutation({
    mutationFn: async () => (await api.post(
      `/api/clusters/${id}/queues/${encodeURIComponent(name)}/entries/poll`,
      { count: pollCount, reason: pollReason })).data,
    onSuccess: () => { setPollOpen(false); setPollCount(1); setPollReason(''); invalidateAll(); },
  });

  const drainMut = useMutation({
    mutationFn: async () => (await api.post(
      `/api/clusters/${id}/queues/${encodeURIComponent(name)}/entries/drain`,
      { reason: drainReason })).data,
    onSuccess: () => { setDrainOpen(false); setDrainReason(''); invalidateAll(); },
  });

  return (
    <>
      {/* Header */}
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Button size="small" onClick={() => nav(`/clusters/${id}/queues`)}>← All queues</Button>
        <QueueIcon color="primary" />
        <Typography variant="h4">{name}</Typography>
        <Box sx={{ flexGrow: 1 }} />
        {canWrite && (
          <Stack direction="row" spacing={1}>
            <Button variant="contained" size="small" startIcon={<SendIcon />}
                    onClick={() => setOfferOpen(true)}>Offer</Button>
            <Button variant="outlined" size="small" startIcon={<PlayArrowIcon />}
                    onClick={() => setPollOpen(true)}>Poll</Button>
            <Button variant="outlined" size="small" color="error" startIcon={<DeleteSweepIcon />}
                    onClick={() => setDrainOpen(true)}>Drain</Button>
          </Stack>
        )}
      </Stack>

      {/* Stats chips */}
      {stats.data && (
        <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap">
          <Chip label={`Depth: ${stats.data.size.toLocaleString()}`}
                color={stats.data.size > 0 ? 'primary' : 'default'} size="small" />
          <Chip label={`Peeking top ${peekLimit}`} size="small" variant="outlined" />
        </Stack>
      )}

      {/* Peek controls */}
      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Stack direction="row" spacing={2} alignItems="center">
            <Typography variant="subtitle2" sx={{ minWidth: 120 }}>Peek limit</Typography>
            <TextField size="small" type="number" value={peekLimit}
                       onChange={e => setPeekLimit(Math.max(1, Math.min(500, Number(e.target.value))))}
                       sx={{ width: 120 }} inputProps={{ min: 1, max: 500 }} />
            <Button variant="outlined" size="small"
                    onClick={() => invalidateAll()}>Refresh</Button>
            <Typography variant="body2" color="text.secondary">
              Non-destructive: items remain in the queue.
            </Typography>
          </Stack>
        </CardContent>
      </Card>

      {/* Items table */}
      {peek.isLoading && <LinearProgress />}
      {peek.error && <Alert severity="error" sx={{ mb: 2 }}>Failed to peek into queue.</Alert>}

      {peek.data && (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow sx={{ bgcolor: 'action.hover' }}>
                  <TableCell sx={{ fontWeight: 600, width: 60 }}>#</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>Value</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>Type</TableCell>
                  <TableCell sx={{ fontWeight: 600 }} align="right">Bytes</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {peek.data.items.map((item, idx) => (
                  <TableRow key={idx} hover>
                    <TableCell>
                      <Chip size="small" label={idx + 1} variant="outlined" />
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', maxWidth: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      <Tooltip title={item.valueJson ?? ''}>
                        <span>{item.valueJson ?? '(opaque)'}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell><Chip size="small" label={shortType(item.valueClassName)} /></TableCell>
                    <TableCell align="right">{item.valueSizeBytes ?? 0}</TableCell>
                  </TableRow>
                ))}
                {peek.data.items.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4} align="center">
                      <Box sx={{ py: 4 }}>
                        <QueueIcon sx={{ fontSize: 48, color: 'text.disabled' }} />
                        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                          Queue is empty
                        </Typography>
                      </Box>
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>

          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Showing {peek.data.peekedCount} of {peek.data.size.toLocaleString()} items (head → tail order)
          </Typography>
        </>
      )}

      {/* ═══════════════════ DIALOGS ═══════════════════ */}

      {/* Offer Dialog */}
      <Dialog open={offerOpen} onClose={() => setOfferOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Offer Item to Queue</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Value (JSON)" value={offerValue}
                       onChange={e => setOfferValue(e.target.value)}
                       multiline rows={6} fullWidth
                       placeholder='{"orderId": 42, "status": "pending"}' />
            <TextField label="Reason (required for audit)" value={offerReason}
                       onChange={e => setOfferReason(e.target.value)} fullWidth
                       placeholder="Why are you adding this item?" />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOfferOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={!offerValue || !offerReason || offerMut.isPending}
                  onClick={() => offerMut.mutate()}>
            {offerMut.isPending ? 'Offering...' : 'Offer'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Poll Dialog */}
      <Dialog open={pollOpen} onClose={() => setPollOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Poll Items from Queue</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="warning">
              Polling is <strong>destructive</strong> — items will be removed from the queue permanently.
            </Alert>
            <TextField label="Number of items to poll" type="number" value={pollCount}
                       onChange={e => setPollCount(Math.max(1, Math.min(100, Number(e.target.value))))}
                       fullWidth inputProps={{ min: 1, max: 100 }} />
            <TextField label="Reason (required for audit)" value={pollReason}
                       onChange={e => setPollReason(e.target.value)} fullWidth
                       placeholder="Why are you polling these items?" />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPollOpen(false)}>Cancel</Button>
          <Button variant="contained" color="warning" disabled={!pollReason || pollMut.isPending}
                  onClick={() => pollMut.mutate()}>
            {pollMut.isPending ? 'Polling...' : `Poll ${pollCount} item(s)`}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Drain Dialog */}
      <Dialog open={drainOpen} onClose={() => setDrainOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Drain All Items</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="error">
              This will <strong>remove all items</strong> from the queue. This action cannot be undone.
            </Alert>
            <TextField label="Reason (required for audit)" value={drainReason}
                       onChange={e => setDrainReason(e.target.value)} fullWidth
                       placeholder="Why are you draining this queue?" />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDrainOpen(false)}>Cancel</Button>
          <Button variant="contained" color="error" disabled={!drainReason || drainMut.isPending}
                  onClick={() => drainMut.mutate()}>
            {drainMut.isPending ? 'Draining...' : 'Drain All'}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function shortType(fqn?: string | null): string {
  if (!fqn) return '—';
  const dot = fqn.lastIndexOf('.');
  return dot < 0 ? fqn : fqn.substring(dot + 1);
}

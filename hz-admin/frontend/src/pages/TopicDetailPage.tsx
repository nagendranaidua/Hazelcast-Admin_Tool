import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent,
  DialogTitle, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead,
  TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import CampaignIcon from '@mui/icons-material/Campaign';
import SendIcon from '@mui/icons-material/Send';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import StopIcon from '@mui/icons-material/Stop';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';

type EntryView = {
  keyJson:        string | null;
  valueJson:      string | null;
  valueClassName: string | null;
  valueSizeBytes: number | null;
};

type ReceivedMessage = {
  timestamp: number;
  entry: EntryView;
};

/**
 * Publish to and listen (SSE) on a single ITopic.
 *
 * The listener opens an EventSource to /api/clusters/:id/topics/:name/stream
 * and displays received messages in real time (newest first). The user can
 * also publish new messages to the topic.
 */
export function TopicDetailPage() {
  const { id, name: rawName } = useParams();
  const name = rawName ? decodeURIComponent(rawName) : '';
  const nav = useNavigate();
  const { hasAnyRole } = useAuth();
  const canWrite = hasAnyRole('SUPER_ADMIN', 'CLUSTER_OPERATOR');

  // Publish dialog
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishValue, setPublishValue] = useState('');
  const [publishReason, setPublishReason] = useState('');

  // Listener state
  const [listening, setListening] = useState(false);
  const [messages, setMessages] = useState<ReceivedMessage[]>([]);
  const [listenerError, setListenerError] = useState<string | null>(null);
  const eventSourceRef = useRef<EventSource | null>(null);
  const maxMessages = 500;

  const startListening = useCallback(() => {
    if (eventSourceRef.current) return;
    setListenerError(null);

    const url = `/api/clusters/${id}/topics/${encodeURIComponent(name)}/stream`;
    const es = new EventSource(url);
    eventSourceRef.current = es;
    setListening(true);

    es.addEventListener('subscribed', () => {
      // Subscription confirmed
    });

    es.addEventListener('message', (evt) => {
      try {
        const entry: EntryView = JSON.parse(evt.data);
        setMessages(prev => {
          const next = [{ timestamp: Date.now(), entry }, ...prev];
          return next.length > maxMessages ? next.slice(0, maxMessages) : next;
        });
      } catch (err) {
        console.error('Failed to parse topic message', err);
      }
    });

    es.addEventListener('error', () => {
      // EventSource will auto-reconnect for most error types.
      // Only set error + stop if readyState is CLOSED (terminal).
      if (es.readyState === EventSource.CLOSED) {
        setListenerError('Connection closed by server. Click Listen to reconnect.');
        setListening(false);
        eventSourceRef.current = null;
      }
    });
  }, [id, name]);

  const stopListening = useCallback(() => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
    setListening(false);
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
    };
  }, []);

  const publishMut = useMutation({
    mutationFn: async () => (await api.post(
      `/api/clusters/${id}/topics/${encodeURIComponent(name)}/publish`,
      { value: publishValue, reason: publishReason })).data,
    onSuccess: () => { setPublishOpen(false); setPublishValue(''); setPublishReason(''); },
  });

  return (
    <>
      {/* Header */}
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Button size="small" onClick={() => nav(`/clusters/${id}/topics`)}>← All topics</Button>
        <CampaignIcon color="primary" />
        <Typography variant="h4">{name}</Typography>
        <Box sx={{ flexGrow: 1 }} />
        <Stack direction="row" spacing={1}>
          {canWrite && (
            <Button variant="contained" size="small" startIcon={<SendIcon />}
                    onClick={() => setPublishOpen(true)}>
              Publish
            </Button>
          )}
          {!listening ? (
            <Button variant="outlined" size="small" color="success" startIcon={<PlayArrowIcon />}
                    onClick={startListening}>
              Listen
            </Button>
          ) : (
            <Button variant="outlined" size="small" color="error" startIcon={<StopIcon />}
                    onClick={stopListening}>
              Stop Listening
            </Button>
          )}
        </Stack>
      </Stack>

      {/* Status bar */}
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} alignItems="center" flexWrap="wrap">
        <Chip
          icon={<FiberManualRecordIcon sx={{ fontSize: 12 }} />}
          label={listening ? 'Listening' : 'Not listening'}
          color={listening ? 'success' : 'default'}
          size="small"
          variant={listening ? 'filled' : 'outlined'}
        />
        <Chip label={`${messages.length} message(s) received`} size="small" variant="outlined" />
        {messages.length > 0 && (
          <Button size="small" onClick={() => setMessages([])}>Clear</Button>
        )}
      </Stack>

      {listenerError && <Alert severity="error" sx={{ mb: 2 }}>{listenerError}</Alert>}

      {/* Info card when not listening and no messages */}
      {!listening && messages.length === 0 && (
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <Stack direction="row" spacing={2} alignItems="center">
              <CampaignIcon color="action" sx={{ fontSize: 48 }} />
              <Box>
                <Typography variant="subtitle1" fontWeight={600}>
                  Real-time Topic Listener
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Click <strong>Listen</strong> to start receiving messages published to this topic
                  in real time via Server-Sent Events. Messages appear below as they arrive
                  (newest first, up to {maxMessages} retained).
                </Typography>
              </Box>
            </Stack>
          </CardContent>
        </Card>
      )}

      {/* Messages table */}
      {messages.length > 0 && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow sx={{ bgcolor: 'action.hover' }}>
                <TableCell sx={{ fontWeight: 600, width: 60 }}>#</TableCell>
                <TableCell sx={{ fontWeight: 600, width: 180 }}>Received At</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Message</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>Type</TableCell>
                <TableCell sx={{ fontWeight: 600 }} align="right">Bytes</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {messages.map((msg, idx) => (
                <TableRow key={`${msg.timestamp}-${idx}`} hover
                          sx={idx === 0 ? { bgcolor: 'success.main', '& td': { color: 'success.contrastText' } } : {}}>
                  <TableCell>
                    <Chip size="small" label={messages.length - idx} variant="outlined"
                          sx={idx === 0 ? { borderColor: 'success.contrastText', color: 'inherit' } : {}} />
                  </TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>
                    {new Date(msg.timestamp).toLocaleTimeString(undefined, {
                      hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3,
                    })}
                  </TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', maxWidth: 500, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    <Tooltip title={msg.entry.valueJson ?? ''}>
                      <span>{msg.entry.valueJson ?? '(opaque)'}</span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Chip size="small" label={shortType(msg.entry.valueClassName)} />
                  </TableCell>
                  <TableCell align="right">{msg.entry.valueSizeBytes ?? 0}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* ═══════��═══════════ DIALOGS ═══════════════════ */}

      {/* Publish Dialog */}
      <Dialog open={publishOpen} onClose={() => setPublishOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Publish Message to Topic</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="info">
              Messages published to a topic are delivered to all current subscribers in real time.
              They are <strong>not</strong> retained — only active listeners will see this message.
            </Alert>
            <TextField label="Message (JSON)" value={publishValue}
                       onChange={e => setPublishValue(e.target.value)}
                       multiline rows={6} fullWidth
                       placeholder='{"event": "user-signup", "userId": 42}' />
            <TextField label="Reason (required for audit)" value={publishReason}
                       onChange={e => setPublishReason(e.target.value)} fullWidth
                       placeholder="Why are you publishing this message?" />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPublishOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={!publishValue || !publishReason || publishMut.isPending}
                  onClick={() => publishMut.mutate()}>
            {publishMut.isPending ? 'Publishing...' : 'Publish'}
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

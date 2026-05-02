import { useParams } from 'react-router-dom';
import { useEffect, useRef, useState } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Chip, IconButton,
  Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Tooltip, Typography,
} from '@mui/material';
import CloudDoneIcon from '@mui/icons-material/CloudDone';
import CloudOffIcon from '@mui/icons-material/CloudOff';
import { useAuth } from '../auth/AuthContext';
import { useFilterSort } from '../lib/useFilterSort';
import { FilterCell, SortableHeader } from '../lib/TableBits';

type Member = {
  uuid:     string;
  address:  string;          // host:port — preserved for backward compat
  host:     string;
  port:     number;
  hostName: string;          // reverse-DNS, "" when unavailable
  lite:     boolean;
  version:  string;
  local:    boolean;
};
type EventLog = { at: number; kind: string; text: string };

/**
 * Cluster-members view. Drives off the existing SSE feed at
 * /api/clusters/{id}/members/events (snapshot + member-added/removed events).
 *
 * <p>Now a proper sortable / per-column filterable table instead of a card stack so
 * operators can find a specific member in a 30+ node cluster without scrolling. Each
 * column header has an inline TextField for substring filtering; sort by clicking
 * the column header. Filters are AND-combined across columns.
 *
 * <p>Hostname comes from the bridge's reverse-DNS lookup (cached per IP). Empty when
 * DNS doesn't resolve, in which case the IP is shown unchanged. CPU/memory per member
 * is intentionally absent here — surfacing that requires server-side cooperation
 * (IExecutorService task or JMX), tracked as Stage 4 in the dashboard plan.
 */
export function MembersPage() {
  const { id } = useParams();
  const { hasAnyRole } = useAuth();

  const [members, setMembers] = useState<Member[]>([]);
  const [log,     setLog]     = useState<EventLog[]>([]);
  const [status,  setStatus]  = useState<'connecting' | 'open' | 'closed' | 'error'>('connecting');
  const [error,   setError]   = useState<string | null>(null);
  const esRef = useRef<EventSource | null>(null);

  // Per-column filter + sort, shared hook
  const cols = [
    { key: 'address',  accessor: (m: Member) => m.address },
    { key: 'hostName', accessor: (m: Member) => m.hostName },
    { key: 'host',     accessor: (m: Member) => m.host },
    { key: 'port',     accessor: (m: Member) => m.port },
    { key: 'version',  accessor: (m: Member) => m.version },
    { key: 'uuid',     accessor: (m: Member) => m.uuid },
  ];
  const { visible, filters, setFilter, sort, toggleSort } = useFilterSort<Member>(members, cols, 'address');

  useEffect(() => {
    if (!id) return;
    setStatus('connecting');
    setError(null);

    const url = `/api/clusters/${id}/members/events`;
    const es  = new EventSource(url, { withCredentials: true });
    esRef.current = es;

    es.addEventListener('snapshot', (ev) => {
      try { setMembers(JSON.parse((ev as MessageEvent).data)); } catch { /* ignore */ }
      setStatus('open');
    });
    es.addEventListener('member-added', (ev) => {
      try {
        const m = JSON.parse((ev as MessageEvent).data) as Member;
        setMembers(prev => prev.find(x => x.uuid === m.uuid) ? prev : [...prev, m]);
        appendLog(setLog, 'added', `${m.address} joined`);
      } catch { /* ignore */ }
    });
    es.addEventListener('member-removed', (ev) => {
      try {
        const m = JSON.parse((ev as MessageEvent).data) as Member;
        setMembers(prev => prev.filter(x => x.uuid !== m.uuid));
        appendLog(setLog, 'removed', `${m.address} left`);
      } catch { /* ignore */ }
    });
    es.addEventListener('error', (ev) => {
      try {
        const data = (ev as MessageEvent).data;
        if (data) setError(typeof data === 'string' ? data : JSON.stringify(data));
      } catch { /* ignore */ }
    });
    es.addEventListener('heartbeat', () => { /* keep proxies awake */ });

    es.onerror = () => {
      setStatus(es.readyState === EventSource.CLOSED ? 'closed' : 'error');
    };
    es.onopen = () => setStatus('open');

    return () => { es.close(); esRef.current = null; };
  }, [id]);

  const reconnect = () => {
    if (esRef.current) { esRef.current.close(); esRef.current = null; }
    window.location.reload();
  };

  return (
    <>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h4">Cluster members</Typography>
        <Box sx={{ flexGrow: 1 }} />
        <Chip size="small" label={`${visible.length} of ${members.length}`} />
        <ConnIndicator status={status} />
        <Tooltip title="Reconnect"><IconButton size="small" onClick={reconnect}>↻</IconButton></Tooltip>
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      <TableContainer component={Paper} variant="outlined" sx={{ mb: 3 }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <SortableHeader label="Address (host:port)" col="address"  sort={sort} onClick={toggleSort} />
              <SortableHeader label="Hostname"            col="hostName" sort={sort} onClick={toggleSort} />
              <SortableHeader label="Host"                col="host"     sort={sort} onClick={toggleSort} />
              <SortableHeader label="Port"                col="port"     sort={sort} onClick={toggleSort} align="right" />
              <SortableHeader label="Version"             col="version"  sort={sort} onClick={toggleSort} />
              <SortableHeader label="UUID"                col="uuid"     sort={sort} onClick={toggleSort} />
              <TableCell>Flags</TableCell>
              {hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR') && <TableCell align="right">Actions</TableCell>}
            </TableRow>
            <TableRow>
              <FilterCell value={filters.address  ?? ''} onChange={v => setFilter('address', v)} />
              <FilterCell value={filters.hostName ?? ''} onChange={v => setFilter('hostName', v)} />
              <FilterCell value={filters.host     ?? ''} onChange={v => setFilter('host', v)} />
              <FilterCell value={filters.port     ?? ''} onChange={v => setFilter('port', v)} />
              <FilterCell value={filters.version  ?? ''} onChange={v => setFilter('version', v)} />
              <FilterCell value={filters.uuid     ?? ''} onChange={v => setFilter('uuid', v)} />
              <TableCell />
              {hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR') && <TableCell />}
            </TableRow>
          </TableHead>
          <TableBody>
            {visible.map(m => (
              <TableRow key={m.uuid} hover>
                <TableCell sx={{ fontFamily: 'monospace' }}>{m.address}</TableCell>
                <TableCell>{m.hostName || <Typography variant="caption" color="text.disabled">—</Typography>}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace' }}>{m.host}</TableCell>
                <TableCell align="right">{m.port}</TableCell>
                <TableCell>{m.version}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace', fontSize: 11 }}>
                  <Tooltip title={m.uuid}><span>{m.uuid.substring(0, 8)}…</span></Tooltip>
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5}>
                    {m.lite && <Chip size="small" label="lite" />}
                  </Stack>
                </TableCell>
                {hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR') && (
                  <TableCell align="right">
                    <Tooltip title="Member shutdown ships in Phase 4 via the SSH executor">
                      <span><Button size="small" color="warning" disabled>Shutdown</Button></span>
                    </Tooltip>
                  </TableCell>
                )}
              </TableRow>
            ))}
            {visible.length === 0 && (
              <TableRow>
                <TableCell colSpan={hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR') ? 8 : 7} align="center">
                  <Typography variant="body2" color="text.secondary">
                    {members.length === 0 ? 'No members reported yet.' : 'No members match the current filters.'}
                  </Typography>
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Phase-4 placeholder: per-member memory/CPU. Surfaces the architectural gap so
          operators don't think the data is missing by accident. */}
      <Card variant="outlined" sx={{ mb: 3, bgcolor: 'action.hover' }}>
        <CardContent>
          <Typography variant="subtitle2">Per-member memory / CPU — Phase 4</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Hazelcast clients can't read remote-member JVM heap or OS CPU stats over the
            client protocol. This will be surfaced via either an IExecutorService task
            deployed to each member (User Code Deployment) or a direct JMX collector.
            See the README dashboard-plan section for the architectural fork.
          </Typography>
        </CardContent>
      </Card>

      <Typography variant="overline">Event log (most recent first)</Typography>
      <Stack spacing={0.5} sx={{ mt: 1 }}>
        {log.length === 0 && <Typography variant="caption" color="text.secondary">No events yet.</Typography>}
        {log.map((l, i) => (
          <Typography key={i} variant="caption">
            <Chip size="small" label={l.kind} sx={{ mr: 1 }} color={l.kind === 'added' ? 'success' : 'warning'} />
            {new Date(l.at).toLocaleTimeString()} — {l.text}
          </Typography>
        ))}
      </Stack>
    </>
  );
}

function appendLog(set: (fn: (prev: EventLog[]) => EventLog[]) => void, kind: string, text: string) {
  set(prev => [{ at: Date.now(), kind, text }, ...prev].slice(0, 50));
}

function ConnIndicator({ status }: { status: 'connecting' | 'open' | 'closed' | 'error' }) {
  if (status === 'open')        return <Chip size="small" color="success" icon={<CloudDoneIcon />} label="live" />;
  if (status === 'connecting')  return <Chip size="small" color="default" label="connecting…" />;
  if (status === 'error')       return <Chip size="small" color="warning" icon={<CloudOffIcon />} label="reconnecting" />;
  return <Chip size="small" color="error" icon={<CloudOffIcon />} label="closed" />;
}


import { useState } from 'react';
import { Box, Button, Card, CardActionArea, CardContent, Chip, IconButton, Stack, Tooltip, Typography, Dialog, DialogTitle, DialogContent, DialogActions, TextField, MenuItem, Alert, LinearProgress } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';

type ClusterDto = {
  id: number; name: string; environment: string; hzClusterName: string;
  majorVersion: string; memberAddresses: string[]; securityMode: string;
  prod: boolean; enabled: boolean;
};

type HealthStatus = 'HEALTHY' | 'WARN' | 'CRITICAL' | 'DISABLED';
type HealthRow = {
  id: number;
  name: string;
  environment: string;
  majorVersion: string;
  prod: boolean;
  enabled: boolean;
  status: HealthStatus;
  memberCount: number;
  clusterState: string;
  clusterSafe: boolean;
  message: string;
};

export function ClustersPage() {
  const qc = useQueryClient();
  const nav = useNavigate();
  const { hasAnyRole } = useAuth();
  const [open, setOpen] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [form, setForm] = useState({
    name: '', environment: 'DEV', hzClusterName: 'dev', majorVersion: '5.2',
    memberAddresses: '127.0.0.1:5701', securityMode: 'PLAIN', username: '', password: '', prod: false,
  });

  const list = useQuery({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get<ClusterDto[]>('/api/clusters')).data,
  });

  // Multi-cluster health roll-up. Drives the traffic-light circle on each card.
  // Refetches every 15s so the dashboard reflects connect/disconnect events without
  // making the user click anything. Server-side probe is bounded by BridgeRouter's
  // connect budget so this can't hang the UI even if a cluster is unreachable.
  const health = useQuery({
    queryKey: ['clusters', 'health-summary'],
    queryFn: async () => (await api.get<HealthRow[]>('/api/clusters/health-summary')).data,
    refetchInterval: 15_000,
  });
  const healthById = new Map((health.data ?? []).map(h => [h.id, h]));

  const create = useMutation({
    mutationFn: async () => {
      const body = { ...form, memberAddresses: form.memberAddresses.split(',').map(s => s.trim()) };
      return (await api.post('/api/clusters', body)).data;
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['clusters'] }); setOpen(false); setErr(null); },
    onError: (e: any) => setErr(e.response?.data?.message ?? 'Register failed'),
  });

  // Per-cluster latest test-connection result, keyed by cluster id. Cleared by clicking Test
  // again on the same row. We don't persist these — the Alert is a transient confirmation.
  type TestResult = { connected: boolean; clusterState?: string; memberCount?: number; error?: string; ts: number };
  const [testResults, setTestResults] = useState<Record<number, TestResult>>({});

  const test = useMutation({
    mutationFn: async (id: number) => {
      const data = (await api.post(`/api/clusters/${id}/test-connection`)).data;
      return { id, data };
    },
    onSuccess: ({ id, data }) => {
      setTestResults(prev => ({ ...prev, [id]: { ...data, ts: Date.now() } }));
    },
    onError: (e: any, id) => {
      // 503 CLUSTER_UNREACHABLE / 401 / 5xx — surface server message verbatim
      setTestResults(prev => ({
        ...prev,
        [id]: { connected: false, error: e.response?.data?.message ?? e.message, ts: Date.now() },
      }));
    },
  });

  const remove = useMutation({
    mutationFn: async (args: { id: number; reason: string }) =>
      (await api.delete(`/api/clusters/${args.id}`, { params: { reason: args.reason } })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['clusters'] }),
    onError: (e: any) => alert('Delete failed: ' + (e.response?.data?.message ?? e.message)),
  });

  return (
    <>
      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
        <Typography variant="h4">Clusters</Typography>
        {hasAnyRole('SUPER_ADMIN', 'CLUSTER_OPERATOR') && (
          <Button variant="contained" onClick={() => setOpen(true)}>Register cluster</Button>
        )}
      </Stack>

      {health.isLoading && <LinearProgress sx={{ mb: 2 }} />}

      <Box sx={{
        display: 'grid',
        gap: 2,
        gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(3, 1fr)' },
      }}>
        {(list.data ?? []).map(c => {
          const h = healthById.get(c.id);
          const status: HealthStatus = h?.status ?? (c.enabled ? 'CRITICAL' : 'DISABLED');
          return (
            <Card key={c.id} variant="outlined" sx={{ position: 'relative' }}>
              {/* Whole card is the primary action — single click to drill into overview. */}
              <CardActionArea onClick={() => nav(`/clusters/${c.id}`)} sx={{ p: 2 }}>
                <Stack direction="row" alignItems="flex-start" spacing={2}>
                  <HealthCircle status={status} />
                  <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                    <Stack direction="row" alignItems="center" spacing={1}>
                      <Typography variant="h6" noWrap title={c.name}>{c.name}</Typography>
                      {c.prod && <Chip size="small" color="warning" label="PROD" />}
                    </Stack>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                      {c.environment} · Hazelcast {c.majorVersion}.x
                    </Typography>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                      {c.memberAddresses.join(', ')}
                    </Typography>

                    <Stack direction="row" spacing={1} sx={{ mt: 1.5 }} flexWrap="wrap">
                      <Chip size="small" variant="outlined"
                            label={`${h?.memberCount ?? '—'} members`} />
                      <Chip size="small" variant="outlined"
                            label={`state: ${h?.clusterState ?? '—'}`} />
                      <Chip size="small" variant="outlined" label={c.securityMode} />
                      {!c.enabled && <Chip size="small" label="disabled" />}
                    </Stack>

                    {h?.message && (
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                        {h.message}
                      </Typography>
                    )}
                  </Box>
                </Stack>
              </CardActionArea>

              {/* Action row outside the CardActionArea so clicks don't bubble into nav. */}
              <CardContent sx={{ pt: 0, pb: 1.5 }}>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  <Button size="small" disabled={test.isPending && test.variables === c.id}
                          onClick={(e) => { e.stopPropagation(); test.mutate(c.id); }}>
                    {test.isPending && test.variables === c.id ? 'Testing…' : 'Test'}
                  </Button>
                  <Button size="small" onClick={(e) => { e.stopPropagation(); nav(`/clusters/${c.id}/members`); }}>Members</Button>
                  <Button size="small" onClick={(e) => { e.stopPropagation(); nav(`/clusters/${c.id}/metrics`); }}>Metrics</Button>
                  <Button size="small" onClick={(e) => { e.stopPropagation(); nav(`/clusters/${c.id}/maps`); }}>Maps</Button>
                  <Button size="small" onClick={(e) => { e.stopPropagation(); nav(`/clusters/${c.id}/sql`); }}>SQL</Button>
                  {hasAnyRole('SUPER_ADMIN') && (
                    <Box onClick={(e) => e.stopPropagation()}>
                      <DeleteClusterButton
                        cluster={c}
                        onConfirm={(reason) => remove.mutate({ id: c.id, reason })}
                        pending={remove.isPending}
                      />
                    </Box>
                  )}
                </Stack>

                {testResults[c.id] && (
                  <Alert
                    severity={testResults[c.id].connected ? 'success' : 'error'}
                    sx={{ mt: 1.5 }}
                    onClose={() => setTestResults(prev => {
                      const next = { ...prev };
                      delete next[c.id];
                      return next;
                    })}
                  >
                    {testResults[c.id].connected ? (
                      <>Connected · state {testResults[c.id].clusterState ?? 'unknown'} · {testResults[c.id].memberCount ?? 0} members</>
                    ) : (
                      <>Could not connect: {testResults[c.id].error ?? 'unknown error'}</>
                    )}
                  </Alert>
                )}
              </CardContent>
            </Card>
          );
        })}
      </Box>

      {list.data?.length === 0 && (
        <Typography color="text.secondary" sx={{ mt: 2 }}>
          No clusters registered yet. Click "Register cluster" to add your first Hazelcast 4.x or 5.x cluster.
        </Typography>
      )}

      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Register Hazelcast cluster</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Display name" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
            <TextField label="Environment" value={form.environment} onChange={e => setForm({ ...form, environment: e.target.value })} />
            <TextField label="Hazelcast cluster name (group)" value={form.hzClusterName} onChange={e => setForm({ ...form, hzClusterName: e.target.value })} />
            <TextField select label="Major version" value={form.majorVersion} onChange={e => setForm({ ...form, majorVersion: e.target.value })}>
              <MenuItem value="4.2">4.2 (uses hz-bridge-4x)</MenuItem>
              <MenuItem value="5.1">5.1 (uses hz-bridge-5x)</MenuItem>
              <MenuItem value="5.2">5.2 (uses hz-bridge-5x)</MenuItem>
            </TextField>
            <TextField label="Member addresses (comma-separated host:port)" value={form.memberAddresses} onChange={e => setForm({ ...form, memberAddresses: e.target.value })} />
            <TextField select label="Security mode" value={form.securityMode} onChange={e => setForm({ ...form, securityMode: e.target.value })}>
              <MenuItem value="PLAIN">PLAIN (dev)</MenuItem>
              <MenuItem value="TLS">TLS</MenuItem>
              <MenuItem value="MTLS">MTLS</MenuItem>
            </TextField>
            <TextField label="Username (optional)" value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} />
            <TextField label="Password (optional)" type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} />
            {err && <Alert severity="error">{err}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => create.mutate()} disabled={create.isPending}>Register</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

/**
 * Inline cluster-delete control. Three states:
 *   1. resting   → red "Delete" button.
 *   2. armed     → reason TextField + confirm/cancel icons. For PROD clusters
 *                  we ALSO require the user to type the cluster name verbatim,
 *                  matching common patterns (GitHub, Stripe, etc.) to make
 *                  destructive ops on production hard to do by reflex.
 *   3. pending   → mutation is in flight; controls disabled.
 *
 * Caller passes the cluster (so we can read .prod / .name) and the confirm
 * callback. Reason is required (>= 3 chars) and forwarded to the audit row
 * via the ?reason= query param the controller now accepts.
 */
function DeleteClusterButton({
  cluster, onConfirm, pending,
}: {
  cluster: { id: number; name: string; prod: boolean };
  onConfirm: (reason: string) => void;
  pending: boolean;
}) {
  const [armed, setArmed]   = useState(false);
  const [reason, setReason] = useState('');
  const [typed, setTyped]   = useState('');

  const reasonOk = reason.trim().length >= 3 && reason.trim().length <= 1000;
  const nameOk   = !cluster.prod || typed === cluster.name;
  const canFire  = reasonOk && nameOk && !pending;

  if (!armed) {
    return (
      <Button size="small" color="error" disabled={pending}
              onClick={() => setArmed(true)}>
        Delete
      </Button>
    );
  }
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <TextField size="small" placeholder="Reason (required)" value={reason}
                 onChange={e => setReason(e.target.value)} sx={{ width: 220 }} />
      {cluster.prod && (
        <TextField size="small" placeholder={`Type "${cluster.name}" to confirm`}
                   value={typed} onChange={e => setTyped(e.target.value)}
                   sx={{ width: 220 }} error={typed.length > 0 && typed !== cluster.name} />
      )}
      <IconButton size="small" color="error" disabled={!canFire}
                  title={cluster.prod && !nameOk ? 'Cluster name must match' : 'Confirm delete'}
                  onClick={() => {
                    onConfirm(reason.trim());
                    setArmed(false); setReason(''); setTyped('');
                  }}>
        ✓
      </IconButton>
      <IconButton size="small"
                  onClick={() => { setArmed(false); setReason(''); setTyped(''); }}>
        ✕
      </IconButton>
    </Stack>
  );
}

/**
 * Traffic-light circle for cluster health. Colour mapping:
 *   HEALTHY  → success.main  (green)
 *   WARN     → warning.main  (amber)
 *   CRITICAL → error.main    (red)
 *   DISABLED → action.disabled (grey)
 *
 * The circle pulses gently when CRITICAL so it grabs the eye on a dashboard with
 * many clusters. Tooltip carries the human-readable reason from the health probe.
 */
function HealthCircle({ status }: { status: HealthStatus }) {
  const colour =
    status === 'HEALTHY'  ? 'success.main'  :
    status === 'WARN'     ? 'warning.main'  :
    status === 'CRITICAL' ? 'error.main'    :
                            'action.disabled';
  const label =
    status === 'HEALTHY'  ? 'Healthy: cluster reachable, partitions safe.' :
    status === 'WARN'     ? 'Degraded: reachable but in transition / single-member / partitions not yet safe.' :
    status === 'CRITICAL' ? 'Critical: cluster unreachable or no members.' :
                            'Disabled: cluster registration is turned off.';
  return (
    <Tooltip title={label} arrow>
      <Box sx={{
        width: 18, height: 18, mt: 0.5, borderRadius: '50%',
        bgcolor: colour, flexShrink: 0,
        boxShadow: status === 'CRITICAL' ? '0 0 0 4px rgba(255,0,0,0.15)' : 'none',
        animation: status === 'CRITICAL' ? 'pulse 1.6s ease-in-out infinite' : 'none',
        '@keyframes pulse': {
          '0%, 100%': { transform: 'scale(1)',   opacity: 1 },
          '50%':      { transform: 'scale(1.1)', opacity: 0.85 },
        },
      }} />
    </Tooltip>
  );
}

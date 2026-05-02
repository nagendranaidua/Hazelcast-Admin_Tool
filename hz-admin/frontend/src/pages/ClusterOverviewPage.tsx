import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, CardContent, Chip, Stack, Typography, Alert, Grid, Button, LinearProgress, Divider, Box,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

type Member = {
  uuid: string;
  address: string;
  lite: boolean;
  version: string;
  attributes?: Record<string, string>;
};
type Overview = {
  id: number;
  name: string;
  environment: string;
  majorVersion: string;
  prod: boolean;
  connected: boolean;
  error?: string;
  clusterName?: string;
  clusterState?: string;
  clientVersion?: string;
  memberCount?: number;
  partitionCount?: number;
  clusterSafe?: boolean;
  members?: Member[];
};

const stateColor: Record<string, 'success' | 'warning' | 'error' | 'default'> = {
  ACTIVE: 'success',
  NO_MIGRATION: 'warning',
  FROZEN: 'warning',
  PASSIVE: 'error',
};

export function ClusterOverviewPage() {
  const { id } = useParams();
  const nav = useNavigate();

  const q = useQuery<Overview>({
    queryKey: ['cluster-overview', id],
    queryFn: async () => (await api.get(`/api/clusters/${id}/overview`)).data,
    refetchInterval: 10_000,
  });

  if (q.isLoading) return <LinearProgress />;

  const o = q.data;
  if (!o) return <Alert severity="error">Failed to load cluster overview.</Alert>;

  return (
    <>
      <Stack direction="row" spacing={2} alignItems="baseline" sx={{ mb: 2 }}>
        <Typography variant="h4">{o.name}</Typography>
        <Chip size="small" label={o.environment} />
        {o.prod && <Chip size="small" color="error" label="PROD" />}
        <Chip size="small" label={`Hazelcast ${o.majorVersion}.x`} />
        <Box sx={{ flexGrow: 1 }} />
        <Button size="small" variant="outlined" onClick={() => nav(`/clusters/${id}/members`)}>Members</Button>
        <Button size="small" variant="outlined" onClick={() => nav(`/clusters/${id}/maps`)}>Maps</Button>
        <Button size="small" variant="outlined" onClick={() => nav(`/clusters/${id}/queues`)}>Queues</Button>
        <Button size="small" variant="outlined" onClick={() => nav(`/clusters/${id}/topics`)}>Topics</Button>
      </Stack>

      {!o.connected && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Not connected: {o.error ?? 'unknown error'}
        </Alert>
      )}

      {o.connected && (
        <>
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <StatCard title="Cluster state" value={o.clusterState ?? '-'} color={stateColor[o.clusterState ?? ''] ?? 'default'} />
            <StatCard title="Members" value={String(o.memberCount ?? '-')} />
            <StatCard title="Partitions" value={String(o.partitionCount ?? '-')} />
            <StatCard
              title="Migration safe"
              value={o.clusterSafe ? 'YES' : 'NO'}
              color={o.clusterSafe ? 'success' : 'warning'}
            />
          </Grid>

          <Card sx={{ mb: 2 }}>
            <CardContent>
              <Typography variant="overline" color="text.secondary">Client</Typography>
              <Stack direction="row" spacing={2} sx={{ mt: 1 }} alignItems="center" flexWrap="wrap">
                <Typography variant="body2">cluster.name: <b>{o.clusterName}</b></Typography>
                <Divider orientation="vertical" flexItem />
                <Typography variant="body2">client lib: <b>{o.clientVersion}</b></Typography>
              </Stack>
            </CardContent>
          </Card>

          <Typography variant="h6" sx={{ mb: 1 }}>Members ({o.members?.length ?? 0})</Typography>
          <Stack spacing={1}>
            {(o.members ?? []).map(m => (
              <Card key={m.uuid}><CardContent sx={{ py: 1.5 }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  <div>
                    <Typography variant="body1">{m.address}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {m.uuid} · v{m.version}{m.lite ? ' · lite' : ''}
                    </Typography>
                  </div>
                </Stack>
              </CardContent></Card>
            ))}
          </Stack>
        </>
      )}
    </>
  );
}

function StatCard({ title, value, color }: { title: string; value: string; color?: 'success' | 'warning' | 'error' | 'default' }) {
  return (
    <Grid item xs={6} md={3}>
      <Card>
        <CardContent>
          <Typography variant="overline" color="text.secondary">{title}</Typography>
          <Typography variant="h5" sx={{ mt: 0.5 }}>
            {color && color !== 'default' ? <Chip label={value} color={color} /> : value}
          </Typography>
        </CardContent>
      </Card>
    </Grid>
  );
}

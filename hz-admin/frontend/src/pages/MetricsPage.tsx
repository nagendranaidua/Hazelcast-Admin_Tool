import { useParams } from 'react-router-dom';
import { useMemo, useState } from 'react';
import {
  Card, CardContent, Typography, Stack, ToggleButtonGroup, ToggleButton, Alert, Box, LinearProgress, Chip,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import {
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
} from 'recharts';
import { api } from '../api/client';

type Series = {
  count: number;
  ts: number[];
  memberCount: number[];
  partitionCount: number[];
  clusterSafe: boolean[];
  connected: boolean[];
  bridgeLatencyMs: number[];
  clusterState: (string | null)[];
};

type Window = '5m' | '15m' | '1h';

export function MetricsPage() {
  const { id } = useParams();
  const [win, setWin] = useState<Window>('15m');

  const fromMs = useMemo(() => {
    const now = Date.now();
    if (win === '5m')  return now - 5  * 60_000;
    if (win === '15m') return now - 15 * 60_000;
    return now - 60 * 60_000;
  }, [win]);

  const q = useQuery<Series>({
    queryKey: ['cluster-metrics', id, win],
    queryFn: async () => (await api.get(`/api/clusters/${id}/metrics`, { params: { from: fromMs } })).data,
    refetchInterval: 15_000,
  });

  if (q.isLoading) return <LinearProgress />;
  if (q.error)     return <Alert severity="error">Failed to load metrics.</Alert>;

  const s = q.data;
  if (!s || s.count === 0) {
    return (
      <>
        <Header win={win} setWin={setWin} />
        <Alert severity="info" sx={{ mt: 2 }}>
          No samples collected yet. The collector polls every 15s — wait a moment and refresh.
          {' '}If this persists, the cluster may not be reachable; check the Overview page.
        </Alert>
      </>
    );
  }

  // Reshape columnar response into row-form for recharts
  const rows = s.ts.map((ts, i) => ({
    ts,
    label:           new Date(ts).toLocaleTimeString(),
    memberCount:     s.memberCount[i],
    partitionCount:  s.partitionCount[i],
    bridgeLatencyMs: s.bridgeLatencyMs[i],
    connected:       s.connected[i] ? 1 : 0,
    safe:            s.clusterSafe[i] ? 1 : 0,
  }));

  const lastSafe       = s.clusterSafe[s.count - 1];
  const lastConnected  = s.connected[s.count - 1];
  const downtimeRatio  = s.connected.filter(c => !c).length / s.count;

  return (
    <>
      <Header win={win} setWin={setWin} />

      <Stack direction="row" spacing={1} sx={{ mb: 2 }}>
        <Chip
          label={lastConnected ? 'connected' : 'unreachable'}
          color={lastConnected ? 'success' : 'error'}
          size="small"
        />
        <Chip
          label={lastSafe ? 'partitions: safe' : 'partitions: migrating'}
          color={lastSafe ? 'success' : 'warning'}
          size="small"
        />
        <Chip
          label={`uptime in window: ${(100 * (1 - downtimeRatio)).toFixed(1)}%`}
          size="small"
        />
        <Chip label={`${s.count} samples`} size="small" />
      </Stack>

      <ChartCard title="Member count">
        <LineChart data={rows} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend />
          <Line type="stepAfter" dataKey="memberCount" stroke="#1976d2" dot={false} isAnimationActive={false} />
        </LineChart>
      </ChartCard>

      <ChartCard title="Bridge round-trip latency (ms)">
        <LineChart data={rows} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend />
          <Line type="monotone" dataKey="bridgeLatencyMs" stroke="#9c27b0" dot={false} isAnimationActive={false} />
        </LineChart>
      </ChartCard>

      <ChartCard title="Connectivity (1 = up, 0 = down)">
        <LineChart data={rows} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" tick={{ fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis ticks={[0, 1]} domain={[0, 1]} tick={{ fontSize: 11 }} />
          <Tooltip />
          <Legend />
          <Line type="stepAfter" dataKey="connected" stroke="#2e7d32" dot={false} isAnimationActive={false} />
          <Line type="stepAfter" dataKey="safe"      stroke="#ed6c02" dot={false} isAnimationActive={false} />
        </LineChart>
      </ChartCard>
    </>
  );
}

function Header({ win, setWin }: { win: Window; setWin: (w: Window) => void }) {
  return (
    <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 2 }}>
      <Typography variant="h4">Metrics</Typography>
      <Box sx={{ flexGrow: 1 }} />
      <ToggleButtonGroup
        size="small"
        value={win}
        exclusive
        onChange={(_, v) => v && setWin(v)}
      >
        <ToggleButton value="5m">5m</ToggleButton>
        <ToggleButton value="15m">15m</ToggleButton>
        <ToggleButton value="1h">1h</ToggleButton>
      </ToggleButtonGroup>
    </Stack>
  );
}

function ChartCard({ title, children }: { title: string; children: React.ReactElement }) {
  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Typography variant="overline" color="text.secondary">{title}</Typography>
        <Box sx={{ width: '100%', height: 220, mt: 1 }}>
          <ResponsiveContainer>{children}</ResponsiveContainer>
        </Box>
      </CardContent>
    </Card>
  );
}

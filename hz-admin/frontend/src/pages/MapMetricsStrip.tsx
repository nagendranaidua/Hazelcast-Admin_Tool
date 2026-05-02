import { useQuery } from '@tanstack/react-query';
import { Box, Card, CardContent, Stack, Typography, Skeleton } from '@mui/material';
import { Area, AreaChart, ResponsiveContainer, Tooltip as RechartsTooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api/client';

/**
 * Strip of four mini-charts shown on the map browse page: Entries, Operations/s, Memory,
 * Backups. Mirrors the layout in the Hazelcast MC screenshot the user shared.
 *
 * <p>Data comes from /api/clusters/{id}/maps/{name}/metrics — populated by the periodic
 * MetricsCollector.sampleMaps tick (default every 30s, cumulative counters with derived
 * per-second arrays). Refetches every 15s so the chart catches up shortly after each
 * collector tick. If no samples have been collected yet, each card shows a skeleton — the
 * collector usually fills in within ~30s of opening the page.
 *
 * <p>Memory cards format bytes adaptively (KB/MB/GB). Operations/s rounds to 1 decimal.
 */
type Series = {
  count: number;
  ts: number[];
  reachable: boolean[];
  entries: number[];
  backupEntries: number[];
  memoryBytes: number[];
  backupMemoryBytes: number[];
  hits: number[];
  lockedEntries: number[];
  dirtyEntries: number[];
  totalOpsPerSec: number[];
  getOpsPerSec: number[];
  putOpsPerSec: number[];
  removeOpsPerSec: number[];
};

export function MapMetricsStrip({ clusterId, mapName }: { clusterId: number | string; mapName: string }) {
  const q = useQuery<Series>({
    queryKey: ['map-metrics', clusterId, mapName],
    queryFn: async () => (await api.get(
      `/api/clusters/${clusterId}/maps/${encodeURIComponent(mapName)}/metrics`)).data,
    refetchInterval: 15_000,
  });

  if (q.isLoading || !q.data) {
    return (
      <Stack direction="row" spacing={2} sx={{ mb: 2 }}>
        {[1,2,3,4].map(i => <Skeleton key={i} variant="rounded" width={250} height={130} />)}
      </Stack>
    );
  }

  const s = q.data;
  if (s.count === 0) {
    return (
      <Card variant="outlined" sx={{ mb: 2, bgcolor: 'action.hover' }}>
        <CardContent>
          <Typography variant="body2" color="text.secondary">
            No samples collected for this map yet. The metrics collector samples maps every
            30s by default; the strip will populate within one tick.
          </Typography>
        </CardContent>
      </Card>
    );
  }

  // Build chart-ready row arrays. Recharts wants [{ts, value}, ...] per series.
  const rows = (key: keyof Series) => s.ts.map((t, i) => ({ ts: t, v: (s[key] as number[])[i] }));

  const cards: Array<{ title: string; data: { ts: number; v: number }[]; format: (v: number) => string; colour: string; }> = [
    { title: 'Entries',         data: rows('entries'),        format: (v) => v.toLocaleString(),                   colour: '#1976d2' },
    { title: 'Operations/s',    data: rows('totalOpsPerSec'), format: (v) => v.toFixed(1),                         colour: '#388e3c' },
    { title: 'Memory',          data: rows('memoryBytes'),    format: fmtBytes,                                    colour: '#f57c00' },
    { title: 'Backups',         data: rows('backupEntries'),  format: (v) => v.toLocaleString(),                   colour: '#7b1fa2' },
  ];

  return (
    <Box sx={{
      display: 'grid', gap: 2, mb: 2,
      gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' },
    }}>
      {cards.map(c => (
        <Card key={c.title} variant="outlined">
          <CardContent sx={{ pb: '12px !important' }}>
            <Stack direction="row" justifyContent="space-between" alignItems="baseline">
              <Typography variant="overline">{c.title}</Typography>
              <Typography variant="h6">
                {c.data.length ? c.format(c.data[c.data.length - 1].v) : '—'}
              </Typography>
            </Stack>
            <Box sx={{ height: 80, mt: 1 }}>
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={c.data} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
                  <defs>
                    <linearGradient id={`grad-${c.title}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={c.colour} stopOpacity={0.4} />
                      <stop offset="100%" stopColor={c.colour} stopOpacity={0.05} />
                    </linearGradient>
                  </defs>
                  <XAxis dataKey="ts" hide />
                  <YAxis hide domain={['dataMin', 'auto']} />
                  <RechartsTooltip
                    labelFormatter={(t: number) => new Date(t).toLocaleTimeString()}
                    formatter={(v: number) => [c.format(v), c.title]}
                  />
                  <Area type="monotone" dataKey="v"
                        stroke={c.colour} strokeWidth={2}
                        fill={`url(#grad-${c.title})`} isAnimationActive={false} />
                </AreaChart>
              </ResponsiveContainer>
            </Box>
            <Typography variant="caption" color="text.secondary">
              {c.data.length} samples · {fmtAge(s.ts[0], s.ts[s.ts.length - 1])}
            </Typography>
          </CardContent>
        </Card>
      ))}
    </Box>
  );
}

function fmtBytes(n: number): string {
  if (!n || n < 0) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0; let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${u[i]}`;
}

function fmtAge(firstTs: number, lastTs: number): string {
  const ms = lastTs - firstTs;
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `last ${sec}s`;
  const min = Math.round(sec / 60);
  if (min < 60) return `last ${min}m`;
  return `last ${(min / 60).toFixed(1)}h`;
}

import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Box, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useFilterSort } from '../lib/useFilterSort';
import { FilterCell, SortableHeader } from '../lib/TableBits';
import CampaignIcon from '@mui/icons-material/Campaign';

type TopicSummary = {
  name: string;
};

/**
 * ITopics overview for a cluster. Lists all topics; click a row to drill into
 * the TopicDetailPage where you can publish messages and listen in real time.
 */
export function TopicsPage() {
  const { id } = useParams();
  const nav = useNavigate();

  const q = useQuery<TopicSummary[]>({
    queryKey: ['cluster-topics-summary', id],
    queryFn: async () => (await api.get<TopicSummary[]>(`/api/clusters/${id}/topics/summary`)).data,
    refetchInterval: 30_000,
  });

  const cols = [
    { key: 'name', accessor: (r: TopicSummary) => r.name },
  ];
  const { visible, filters, setFilter, sort, toggleSort } = useFilterSort<TopicSummary>(q.data ?? [], cols, 'name');

  return (
    <>
      <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 2 }}>
        <CampaignIcon color="primary" sx={{ fontSize: 32 }} />
        <Typography variant="h4">Topics</Typography>
        <Box sx={{ flexGrow: 1 }} />
        <Chip size="small" label={`${visible.length} of ${q.data?.length ?? 0}`} />
        <Button size="small" variant="outlined" onClick={() => nav(`/clusters/${id}/sql`)}>SQL console</Button>
      </Stack>

      {q.isLoading && <LinearProgress sx={{ mb: 2 }} />}
      {q.error && <Alert severity="error" sx={{ mb: 2 }}>Failed to list topics. The cluster may be unreachable.</Alert>}

      {q.data && q.data.length === 0 && (
        <Paper
          variant="outlined"
          sx={{
            textAlign: 'center', py: 8, px: 4,
            borderStyle: 'dashed', borderColor: 'divider',
            backgroundColor: 'action.hover', borderRadius: 3,
          }}
        >
          <CampaignIcon sx={{ fontSize: 72, color: 'text.disabled', mb: 2 }} />
          <Typography variant="h5" color="text.secondary" gutterBottom>
            No ITopics found on this cluster
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ maxWidth: 520, mx: 'auto' }}>
            Topics appear here once created from any Hazelcast client. Unlike maps and queues,
            topics are pub/sub and do not retain messages — they are fire-and-forget.
          </Typography>
        </Paper>
      )}

      {q.data && q.data.length > 0 && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow sx={{ bgcolor: 'action.hover' }}>
                <SortableHeader label="Topic Name" col="name" sort={sort} onClick={toggleSort} />
                <TableCell sx={{ fontWeight: 600 }}>Type</TableCell>
                <TableCell />
              </TableRow>
              <TableRow>
                <FilterCell value={filters.name ?? ''} onChange={v => setFilter('name', v)} />
                <TableCell />
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {visible.map(r => (
                <TableRow key={r.name} hover sx={{ cursor: 'pointer' }}
                          onClick={() => nav(`/clusters/${id}/topics/${encodeURIComponent(r.name)}`)}>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{r.name}</TableCell>
                  <TableCell>
                    <Chip size="small" variant="outlined" label="ITopic" color="secondary" />
                  </TableCell>
                  <TableCell align="right">
                    <Button size="small" onClick={(e) => { e.stopPropagation();
                      nav(`/clusters/${id}/topics/${encodeURIComponent(r.name)}`); }}>
                      Publish / Listen →
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {visible.length === 0 && (
                <TableRow>
                  <TableCell colSpan={3} align="center">
                    <Typography variant="body2" color="text.secondary">
                      No topics match the current filters.
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

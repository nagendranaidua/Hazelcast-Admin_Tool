import { Card, CardContent, Grid, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';

type ClusterDto = { id: number; name: string; environment: string; majorVersion: string; enabled: boolean };

export function DashboardPage() {
  const { me } = useAuth();
  const q = useQuery({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get<ClusterDto[]>('/api/clusters')).data,
  });

  return (
    <>
      <Typography variant="h4" gutterBottom>Welcome, {me?.fullName || me?.username}</Typography>
      <Typography color="text.secondary" sx={{ mb: 3 }}>
        Phase 1 verification dashboard. Register a Hazelcast 5.x cluster on the Clusters page,
        then drill into Members, Maps, or Ops based on your role.
      </Typography>
      <Grid container spacing={2}>
        <Grid item xs={12} md={4}>
          <Card><CardContent>
            <Typography variant="overline">Registered clusters</Typography>
            <Typography variant="h3">{q.data?.length ?? '—'}</Typography>
          </CardContent></Card>
        </Grid>
        <Grid item xs={12} md={4}>
          <Card><CardContent>
            <Typography variant="overline">Your roles</Typography>
            <Typography variant="h6">{me?.roles?.map(r => r.replace('ROLE_','')).join(', ')}</Typography>
          </CardContent></Card>
        </Grid>
        <Grid item xs={12} md={4}>
          <Card><CardContent>
            <Typography variant="overline">Auth source</Typography>
            <Typography variant="h6">{me?.authSource}</Typography>
          </CardContent></Card>
        </Grid>
      </Grid>
    </>
  );
}

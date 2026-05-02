import { useNavigate } from 'react-router-dom';
import { Box, Button, Card, CardContent, Grid, Typography, Paper } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import LinkIcon from '@mui/icons-material/Link';
import { useThemeMode } from '../ThemeModeProvider';

export function DashboardPage() {
  const { me } = useAuth();
  const { mode } = useThemeMode();
  const navigate = useNavigate();

  const clustersQuery = useQuery({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get('/api/clusters')).data,
  });

  const clusters = clustersQuery.data || [];

  return (
    <>
      <Typography
        variant="h4"
        gutterBottom
        sx={{
          fontWeight: 700,
          background: 'linear-gradient(135deg, #7C3AED 0%, #F97316 100%)',
          backgroundClip: 'text',
          WebkitBackgroundClip: 'text',
          WebkitTextFillColor: 'transparent',
        }}
      >
        Welcome, {me?.fullName || me?.username}
      </Typography>
      <Typography color="text.secondary" sx={{ mb: 4, fontWeight: 500 }}>
        {clusters.length === 0
          ? 'No clusters configured yet. Add your first Hazelcast cluster to get started.'
          : `You have ${clusters.length} cluster${clusters.length !== 1 ? 's' : ''} configured.`}
      </Typography>

      {clusters.length === 0 ? (
        // Empty State
        <Paper
          elevation={0}
          sx={{
            p: 6,
            textAlign: 'center',
            borderRadius: 3,
            border: '2px dashed',
            borderColor: 'primary.main',
            backgroundColor: mode === 'dark'
              ? 'rgba(124, 58, 237, 0.05)'
              : 'rgba(124, 58, 237, 0.08)',
          }}
        >
          <Box
            sx={{
              display: 'flex',
              justifyContent: 'center',
              mb: 3,
            }}
          >
            <Box
              sx={{
                width: 120,
                height: 120,
                borderRadius: '50%',
                backgroundColor: mode === 'dark'
                  ? 'rgba(124, 58, 237, 0.15)'
                  : 'rgba(124, 58, 237, 0.1)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <LinkIcon sx={{ fontSize: 60, color: 'primary.main', opacity: 0.6 }} />
            </Box>
          </Box>

          <Typography variant="h5" sx={{ fontWeight: 700, mb: 1 }}>
            Add Cluster Config
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 3, maxWidth: 400, mx: 'auto' }}>
            There is no cluster configured yet. Add a new cluster configuration to start monitoring and managing
            your Hazelcast instances.
          </Typography>

          <Button
            variant="contained"
            size="large"
            onClick={() => navigate('/clusters')}
            sx={{
              background: 'linear-gradient(135deg, #7C3AED 0%, #F97316 100%)',
              px: 4,
              py: 1.5,
              fontWeight: 700,
            }}
          >
            Add Cluster Config
          </Button>
        </Paper>
      ) : (
        // Clusters Summary
        <Grid container spacing={3}>
          <Grid item xs={12} md={4}>
            <Card
              sx={{
                transition: 'all 0.3s ease',
                '&:hover': {
                  transform: 'translateY(-4px)',
                  boxShadow: mode === 'dark'
                    ? '0 10px 30px rgba(167, 139, 250, 0.2)'
                    : '0 10px 30px rgba(124, 58, 237, 0.15)',
                },
              }}
            >
              <CardContent>
                <Typography variant="overline" color="text.secondary" sx={{ fontWeight: 600 }}>
                  Registered Clusters
                </Typography>
                <Typography
                  variant="h3"
                  sx={{
                    fontWeight: 700,
                    background: 'linear-gradient(135deg, #7C3AED 0%, #F97316 100%)',
                    backgroundClip: 'text',
                    WebkitBackgroundClip: 'text',
                    WebkitTextFillColor: 'transparent',
                  }}
                >
                  {clusters.length}
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          <Grid item xs={12} md={4}>
            <Card
              sx={{
                transition: 'all 0.3s ease',
                '&:hover': {
                  transform: 'translateY(-4px)',
                  boxShadow: mode === 'dark'
                    ? '0 10px 30px rgba(167, 139, 250, 0.2)'
                    : '0 10px 30px rgba(124, 58, 237, 0.15)',
                },
              }}
            >
              <CardContent>
                <Typography variant="overline" color="text.secondary" sx={{ fontWeight: 600 }}>
                  Your Roles
                </Typography>
                <Typography variant="h6" sx={{ fontWeight: 700 }}>
                  {me?.roles?.map(r => r.replace('ROLE_', '')).join(', ')}
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          <Grid item xs={12} md={4}>
            <Card
              sx={{
                transition: 'all 0.3s ease',
                '&:hover': {
                  transform: 'translateY(-4px)',
                  boxShadow: mode === 'dark'
                    ? '0 10px 30px rgba(167, 139, 250, 0.2)'
                    : '0 10px 30px rgba(124, 58, 237, 0.15)',
                },
              }}
            >
              <CardContent>
                <Typography variant="overline" color="text.secondary" sx={{ fontWeight: 600 }}>
                  Auth Source
                </Typography>
                <Typography variant="h6" sx={{ fontWeight: 700 }}>
                  {me?.authSource}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      )}
    </>
  );
}


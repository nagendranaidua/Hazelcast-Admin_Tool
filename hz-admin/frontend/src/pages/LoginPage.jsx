import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  TextField,
  Button,
  Typography,
  Alert,
  Stack,
  FormControlLabel,
  Checkbox,
  Container,
} from '@mui/material';
import HazelcastLogo from '../assets/HazelcastLogo';
import { useAuth } from '../auth/AuthContext';
import { useThemeMode } from '../ThemeModeProvider';

export default function LoginPage() {
  const { login } = useAuth();
  const { mode } = useThemeMode();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const me = await login(username, password);
      const destination = me.mustChangePassword ? '/change-password' : (location.state?.from?.pathname ?? '/');
      navigate(destination, { replace: true });
    } catch (ex) {
      setError(ex.response?.data?.message ?? 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: mode === 'dark'
          ? 'linear-gradient(135deg, #23235B 0%, #7C3AED 100%)'
          : 'linear-gradient(135deg, #F8FAFC 0%, #F1F5F9 50%, #F97316 100%)',
        p: 2,
      }}
    >
      <Container maxWidth="sm">
        <Card
          sx={{
            boxShadow: '0 20px 60px rgba(0, 0, 0, 0.3)',
            borderRadius: 3,
            overflow: 'hidden',
            border: mode === 'dark'
              ? '1px solid rgba(167, 139, 250, 0.2)'
              : '1px solid rgba(124, 58, 237, 0.1)',
          }}
        >
          {/* Header with logo and gradient */}
          <Box
            sx={{
              background: 'linear-gradient(135deg, #7C3AED 0%, #F97316 100%)',
              height: 120,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 2,
            }}
          >
            <Box sx={{ mr: 2 }}>
              <HazelcastLogo width={50} height={50} />
            </Box>
            <Box>
              <Typography variant="h5" sx={{ color: '#FFFFFF', fontWeight: 700 }}>
                Hazelcast Admin
              </Typography>
              <Typography variant="caption" sx={{ color: 'rgba(255, 255, 255, 0.8)' }}>
                Management Center
              </Typography>
            </Box>
          </Box>

          {/* Form */}
          <CardContent sx={{ p: 4 }}>
            <Typography variant="h6" gutterBottom sx={{ fontWeight: 700, mb: 1 }}>
              Welcome Back
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
              Sign in to manage your Hazelcast clusters.
            </Typography>

            <form onSubmit={handleSubmit}>
              <Stack spacing={2.5}>
                <TextField
                  label="Username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  disabled={loading}
                  autoFocus
                  required
                  fullWidth
                  variant="outlined"
                  size="medium"
                />

                <TextField
                  label="Password"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={loading}
                  required
                  fullWidth
                  variant="outlined"
                  size="medium"
                />

                <FormControlLabel
                  control={
                    <Checkbox
                      checked={rememberMe}
                      onChange={(e) => setRememberMe(e.target.checked)}
                      disabled={loading}
                    />
                  }
                  label="Remember me on this device"
                />

                {error && <Alert severity="error">{error}</Alert>}

                <Button
                  type="submit"
                  variant="contained"
                  size="large"
                  disabled={loading || !username || !password}
                  fullWidth
                  sx={{ py: 1.5, fontWeight: 700 }}
                >
                  {loading ? 'Signing in…' : 'Sign in'}
                </Button>

                <Typography variant="caption" color="text.secondary">
                  First time? Check README.md for default credentials.
                </Typography>
              </Stack>
            </form>
          </CardContent>
        </Card>
      </Container>
    </Box>
  );
}


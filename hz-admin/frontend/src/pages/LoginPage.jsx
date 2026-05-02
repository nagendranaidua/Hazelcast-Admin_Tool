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
  CircularProgress,
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
          ? 'linear-gradient(135deg, #0F172A 0%, #1E293B 50%, #312E81 100%)'
          : 'linear-gradient(135deg, #F8FAFC 0%, #F1F5F9 50%, #E9D5FF 100%)',
        p: 2,
        animation: 'fadeIn 0.6s ease-in',
        '@keyframes fadeIn': {
          from: { opacity: 0 },
          to: { opacity: 1 },
        },
      }}
    >
      <Container maxWidth="sm">
        <Box sx={{
          animation: 'slideUp 0.6s ease-out',
          '@keyframes slideUp': {
            from: { opacity: 0, transform: 'translateY(30px)' },
            to: { opacity: 1, transform: 'translateY(0)' },
          },
        }}>
          <Card
            sx={{
              boxShadow: mode === 'dark'
                ? '0 25px 50px rgba(167, 139, 250, 0.15)'
                : '0 20px 60px rgba(124, 58, 237, 0.15)',
              borderRadius: 3,
              overflow: 'hidden',
              border: mode === 'dark'
                ? '1px solid rgba(167, 139, 250, 0.2)'
                : '1px solid rgba(124, 58, 237, 0.15)',
              transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
              '&:hover': {
                boxShadow: mode === 'dark'
                  ? '0 30px 60px rgba(167, 139, 250, 0.2)'
                  : '0 25px 70px rgba(124, 58, 237, 0.2)',
                transform: 'translateY(-2px)',
              },
            }}
          >
            {/* Header with logo and gradient */}
            <Box
              sx={{
                background: 'linear-gradient(135deg, #7C3AED 0%, #F97316 100%)',
                height: 140,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 2.5,
                position: 'relative',
                overflow: 'hidden',
                '&::before': {
                  content: '""',
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  right: 0,
                  bottom: 0,
                  background: 'radial-gradient(circle at 20% 50%, rgba(255, 255, 255, 0.1) 0%, transparent 50%)',
                  pointerEvents: 'none',
                },
              }}
            >
              <Box
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: 65,
                  height: 65,
                  borderRadius: 2.5,
                  backgroundColor: 'rgba(255, 255, 255, 0.15)',
                  backdropFilter: 'blur(10px)',
                  border: '1.5px solid rgba(255, 255, 255, 0.3)',
                  animation: 'pulse 3s ease-in-out infinite',
                  zIndex: 1,
                  '@keyframes pulse': {
                    '0%, 100%': { transform: 'scale(1)' },
                    '50%': { transform: 'scale(1.05)' },
                  },
                }}
              >
                <HazelcastLogo width={45} height={45} />
              </Box>
              <Box sx={{ zIndex: 1 }}>
                <Typography
                  variant="h5"
                  sx={{
                    color: '#FFFFFF',
                    fontWeight: 800,
                    letterSpacing: '-0.5px',
                  }}
                >
                  Hazelcast Admin
                </Typography>
                <Typography
                  variant="caption"
                  sx={{
                    color: 'rgba(255, 255, 255, 0.9)',
                    fontWeight: 500,
                    letterSpacing: '0.5px',
                  }}
                >
                  Management Center
                </Typography>
              </Box>
            </Box>

            {/* Form */}
            <CardContent sx={{ p: 4 }}>
              <Typography
                variant="h6"
                gutterBottom
                sx={{
                  fontWeight: 800,
                  mb: 0.5,
                  fontSize: '1.3rem',
                  letterSpacing: '-0.5px',
                }}
              >
                Welcome Back
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 3, fontWeight: 500 }}>
                Sign in to manage your Hazelcast clusters
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
                    sx={{
                      '& .MuiOutlinedInput-root': {
                        transition: 'all 0.3s ease',
                        '&:hover fieldset': {
                          borderColor: mode === 'dark' ? '#A78BFA' : '#7C3AED',
                        },
                        '&.Mui-focused fieldset': {
                          borderColor: '#7C3AED',
                          boxShadow: `0 0 0 3px ${mode === 'dark' ? 'rgba(167, 139, 250, 0.1)' : 'rgba(124, 58, 237, 0.1)'}`,
                        },
                      },
                    }}
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
                    sx={{
                      '& .MuiOutlinedInput-root': {
                        transition: 'all 0.3s ease',
                        '&:hover fieldset': {
                          borderColor: mode === 'dark' ? '#A78BFA' : '#7C3AED',
                        },
                        '&.Mui-focused fieldset': {
                          borderColor: '#7C3AED',
                          boxShadow: `0 0 0 3px ${mode === 'dark' ? 'rgba(167, 139, 250, 0.1)' : 'rgba(124, 58, 237, 0.1)'}`,
                        },
                      },
                    }}
                  />

                  <FormControlLabel
                    control={
                      <Checkbox
                        checked={rememberMe}
                        onChange={(e) => setRememberMe(e.target.checked)}
                        disabled={loading}
                        sx={{
                          color: mode === 'dark' ? '#A78BFA' : '#7C3AED',
                          '&.Mui-checked': {
                            color: mode === 'dark' ? '#A78BFA' : '#7C3AED',
                          },
                        }}
                      />
                    }
                    label="Remember me on this device"
                    sx={{ userSelect: 'none' }}
                  />

                  {error && (
                    <Alert
                      severity="error"
                      sx={{
                        animation: 'slideDown 0.3s ease',
                        '@keyframes slideDown': {
                          from: { opacity: 0, transform: 'translateY(-10px)' },
                          to: { opacity: 1, transform: 'translateY(0)' },
                        },
                      }}
                    >
                      {error}
                    </Alert>
                  )}

                  <Button
                    type="submit"
                    variant="contained"
                    size="large"
                    disabled={loading || !username || !password}
                    fullWidth
                    sx={{
                      py: 1.5,
                      fontWeight: 700,
                      fontSize: '1rem',
                      letterSpacing: '0.5px',
                      transition: 'all 0.3s ease',
                      position: 'relative',
                      overflow: 'hidden',
                      '&:disabled': {
                        opacity: 0.6,
                      },
                      '&:not(:disabled):active': {
                        transform: 'scale(0.98)',
                      },
                    }}
                  >
                    {loading ? (
                      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 1 }}>
                        <CircularProgress size={20} sx={{ color: 'inherit' }} />
                        <span>Signing in…</span>
                      </Box>
                    ) : (
                      'Sign in'
                    )}
                  </Button>

                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ textAlign: 'center', display: 'block', fontWeight: 500 }}
                  >
                    First time? Check README.md for default credentials.
                  </Typography>
                </Stack>
              </form>
            </CardContent>
          </Card>
        </Box>
      </Container>
    </Box>
  );
}


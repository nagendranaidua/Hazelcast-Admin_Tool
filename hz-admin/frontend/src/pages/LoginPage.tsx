import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Box, Card, CardContent, TextField, Button, Typography, Alert, Stack } from '@mui/material';
import { useAuth } from '../auth/AuthContext';

export function LoginPage() {
  const { login } = useAuth();
  const nav = useNavigate();
  const loc = useLocation() as any;
  const [u, setU] = useState('');
  const [p, setP] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      const me = await login(u, p);
      const dest = me.mustChangePassword ? '/change-password' : (loc.state?.from?.pathname ?? '/');
      nav(dest, { replace: true });
    } catch (ex: any) {
      setErr(ex.response?.data?.message ?? 'Login failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box sx={{ height: '100vh', display: 'grid', placeItems: 'center', p: 2 }}>
      <Card sx={{ width: 380 }}>
        <CardContent>
          <Typography variant="h5" gutterBottom>Hazelcast Admin</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Sign in with your administrator account.
          </Typography>
          <form onSubmit={onSubmit}>
            <Stack spacing={2}>
              <TextField label="Username" value={u} onChange={e => setU(e.target.value)} autoFocus required />
              <TextField label="Password" type="password" value={p} onChange={e => setP(e.target.value)} required />
              {err && <Alert severity="error">{err}</Alert>}
              <Button type="submit" variant="contained" disabled={busy}>
                {busy ? 'Signing in…' : 'Sign in'}
              </Button>
            </Stack>
          </form>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 3 }}>
            First-time setup? See README for default credentials. You'll be required to rotate
            the temporary password before any other action.
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
}

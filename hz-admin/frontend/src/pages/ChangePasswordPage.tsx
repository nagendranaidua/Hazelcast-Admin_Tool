import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Card, CardContent, TextField, Button, Typography, Alert, Stack } from '@mui/material';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';

export function ChangePasswordPage() {
  const { refresh } = useAuth();
  const nav = useNavigate();
  const [cur, setCur] = useState('');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(null);
    if (pw !== pw2) return setErr('Passwords do not match.');
    if (pw.length < 12) return setErr('Password must be at least 12 characters.');
    setBusy(true);
    try {
      await api.post('/api/auth/change-password', { currentPassword: cur, newPassword: pw });
      await refresh();
      nav('/', { replace: true });
    } catch (ex: any) {
      setErr(ex.response?.data?.message ?? 'Change failed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box sx={{ height: '100vh', display: 'grid', placeItems: 'center', p: 2 }}>
      <Card sx={{ width: 420 }}>
        <CardContent>
          <Typography variant="h5" gutterBottom>Set a new password</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Your account uses a temporary password. Please choose a new one (≥ 12 characters)
            before continuing.
          </Typography>
          <form onSubmit={onSubmit}>
            <Stack spacing={2}>
              <TextField label="Current password" type="password" value={cur} onChange={e => setCur(e.target.value)} required />
              <TextField label="New password" type="password" value={pw} onChange={e => setPw(e.target.value)} required />
              <TextField label="Confirm new password" type="password" value={pw2} onChange={e => setPw2(e.target.value)} required />
              {err && <Alert severity="error">{err}</Alert>}
              <Button type="submit" variant="contained" disabled={busy}>
                {busy ? 'Updating…' : 'Update password'}
              </Button>
            </Stack>
          </form>
        </CardContent>
      </Card>
    </Box>
  );
}

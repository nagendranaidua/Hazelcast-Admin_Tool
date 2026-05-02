import { useState } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useFilterSort } from '../lib/useFilterSort';
import { FilterCell, SortableHeader } from '../lib/TableBits';

type Row = {
  id: number; username: string; fullName: string; email: string;
  enabled: boolean; mustChangePassword: boolean; authSource: string;
  roles: string[];
};

/**
 * SUPER_ADMIN-only user administration. Lists all accounts in a sortable + filterable
 * table. Roles render as outlined chips, mustChangePassword as a small warning chip,
 * disabled accounts are visually de-emphasised. Create flow lives in a dialog so the
 * table doesn't get swamped by an inline form.
 */
export function UsersPage() {
  const qc = useQueryClient();
  const list = useQuery<Row[]>({
    queryKey: ['users'],
    queryFn: async () => (await api.get('/api/users')).data,
  });
  const [open, setOpen] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [form, setForm] = useState({ username: '', fullName: '', email: '', tempPassword: '', role: 'DEVELOPER' });

  const create = useMutation({
    mutationFn: async () => (await api.post('/api/users', { ...form, roles: [form.role] })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['users'] });
      setOpen(false); setErr(null);
      setForm({ username: '', fullName: '', email: '', tempPassword: '', role: 'DEVELOPER' });
    },
    onError: (e: any) => setErr(e.response?.data?.message ?? 'Create failed'),
  });

  const setEnabled = useMutation({
    mutationFn: async (args: { id: number; value: boolean }) =>
      api.put(`/api/users/${args.id}/enabled`, null, { params: { value: args.value } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
  });

  const cols = [
    { key: 'username',   accessor: (r: Row) => r.username },
    { key: 'fullName',   accessor: (r: Row) => r.fullName },
    { key: 'email',      accessor: (r: Row) => r.email },
    { key: 'authSource', accessor: (r: Row) => r.authSource },
    { key: 'roles',      accessor: (r: Row) => r.roles.join(',') },
    { key: 'enabled',    accessor: (r: Row) => r.enabled ? 'enabled' : 'disabled' },
  ];
  const { visible, filters, setFilter, sort, toggleSort } =
    useFilterSort<Row>(list.data ?? [], cols, 'username');

  return (
    <>
      <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 2 }}>
        <Typography variant="h4">Users</Typography>
        <Box sx={{ flexGrow: 1 }} />
        <Chip size="small" label={`${visible.length} of ${list.data?.length ?? 0}`} />
        <Button variant="contained" onClick={() => setOpen(true)}>Create user</Button>
      </Stack>

      {list.isLoading && <LinearProgress sx={{ mb: 2 }} />}
      {list.error && <Alert severity="error" sx={{ mb: 2 }}>Failed to load users.</Alert>}

      {list.data && (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <SortableHeader label="Username"   col="username"   sort={sort} onClick={toggleSort} />
                <SortableHeader label="Full name"  col="fullName"   sort={sort} onClick={toggleSort} />
                <SortableHeader label="Email"      col="email"      sort={sort} onClick={toggleSort} />
                <SortableHeader label="Auth"       col="authSource" sort={sort} onClick={toggleSort} />
                <SortableHeader label="Roles"      col="roles"      sort={sort} onClick={toggleSort} />
                <SortableHeader label="State"      col="enabled"    sort={sort} onClick={toggleSort} />
                <TableCell align="right">Actions</TableCell>
              </TableRow>
              <TableRow>
                <FilterCell value={filters.username   ?? ''} onChange={v => setFilter('username', v)} />
                <FilterCell value={filters.fullName   ?? ''} onChange={v => setFilter('fullName', v)} />
                <FilterCell value={filters.email      ?? ''} onChange={v => setFilter('email', v)} />
                <FilterCell value={filters.authSource ?? ''} onChange={v => setFilter('authSource', v)} />
                <FilterCell value={filters.roles      ?? ''} onChange={v => setFilter('roles', v)} />
                <FilterCell value={filters.enabled    ?? ''} onChange={v => setFilter('enabled', v)} />
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {visible.map(u => (
                <TableRow key={u.id} hover sx={{ opacity: u.enabled ? 1 : 0.6 }}>
                  <TableCell>
                    <Stack direction="row" spacing={1} alignItems="center">
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{u.username}</Typography>
                      {u.mustChangePassword && (
                        <Chip size="small" color="warning" label="must rotate pwd" />
                      )}
                    </Stack>
                  </TableCell>
                  <TableCell>{u.fullName || <Typography variant="caption" color="text.disabled">—</Typography>}</TableCell>
                  <TableCell><Typography variant="body2">{u.email}</Typography></TableCell>
                  <TableCell><Chip size="small" variant="outlined" label={u.authSource} /></TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                      {u.roles.map(r => (
                        <Chip key={r} size="small" variant="outlined"
                              label={r.replace('ROLE_', '').replace('_', ' ').toLowerCase()}
                              sx={{ textTransform: 'capitalize' }} />
                      ))}
                    </Stack>
                  </TableCell>
                  <TableCell>
                    {u.enabled
                      ? <Chip size="small" color="success" label="enabled" />
                      : <Chip size="small" label="disabled" />}
                  </TableCell>
                  <TableCell align="right">
                    <Button size="small" color={u.enabled ? 'warning' : 'primary'}
                            onClick={() => setEnabled.mutate({ id: u.id, value: !u.enabled })}
                            disabled={setEnabled.isPending}>
                      {u.enabled ? 'Disable' : 'Enable'}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {visible.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} align="center">
                    <Typography variant="body2" color="text.secondary">
                      {(list.data?.length ?? 0) === 0 ? 'No users yet.' : 'No users match the current filters.'}
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Create user</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Username"           value={form.username}     onChange={e => setForm({ ...form, username: e.target.value })} />
            <TextField label="Full name"          value={form.fullName}     onChange={e => setForm({ ...form, fullName: e.target.value })} />
            <TextField label="Email"              value={form.email}        onChange={e => setForm({ ...form, email: e.target.value })} />
            <TextField label="Temporary password" type="password"
                       value={form.tempPassword} onChange={e => setForm({ ...form, tempPassword: e.target.value })}
                       helperText="User will be required to rotate on first login (≥ 12 chars)" />
            <TextField select label="Role" value={form.role} onChange={e => setForm({ ...form, role: e.target.value })}>
              <MenuItem value="SUPER_ADMIN">SUPER_ADMIN</MenuItem>
              <MenuItem value="CLUSTER_OPERATOR">CLUSTER_OPERATOR</MenuItem>
              <MenuItem value="DEVELOPER">DEVELOPER</MenuItem>
              <MenuItem value="READ_ONLY">READ_ONLY</MenuItem>
            </TextField>
            {err && <Alert severity="error">{err}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => create.mutate()} disabled={create.isPending}>
            Create
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

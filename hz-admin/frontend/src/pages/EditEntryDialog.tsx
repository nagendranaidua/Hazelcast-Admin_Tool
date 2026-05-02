import { useState } from 'react';
import {
  Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle,
  Stack, TextField, Typography,
} from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { api } from '../api/client';

type Props = {
  clusterId:    number;
  mapName:      string;
  initialKey:   string;
  initialValue: string | null;
  onClose:      () => void;
  onSaved:      () => void;
};

/**
 * Modal editor for a single IMap entry. Mandatory reason field is enforced both client-side
 * (Save button disabled) and server-side (controller rejects empty reasons with 400).
 *
 * <p>The value field accepts arbitrary JSON; the bridge wraps it in {@code HazelcastJsonValue}.
 * Validation is light: we only check that the JSON parses on the client. The actual wire format
 * (Portable / Compact / IdentifiedDataSerializable) is whatever the cluster's serialization config
 * resolves it to — most production maps store {@code HazelcastJsonValue} or string anyway.
 */
export function EditEntryDialog({ clusterId, mapName, initialKey, initialValue, onClose, onSaved }: Props) {
  const [key, setKey]       = useState(initialKey);
  const [value, setValue]   = useState(initialValue ?? '');
  const [ttlMs, setTtlMs]   = useState<string>('');
  const [reason, setReason] = useState('');
  const [parseError, setParseError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: async () => {
      // Best-effort JSON parse so the user sees the error before the round-trip.
      try { JSON.parse(value); setParseError(null); }
      catch (e: any) {
        // Allow plain strings — wrap and retry.
        try { JSON.parse(JSON.stringify(value)); }
        catch { setParseError(e.message); throw new Error('Invalid JSON in value'); }
      }
      const body = {
        key,
        value,
        reason,
        ttlMs: ttlMs ? Number(ttlMs) : null,
      };
      return (await api.put(`/api/clusters/${clusterId}/maps/${encodeURIComponent(mapName)}/entry`, body)).data;
    },
    onSuccess: () => onSaved(),
  });

  const reasonValid = reason.trim().length > 0 && reason.length <= 1000;
  const canSave = !!key && !!value && reasonValid;

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{initialKey ? 'Edit entry' : 'New entry'} — {mapName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Key (JSON or string)"
            value={key} onChange={e => setKey(e.target.value)}
            disabled={!!initialKey}
            helperText={initialKey ? 'Key is immutable — create a new entry to change keys.' : 'Will be parsed as JSON if possible, else used as a string.'}
          />
          <TextField
            label="Value (JSON)"
            value={value} onChange={e => setValue(e.target.value)}
            multiline minRows={6} maxRows={20}
            sx={{ fontFamily: 'monospace' }}
            helperText='Stored as HazelcastJsonValue. Example: {"name":"Alice","age":30}'
          />
          <TextField
            label="TTL (ms, optional)"
            value={ttlMs} onChange={e => setTtlMs(e.target.value.replace(/\D/g, ''))}
            placeholder="e.g. 60000 for 1 minute"
          />
          <TextField
            label="Reason (required)"
            value={reason} onChange={e => setReason(e.target.value)}
            multiline minRows={2} maxRows={4}
            error={reason.length > 0 && !reasonValid}
            helperText={`Audit log entry — explain why this change is being made. ${reason.length}/1000`}
          />
          {parseError && <Alert severity="warning">JSON parse warning: {parseError}</Alert>}
          {save.error && <Alert severity="error">
            {(save.error as any).response?.data?.error ?? 'Save failed.'}
          </Alert>}
          {save.data && save.data.auditId && (
            <Typography variant="caption" color="text.secondary">
              Audit row #{save.data.auditId} written.
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!canSave || save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

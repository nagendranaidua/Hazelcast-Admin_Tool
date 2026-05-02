import { Typography, Alert } from '@mui/material';

export function OpsPage() {
  return (
    <>
      <Typography variant="h4" gutterBottom>Operations</Typography>
      <Alert severity="info">
        SSH-based start/stop, cluster state changes, and the rolling-upgrade wizard land in Phase 4.
        Schema for ssh_hosts already exists; UI/REST coming next phase.
      </Alert>
    </>
  );
}

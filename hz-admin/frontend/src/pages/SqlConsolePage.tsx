import { useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Alert, Box, Button, Card, CardContent, Chip, IconButton, Paper, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField,
  ToggleButton, ToggleButtonGroup, Tooltip, Typography,
} from '@mui/material';
import { api } from '../api/client';

type ColMeta = { name: string; type: string; nullable: boolean };
type SqlPage = {
  cursorId:  string | null;
  mode:      'STREAM' | 'LIMIT';
  columns:   ColMeta[];
  rows:      any[][];
  done:      boolean;
  pageIndex: number;
  elapsedMs: number;
};

type Mode = 'STREAM' | 'LIMIT';

/**
 * SQL editor + paginated result grid. The mode toggle picks between server-side streaming
 * (recommended for large result sets — preserves ORDER BY across pages) and one-shot
 * LIMIT/OFFSET. Both modes run through the same backend endpoint that audits every query.
 *
 * <p>On unmount we politely close any open streaming cursor so the bridge can release the
 * underlying SqlResult immediately rather than waiting for the 5-minute idle reaper.
 */
export function SqlConsolePage() {
  const { id } = useParams();
  const nav = useNavigate();
  const [query, setQuery] = useState("SELECT __key, this FROM \"my-map\" LIMIT 100");
  const [mode, setMode]   = useState<Mode>('STREAM');
  const [pageSize, setPageSize] = useState(100);
  const [running, setRunning] = useState(false);
  const [pages, setPages]   = useState<SqlPage[]>([]);  // accumulated pages, latest last
  const [err, setErr]       = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const cursorRef = useRef<string | null>(null);

  // Best-effort cursor cleanup on unmount.
  useEffect(() => () => { void closeIfOpen(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

  const closeIfOpen = async () => {
    if (cursorRef.current) {
      try { await api.delete(`/api/clusters/${id}/sql/${cursorRef.current}`); }
      catch { /* the bridge will reap it eventually anyway */ }
      cursorRef.current = null;
    }
  };

  const run = async () => {
    setErr(null);
    setRunning(true);
    setPages([]);
    await closeIfOpen();
    try {
      const { data } = await api.post<SqlPage>(`/api/clusters/${id}/sql`, {
        query, pageSize, mode, offset: 0, reason: reason || null,
      });
      cursorRef.current = data.cursorId;
      setPages([data]);
    } catch (e: any) {
      setErr(e.response?.data?.error ?? e.message ?? 'Query failed');
    } finally {
      setRunning(false);
    }
  };

  const next = async () => {
    setRunning(true);
    setErr(null);
    try {
      if (mode === 'STREAM') {
        if (!cursorRef.current) throw new Error('No open cursor');
        const { data } = await api.post<SqlPage>(
          `/api/clusters/${id}/sql/${cursorRef.current}/next`, { pageSize });
        if (data.done) cursorRef.current = null;
        setPages(p => [...p, data]);
      } else {
        const last = pages[pages.length - 1];
        const nextOffset = (last.pageIndex + 1) * pageSize;
        const { data } = await api.post<SqlPage>(`/api/clusters/${id}/sql`, {
          query, pageSize, mode, offset: nextOffset, reason: reason || null,
        });
        setPages(p => [...p, data]);
      }
    } catch (e: any) {
      setErr(e.response?.data?.error ?? e.message ?? 'Fetch failed');
    } finally {
      setRunning(false);
    }
  };

  const allRows  = pages.flatMap(p => p.rows);
  const cols     = pages[0]?.columns ?? [];
  const lastPage = pages[pages.length - 1];
  const canPage  = lastPage && !lastPage.done;

  return (
    <>
      <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 2 }}>
        <Button size="small" onClick={() => nav(`/clusters/${id}/maps`)}>← Maps</Button>
        <Typography variant="h4">SQL console</Typography>
      </Stack>

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Stack spacing={2}>
            <TextField
              label="SQL query"
              value={query} onChange={e => setQuery(e.target.value)}
              multiline minRows={4} maxRows={20}
              sx={{ fontFamily: 'monospace' }}
            />
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
              <ToggleButtonGroup size="small" exclusive value={mode}
                                 onChange={(_, v) => v && setMode(v)}>
                <ToggleButton value="STREAM">
                  <Tooltip title="Server keeps the SqlResult open; pages preserve ORDER BY semantics. 5-minute idle TTL.">
                    <span>Stream</span>
                  </Tooltip>
                </ToggleButton>
                <ToggleButton value="LIMIT">
                  <Tooltip title="Backend appends LIMIT/OFFSET per page and re-executes. Simpler but ORDER BY drifts if the data changes.">
                    <span>LIMIT/OFFSET</span>
                  </Tooltip>
                </ToggleButton>
              </ToggleButtonGroup>
              <TextField label="Page size" size="small" type="number"
                         value={pageSize} onChange={e => setPageSize(Math.max(1, Math.min(1000, Number(e.target.value) || 100)))}
                         sx={{ width: 120 }} />
              <TextField label="Reason (optional)" size="small"
                         value={reason} onChange={e => setReason(e.target.value)}
                         placeholder="recorded on the audit row"
                         sx={{ flexGrow: 1, minWidth: 240 }} />
              <Button variant="contained" onClick={run} disabled={running || !query.trim()}>
                {running ? 'Running…' : 'Run'}
              </Button>
            </Stack>
          </Stack>
        </CardContent>
      </Card>

      {err && <Alert severity="error" sx={{ mb: 2 }}>{err}</Alert>}

      {pages.length > 0 && (
        <>
          <Stack direction="row" spacing={1} sx={{ mb: 1 }} flexWrap="wrap">
            <Chip size="small" label={`mode: ${pages[0].mode}`} />
            <Chip size="small" label={`${cols.length} columns`} />
            <Chip size="small" label={`${allRows.length} rows`} />
            <Chip size="small" label={`first page ${pages[0].elapsedMs}ms`} />
            {!canPage && <Chip size="small" color="success" label="done" />}
          </Stack>

          <TableContainer component={Paper} variant="outlined" sx={{ maxHeight: 600 }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  {cols.map(c => (
                    <TableCell key={c.name}>
                      {c.name}
                      <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>
                        {c.type}
                      </Typography>
                    </TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {allRows.map((row, i) => (
                  <TableRow key={i}>
                    {row.map((cell, j) => (
                      <TableCell key={j} sx={{ fontFamily: 'monospace', maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        <Tooltip title={String(cell ?? '')}><span>{renderCell(cell)}</span></Tooltip>
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 2 }}>
            <Button size="small" disabled={!canPage || running} onClick={next}>
              Fetch next {pageSize}
            </Button>
            {mode === 'STREAM' && cursorRef.current && (
              <Button size="small" color="inherit" onClick={() => closeIfOpen().then(() => setPages([]))}>
                Close cursor
              </Button>
            )}
          </Stack>
        </>
      )}
    </>
  );
}

function renderCell(v: any): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

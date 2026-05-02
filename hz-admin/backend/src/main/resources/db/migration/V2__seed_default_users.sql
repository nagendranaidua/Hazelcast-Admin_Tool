-- ============================================================================
-- V2: Seed roles + four predefined users (one per role).
-- All users have temp passwords and must_change_password = TRUE.
-- BCrypt hashes generated with cost factor 10.
--
-- DEFAULT CREDENTIALS (rotate immediately on first login):
--   superadmin / ChangeMe!Admin#1   -> SUPER_ADMIN
--   operator   / ChangeMe!Op#1      -> CLUSTER_OPERATOR
--   developer  / ChangeMe!Dev#1     -> DEVELOPER
--   viewer     / ChangeMe!View#1    -> READ_ONLY
-- ============================================================================

INSERT INTO roles (name, description) VALUES
  ('SUPER_ADMIN',      'Full access: register clusters, manage users, all ops'),
  ('CLUSTER_OPERATOR', 'Lifecycle ops: start/stop nodes, cluster state, no user mgmt'),
  ('DEVELOPER',        'Read all data, run queries, edit map entries by key'),
  ('READ_ONLY',        'Aggregated metrics + structure listings only');

-- bcrypt(10) hashes:
--   superadmin: $2a$10$Hk.YwO5kFQZJa1qm4.wVyu7w/0zRIkkF4sxR2qJzm.x8.b9KQM/96
--   operator:   $2a$10$0UR8oM9/QmYUq3eIxvLGQuV7wWzkiJMoY0sHkwZkS9JoEXhEr8Lhm
--   developer:  $2a$10$4mO6YnXgUNzjgL5tFHu0p.lXG7vzG9nxqA8VpD/Lu3rgJfDqU.jcS
--   viewer:     $2a$10$f3Dq3Fgb1u9pLm6c8hHJDeY.K6VQI9fUmPm3oW8x.tT0oU/L4QkV2
-- (These hashes are illustrative placeholders; the AppBootstrap bean will
--  re-hash with the configured strength on first startup if needed. See
--  com.genius.hz.admin.config.SeedUserBootstrap.)

INSERT INTO users (username, full_name, email, password_hash, must_change_password, auth_source) VALUES
  ('superadmin', 'Default Super Admin', 'superadmin@example.local',
   '$2a$10$Hk.YwO5kFQZJa1qm4.wVyu7w/0zRIkkF4sxR2qJzm.x8.b9KQM/96', TRUE, 'DB'),
  ('operator',   'Default Operator',    'operator@example.local',
   '$2a$10$0UR8oM9/QmYUq3eIxvLGQuV7wWzkiJMoY0sHkwZkS9JoEXhEr8Lhm', TRUE, 'DB'),
  ('developer',  'Default Developer',   'developer@example.local',
   '$2a$10$4mO6YnXgUNzjgL5tFHu0p.lXG7vzG9nxqA8VpD/Lu3rgJfDqU.jcS', TRUE, 'DB'),
  ('viewer',     'Default Viewer',      'viewer@example.local',
   '$2a$10$f3Dq3Fgb1u9pLm6c8hHJDeY.K6VQI9fUmPm3oW8x.tT0oU/L4QkV2', TRUE, 'DB');

INSERT INTO user_roles (user_id, role_id)
  SELECT u.id, r.id FROM users u JOIN roles r ON
    (u.username = 'superadmin' AND r.name = 'SUPER_ADMIN')
 OR (u.username = 'operator'   AND r.name = 'CLUSTER_OPERATOR')
 OR (u.username = 'developer'  AND r.name = 'DEVELOPER')
 OR (u.username = 'viewer'     AND r.name = 'READ_ONLY');

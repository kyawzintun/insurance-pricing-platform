-- Spring Data IgnoreCase emits upper(email); plain email uniqueness cannot serve it.
-- Keep this nonunique: do not introduce new identity/uniqueness semantics.
CREATE INDEX idx_users_upper_email ON users (upper(email));
CREATE INDEX idx_users_role_created_id ON users (role, created_at DESC, id DESC);

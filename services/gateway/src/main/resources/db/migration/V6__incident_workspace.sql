-- Wire workspaces into incidents: an incident can optionally be scoped to a workspace,
-- so "workspace" becomes a real filter/view instead of a decorative group of members.
-- Additive; V1-V5 untouched.
ALTER TABLE incidents ADD COLUMN workspace_id UUID REFERENCES workspaces(id) ON DELETE SET NULL;
CREATE INDEX idx_incidents_workspace_id ON incidents(workspace_id);

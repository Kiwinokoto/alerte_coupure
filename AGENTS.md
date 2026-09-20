# Agent instructions

PowerWatch is coordinated through the central AgentCtl service. The repository identity is declared in `.agentctl.json`.

Before any mutation:
1. Read `android/README.md`, especially the current handoff section.
2. Verify `dev/android-v1`, Git origin, HEAD, local changes and the current GitHub branch tip.
3. Run `agentctl status` and check for active/stale sessions or leases affecting the resource you need.
4. Never overwrite unexplained local work or bypass an active lease.

Read-only inspection does not require a lease. Mutations should use `tools/agentctl-run` with the smallest applicable scope:
- `repo`: repository files/Git state.
- `device`: the OnePlus 7T via ADB.
- `service`: PowerWatch API runtime/service.
- `db`: PowerWatch database.
- `deploy`: personal-VPS PowerWatch deployment target.
- `backend`: shorthand for service + db + deploy.
- `all`: every PowerWatch mutable resource.

Example:
`tools/agentctl-run --owner chat:powerwatch repo device -- <command>`

A stale lease is not permission to take over: investigate before any admin break. Never expose AgentCtl, PowerWatch, SMTP, GitHub or device secrets. Do not pass admin credentials to worker commands.

Keep changes small and tested. Reuse the existing handoff in `android/README.md`; do not create parallel TODO/backlog/checkpoint files.

# Judge0 (local, for Run Code)

Backs the "Run Code" feature in the live-coding round — compiles and runs the candidate's
submission against a question's stored test cases. Self-hosted because the free public Piston
API this originally used went whitelist-only on 2026-02-15.

## Known issue: Docker Desktop on Windows (WSL2) — sandbox doesn't execute

**As shipped, Run Code does not actually execute code under Docker Desktop's default WSL2
backend.** The request reaches Judge0 fine (compiles, queues the job), but its `isolate` sandbox
fails with:

```
Failed to create control group /sys/fs/cgroup/memory/box-N/: No such file or directory
```

Judge0 v1.13.1's bundled `isolate` binary expects the classic **cgroup v1** per-controller
hierarchy (`/sys/fs/cgroup/memory/...`). Docker Desktop's WSL2 kernel runs **cgroup v2** (unified
hierarchy) by default, which has no such path. This is an environment incompatibility, not
something fixable in this project's code or `judge0.conf`.

The rest of the interview flow is unaffected: submitting a coding answer still goes through the
LLM evaluator fully regardless of whether Run Code works. The endpoint fails with a clear
"Code execution service is unavailable" message rather than a crash.

**To actually fix it** (optional, affects your whole WSL2/Docker setup, not just this project):

1. Edit (or create) `%UserProfile%\.wslconfig`:
   ```ini
   [wsl2]
   kernelCommandLine = cgroup_no_v1=all systemd.unified_cgroup_hierarchy=0
   ```
   (the second flag is the one that actually forces cgroup v1; adjust if a different WSL2 kernel
   version needs a different flag — check current WSL2/Docker Desktop docs, this shifts over time)
2. `wsl --shutdown` from a terminal, then restart Docker Desktop.
3. Re-verify: `docker exec judge0-workers-1 mount | grep cgroup` should show `cgroup` (v1, several
   lines) instead of a single `cgroup2` line.
4. Recreate the containers (`docker compose up -d --force-recreate`) and try Run Code again.

This is a machine-wide setting — think about whether other Docker workloads on this machine
expect cgroup v2 before changing it.

## Start it

```
cd backend/judge0
docker compose up -d
```

First start pulls the `judge0/judge0:1.13.1`, `postgres:16.2`, and `redis:7.2.4` images and can
take a few minutes. Once up, the API is at `http://localhost:2358` (matches the backend's default
`judge0.base-url` — override with the `JUDGE0_BASE_URL` env var if you run it elsewhere).

Check it's ready:

```
curl http://localhost:2358/languages
```

## Stop it

```
docker compose down
```

Add `-v` to also drop the Postgres volume (submission history) if you want a clean slate.

## Notes

- `docker-compose.yml`/`judge0.conf` are from the official
  [judge0/judge0](https://github.com/judge0/judge0) v1.13.1 release, with two changes from the
  shipped defaults: `POSTGRES_PASSWORD` and `REDIS_PASSWORD` are set (both blank by default in the
  release, but "cannot be blank" per Judge0's own config comments — the newer `postgres:16.2` image
  actively refuses to start with a blank password, and Redis refuses the AUTH command Judge0 sends
  when its own `REDIS_PASSWORD` is blank). Both values are local-only placeholders
  (`judge0_local_dev_password`) — fine since this only ever listens on `localhost`.
- Re-download `docker-compose.yml`/`judge0.conf` from the Judge0 releases page if a newer version
  is needed, and re-apply the two password settings above.

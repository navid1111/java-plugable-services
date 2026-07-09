# Removing a committed `.env` from Git history

This documents how the leaked `.env` file was removed from this repository's
Git history, and how to handle a similar leak in the future.

## What happened

`.env` was listed in `.gitignore`, **but it had already been committed before
the ignore rule existed**. Git only ignores *untracked* files, so once a file is
tracked the `.gitignore` entry has no effect — the file kept getting included in
commits and was pushed to GitHub.

The file was present in history from commit `124f6e8` through `HEAD` and
contained real secrets:

- `JWT_SECRET`
- `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET`
- `MEDIA_DB_PASSWORD`

## The fix

### 1. Diagnose

```bash
# Is .env actually tracked? (no error = it's tracked)
git ls-files --error-unmatch .env

# Which commits touched it?
git log --oneline --all -- .env

# Is the leak already pushed? (0  0 = local and origin are in sync)
git rev-list --left-right --count origin/master...master
```

### 2. Create a safety backup

```bash
git branch backup-before-env-purge
git tag   backup-env-purge-$(date +%s)
```

### 3. Purge `.env` from every commit

`git filter-repo` is the recommended tool but was not installed, so the built-in
`git filter-branch` was used (fine for a small repo):

```bash
export FILTER_BRANCH_SQUELCH_WARNING=1
git filter-branch --force --index-filter \
  'git rm --cached --ignore-unmatch .env' \
  --prune-empty --tag-name-filter cat -- --all
```

- `--index-filter` rewrites each commit's index, dropping `.env`.
- `--ignore-unmatch` avoids errors on commits that never had the file.
- `--prune-empty` drops commits that became empty.
- `-- --all` applies to all refs.

> ⚠️ `filter-branch` resets the working tree to the rewritten `HEAD`, which
> **deletes the working `.env` from disk**. Restore it from the original commit:
>
> ```bash
> git show refs/original/refs/heads/master:.env > .env
> ```
>
> Because `.env` is now untracked and gitignored, it stays local-only.

### 4. Verify locally

```bash
git log master --oneline -- .env      # empty = clean
git ls-files --error-unmatch .env     # errors = no longer tracked
git check-ignore .env                 # prints ".env" = correctly ignored
```

### 5. Overwrite the remote

Rewriting history means the remote must be force-pushed:

```bash
git fetch origin
git push --force origin master
```

> `--force-with-lease` may reject with `stale info` after a history rewrite
> because the local remote-tracking ref is out of sync. `git fetch` first, then
> either retry `--force-with-lease` or use plain `--force` on a solo repo.

### 6. Clean up recovery refs (only after the push succeeds)

`filter-branch` stores the pre-rewrite history under `refs/original/`. Delete
those (plus the manual backup branch/tag) so no copy of `.env` remains:

```bash
git for-each-ref refs/original/            # list them
git update-ref -d refs/original/refs/heads/master
git update-ref -d refs/original/refs/heads/backup-before-env-purge
git update-ref -d refs/original/refs/remotes/origin/master
git update-ref -d refs/original/refs/tags/backup-env-purge-<timestamp>
git branch -D backup-before-env-purge
git tag    -d backup-env-purge-<timestamp>
git reflog expire --expire=now --all
git gc --prune=now

git log --all --oneline -- .env           # empty = fully purged
```

## 🔴 Rotate the secrets — this is the part that actually protects you

**Rewriting history does not un-leak a secret.** The values were public on
GitHub, so they must be treated as compromised regardless of the rewrite:

- GitHub keeps old commits reachable by SHA until it garbage-collects.
- Forks, clones, CI logs, and caches may retain copies.
- Automated scanners grab pushed secrets within seconds.

Rotate every value that was in the file:

- **`JWT_SECRET`** — regenerate (this invalidates existing tokens).
- **Cloudinary `API_KEY` / `API_SECRET`** — roll in the Cloudinary console.
- **`MEDIA_DB_PASSWORD`** — change the database password.

## Preventing a repeat

- Keep `.env` in `.gitignore` (already done) and commit only `.env.example`
  with placeholder values.
- The rule is only effective for **untracked** files — never `git add` a real
  `.env` in the first place.
- Optionally add a pre-commit hook or a secret scanner (e.g. `gitleaks`) to
  block secrets before they land.

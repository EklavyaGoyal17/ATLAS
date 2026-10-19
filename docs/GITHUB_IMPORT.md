# Import a public GitHub repository

ATLAS supports frontend, backend, library, infrastructure and full-stack source projects. The payments lab is a separate built-in synthetic example. Source import never starts a repository's services or executes its code.

1. Open **Code intelligence** or **AI engineer** and choose **GitHub link**.
2. Paste a canonical root link, for example `https://github.com/EklavyaGoyal17/Forge`. Optional `.git` and a trailing slash are accepted. Remove tracking queries, branch/file paths and credentials.
3. Optionally enter an exact repository subfolder, such as `forge-server/src` or `frontend/src`. Source paths retain that prefix.
4. Check **I approve downloading this public repository**, then choose **Import GitHub repository**.
5. Check the displayed full commit SHA and scope. Browse **Symbols & files**, inspect declared manifests and supported Java relationships, or choose **Find local evidence** for a focused question.

No GitHub API key or AI subscription is needed. This adapter supports public GitHub.com repositories only; it does not use your browser sign-in, Git credential helper, personal access token or model key. Anonymous GitHub rate limits and network policies still apply. A refusal produces a retryable import error and preserves the previous approved snapshot. Private/absent repositories, unsafe URLs and archive limits have separate errors.

The default branch is pinned to a commit before its source archive is fetched. The snapshot is static; reimport to obtain a newer commit. Files remain in API memory rather than a checkout. Delete the import or restart the API to clear it. The provenance view identifies the commit but does not claim Git history or a working-tree diff. Local-folder imports retain the existing Git inspection behavior.

## Supported analysis

Java receives AST symbols, endpoints, transaction declarations, conservative potential calls, qualified field/record references, cycle/coupling review and selected-file change-impact paths. These are syntax-based leads, not compilation or runtime proof.

Supported text extensions include JavaScript/TypeScript/JSX/TSX, Python/PYI, Go, Rust, C/C++ headers and sources, C#, Kotlin, Ruby, PHP, Swift, HTML/CSS/SCSS, Vue/Svelte, Markdown/MDX, SQL, JSON, YAML, XML, TOML, properties, text and shell scripts. They can be browsed and retrieved as source text; non-Java AST/import/call graphs are not implemented. Manifest dependency inventory parses npm `package.json` and Maven `pom.xml`; other manifests remain text.

## Limits and privacy

Indexed source: **500 files, 4000000 bytes total, 160000 bytes per file**. Whole archive: **16 MiB compressed, 32 MiB expanded, 5000 entries, depth 18**, with bounded network and parsing deadlines. Scope can reduce indexed files but does not reduce the whole archive download or expanded-entry budgets. A very large repository requires a smaller repository or an operator-prepared local checkout, not merely a narrower remote scope.

Hidden/generated/credential-named files and unsupported formats are excluded. Oversized, invalid UTF-8 and NUL-containing supported files are omitted with summary findings. Redaction is heuristic; private-key content is withheld and instruction-like content is treated as untrusted data. Review exact excerpts before approving any optional model transmission. Import approval and provider-transmission approval are separate actions.

## Relationship to Forge

[Forge](https://github.com/EklavyaGoyal17/Forge) runs reviewed built-in experiments to study correctness and performance. ATLAS imports its source as evidence for code exploration, architecture review and engineering notes. Importing Forge does not start a Forge experiment or merge its runner/database into ATLAS. The projects can be used together while retaining their own execution and data boundaries.

ADR 013 describes the adapter. TESTING distinguishes deterministic fixtures from actual public-download smoke checks.

For an explicit live-network check against a running local API, set `ATLAS_API_TOKEN` in your terminal and run:

```powershell
node scripts/github-import-check.mjs https://github.com/EklavyaGoyal17/ATLAS https://github.com/EklavyaGoyal17/Forge
```

This replaces the current in-memory import and leaves the final repository selected. It verifies download provenance, source retrieval, manifest inventory and Java/impact snapshot identity without executing repository code or invoking a model. It is deliberately separate from default CI because anonymous GitHub availability/rate limits are external conditions.

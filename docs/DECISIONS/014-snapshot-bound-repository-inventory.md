# ADR 014: Snapshot-bound language inventory and manifest declarations

Status: accepted, 21 October 2026.

## Context

Public GitHub imports allow frontend, backend and mixed-language projects. Java AST evidence is useful only for the supported Java subset. Users also need a concrete inventory of the approved text and safe dependency leads for Python and Go projects. Metadata must not silently describe a different import while the UI still displays an earlier source snapshot.

## Decision

Metadata captures one immutable approved snapshot. Responses include its UUID. An optional expected `snapshotId` returns 409 on mismatch, and the console sends it and withholds mismatched results. Imports and deletion hide old inventory; language/path filters reset for a replacement snapshot. Delayed responses cannot replace the new snapshot's inventory.

The inventory groups indexed paths by their stored extension and reports file counts, stored line counts and UTF-8 content bytes after redaction. These are neither whole-repository statistics nor semantic language detection. Path links open the retained source. The viewer uses the same newline convention as the Java index, including bare CR and Unicode line separators.

Recognize exact `package.json`, `pom.xml`, `requirements.txt`, `requirements-<name>.txt` and `go.mod` basenames. Accept a conservative subset of named Python requirements and Go `require` declarations, preserving literal versions and exact declaration lines. npm/Maven evidence remains file-level (`line: 0`). Framework signals require the matching ecosystem, avoiding claims such as Python's similarly named package proving a React application.

Skip unsupported requirements options, includes, URLs, VCS/local/archive targets, continuations and unsupported marker syntax. Never evaluate markers, resolve versions, apply Go replacements/exclusions, install dependencies or execute imported content. Format-specific omissions and resource limits appear in the report. Exactly named `go.mod` is allowed by both import adapters; arbitrary `.mod` files remain excluded.

Reports contain at most 2,000 dependency declarations, 512,000 UTF-8 bytes across their text fields, 500 characters per name and 2,000 per version declaration. Oversized values are omitted with explicit limitations. Flat direct-child Maven fields avoid nested XML text amplification. Inventory still covers all indexed files when dependency collection reaches its limit. No new dependency, database migration or model transmission is introduced.

## Alternatives

Executing package managers would produce richer resolved graphs but would introduce network, script and filesystem side effects outside approved source inspection. Adding full Python/TOML/Go parsers would broaden syntax coverage at a maintenance cost; this iteration favors explicit conservative declarations. Reusing the client snapshot without a server identity check would leave metadata requests vulnerable to another import occurring between HTTP requests.

## Consequences

Python and Go projects get useful source-linked inventory and declarations without implying non-Java call-graph support. Unsupported formats such as `pyproject.toml` remain searchable text. Findings describe a bounded subset, not an installed environment, software bill of materials, vulnerability assessment or runtime service map. Exact source links remain useful even when declaration resolution is unavailable.

## References

- [pip requirements-file format](https://pip.pypa.io/en/stable/reference/requirements-file-format/)
- [Go module file reference](https://go.dev/doc/modules/gomod-ref)
- [ADR 013: approved public GitHub snapshots](013-approved-public-github-snapshots.md)

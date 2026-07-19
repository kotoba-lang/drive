# drive

The existing CLJC model remains the open-schema semantic oracle. The
capability-free Kotoba profile in `src/drive/bounded.kotoba` represents a
single-rooted tree of at most eight keyword-identified folders and files.
`src/drive/bounded_validate.kotoba` rejects missing parents, files used as
parents, self-links, longer cycles, non-root parentless items, unknown kinds,
and over-limit trees. Updates are persistent and both Web and typed Wasm run
the same conformance graph.

Titles, media types, content/object references, and arbitrary attribute maps
are intentionally not reinterpreted by the bounded profile; consumers needing
those values continue to use the CLJC oracle or an explicit host boundary.

[![CI](https://github.com/kotoba-lang/drive/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/drive/actions/workflows/ci.yml)

Portable CLJC model for kotoba-lang/drive.

Pages editor: https://kotoba-lang.github.io/drive/

The Pages UI is local to kotoba-lang and does not redirect to external hosts.

## Test

```bash
clojure -X:test
```

# drive

[![CI](https://github.com/kotoba-lang/drive/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/drive/actions/workflows/ci.yml)

Portable CLJC model for kotoba-lang/drive.

`drive.workspace` adds tenant hierarchy, owner/editor/commenter/viewer ACLs,
immutable object versions, trash/restore and byte-quota enforcement. Blob I/O
remains a host capability; versions store opaque object references.

Pages editor: https://kotoba-lang.github.io/drive/

The Pages UI is local to kotoba-lang and does not redirect to external hosts.

## Test

```bash
clojure -X:test
```

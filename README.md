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

## Where the bytes are

`drive.object` is that host boundary, and it is in this library rather than in
each consumer for one reason: the rules only work if there is one of them. A
permission boundary filled privately by three applications gives three answers
to *may this principal read this file*.

```clojure
(require '[drive.object :as object] '[drive.store.memory :as mem])

(let [store (mem/store)
      {:keys [workspace]} (object/write-item ws store "plan" "alice" bytes
                                             {:object-ref "obj-1"})]
  (object/read-item workspace store "plan" "bob"))
;; {:ok? false :reason :not-permitted}
```

An `IObjectStore` is four operations over opaque references and knows nothing
about principals, roles, share links, trash or quota. Everything in
`drive.object` exists so that it is only reached after the answer is yes.

**Bytes are a vector of unsigned ints, in both directions.** The protocol did
not say so at first, and within hours there were two implementations that
disagreed — `drive.store.memory` passes vectors and a Filecoin-backed store in
another repository passes `byte[]`. Both are reasonable; the protocol not
saying was not. Unstated, the mismatch surfaces in whoever called `read-item`
rather than at the seam. `store-of` normalises both ways, and a backend that
wants arrays says so with `:bytes-out`.

Four rules that a straightforward implementation gets wrong:

- **Trashed items are not readable.** `can-read?` says nothing about trash —
  `visible-items` filters it separately — so composing `can-read?` with a
  store hands out deleted content.
- **Quota is checked before the bytes move.** `add-version` throws when the
  total would exceed, which is correct and too late: the upload has happened
  and the bandwidth is spent.
- **A new version needs a new object reference.** Reusing one silently
  replaces an earlier version's bytes while the history saying otherwise stays
  in `:drive/versions`.
- **A version records who wrote it, and the caller does not get to say who.**
  `:drive.version/author` is the principal `write-item` just checked against
  the ACL. An author passed in `opts` would be a history the caller can
  write; and the moment an item can be shared, a history that cannot say
  which of two writers made a version is a history of nothing.
- **Forgetting returns the quota.** A workspace counting bytes nobody can
  reach fills up for no reason anyone can see.

A write checks permission and quota first, then stores, then records the
version. A store failure leaves the workspace untouched — the model never
claims bytes that are not there. The other order strands objects nothing
references.

### Attaching a backend

`drive.store.memory` is portable and forgets everything. `drive.store.fs` is a
directory on a JVM, and refuses any reference that could name a file outside
it. Anything else attaches through `object/store-of`, which takes four
functions so that neither library has to depend on the other:

```clojure
(object/store-of {:get-object    #(storj/get-object client bucket %)
                  :put-object    #(storj/put-object client bucket %1 %2)
                  :delete-object #(storj/delete-object client bucket %)})
```

`kotoba-lang/io-storj` already exposes those verbs. Whoever builds the store
depends on both, and that is the application.

[![CI](https://github.com/kotoba-lang/drive/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/drive/actions/workflows/ci.yml)

Portable CLJC model for kotoba-lang/drive.

Pages editor: https://kotoba-lang.github.io/drive/

The Pages UI is local to kotoba-lang and does not redirect to external hosts.

## Test

```bash
clojure -X:test
```

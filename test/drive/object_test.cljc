(ns drive.object-test
  "Who may move which bytes.

  Every test here is a way the seam could be filled that reads correctly and
  hands out someone else's file. That is why the seam is in this library
  rather than in each consumer: the rules only work if there is one of them."
  (:require [clojure.test :refer [deftest is testing]]
            [drive.object :as object]
            [drive.store.memory :as mem]
            [drive.workspace :as ws]))

(def ^:private bytes-a [1 2 3 4 5 6])
(def ^:private bytes-b [9 9 9])

(defn- fixture
  "A workspace owned by alice with one file, and a store holding its bytes."
  []
  (let [store (mem/store)
        w     (-> (ws/workspace "acme" "alice" 1000)
                  (ws/create-file "plan" "root" {:drive/title "plan"} "alice"))
        r     (object/write-item w store "plan" "alice" bytes-a {:object-ref "obj-1"})]
    {:ws (:workspace r) :store store}))

;; ── reading ─────────────────────────────────────────────────────────────────

(deftest an-owner-reads-their-own-file
  (let [{:keys [ws store]} (fixture)
        r (object/read-item ws store "plan" "alice")]
    (is (:ok? r))
    (is (= bytes-a (:bytes r)))
    (is (= "obj-1" (:object-ref r)))))

(deftest a-stranger-reads-nothing
  (let [{:keys [ws store]} (fixture)
        r (object/read-item ws store "plan" "mallory")]
    (is (not (:ok? r)))
    (is (= :not-permitted (:reason r)))
    (is (nil? (:bytes r)) "and is not handed the bytes alongside the refusal")))

(deftest a-viewer-may-read
  (let [{:keys [ws store]} (fixture)
        ws (ws/grant ws "plan" "bob" :viewer)]
    (is (:ok? (object/read-item ws store "plan" "bob")))))

(deftest trashed-bytes-are-not-readable
  ;; can-read? says nothing about trash — visible-items filters it separately —
  ;; so a caller composing can-read? with a store hands out deleted content
  (let [{:keys [ws store]} (fixture)
        trashed (ws/trash ws "plan")]
    (is (ws/can-read? trashed "plan" "alice") "the ACL still says yes")
    (let [r (object/read-item trashed store "plan" "alice")]
      (is (not (:ok? r)))
      (is (= :item-is-trashed (:reason r))))
    (testing "and restoring makes them readable again, because trash is not deletion"
      (is (:ok? (object/read-item (ws/restore trashed "plan") store "plan" "alice"))))))

(deftest readable?-agrees-with-read-item
  (let [{:keys [ws store]} (fixture)]
    (is (object/readable? ws "plan" "alice"))
    (is (not (object/readable? ws "plan" "mallory")))
    (is (not (object/readable? (ws/trash ws "plan") "plan" "alice")))
    (is (not (object/readable? ws "nope" "alice")))
    (doseq [[who trashed?] [["alice" false] ["mallory" false] ["alice" true]]]
      (let [w (cond-> ws trashed? (ws/trash "plan"))]
        (is (= (object/readable? w "plan" who)
               (:ok? (object/read-item w store "plan" who)))
            (str who " trashed=" trashed?))))))

(deftest a-file-with-no-content-is-not-an-error-about-permission
  (let [store (mem/store)
        w (-> (ws/workspace "acme" "alice" 1000)
              (ws/create-file "empty" "root" {:drive/title "empty"} "alice"))
        r (object/read-item w store "empty" "alice")]
    (is (not (:ok? r)))
    (is (= :no-content (:reason r)))))

(deftest bytes-the-model-claims-and-the-store-does-not-have
  ;; a broken node, not a permission answer
  (let [{:keys [ws]} (fixture)
        empty-store (mem/store)
        r (object/read-item ws empty-store "plan" "alice")]
    (is (= :object-missing-from-store (:reason r)))))

(deftest folders-have-no-bytes
  (let [{:keys [ws store]} (fixture)]
    (is (= :folders-have-no-bytes (:reason (object/read-item ws store "root" "alice"))))))

;; ── share links ─────────────────────────────────────────────────────────────

(deftest a-share-link-reads-without-an-acl-entry
  (let [{:keys [ws store]} (fixture)
        ws (ws/create-share-link ws "tok" "plan" :viewer 100)
        r  (object/read-via-share-link ws store "tok" 50)]
    (is (:ok? r))
    (is (= bytes-a (:bytes r)))
    (is (= :viewer (:role r)))
    (is (not (ws/can-read? ws "plan" "anyone")) "and the ACL still says no")))

(deftest an-expired-link-is-a-link-that-never-existed
  (let [{:keys [ws store]} (fixture)
        ws (ws/create-share-link ws "tok" "plan" :viewer 100)]
    (is (= :no-such-link (:reason (object/read-via-share-link ws store "tok" 100))))
    (is (= :no-such-link (:reason (object/read-via-share-link ws store "tok" 999))))
    (testing "as is a revoked one"
      (is (= :no-such-link
             (:reason (object/read-via-share-link (ws/revoke-share-link ws "tok")
                                                  store "tok" 50)))))))

(deftest a-link-to-something-trashed-reads-nothing
  (let [{:keys [ws store]} (fixture)
        ws (-> ws (ws/create-share-link "tok" "plan" :viewer 100) (ws/trash "plan"))]
    (is (= :item-is-trashed (:reason (object/read-via-share-link ws store "tok" 50))))))

;; ── writing ─────────────────────────────────────────────────────────────────

(deftest writing-records-a-version-and-spends-quota
  (let [{:keys [ws]} (fixture)]
    (is (= (count bytes-a) (:drive.workspace/used-bytes ws)))
    (is (= 1 (count (:drive/versions (ws/item ws "plan")))))
    (is (= "obj-1" (:drive/object-ref (ws/item ws "plan"))))))

(deftest a-commenter-may-not-write
  (let [{:keys [ws store]} (fixture)
        ws (ws/grant ws "plan" "bob" :commenter)
        r  (object/write-item ws store "plan" "bob" bytes-b {:object-ref "obj-2"})]
    (is (= :not-permitted (:reason r)))
    (is (nil? (object/-get-object store "obj-2"))
        "and nothing was stored on the way to finding out")))

(deftest a-new-version-must-have-a-new-reference
  ;; reusing one silently replaces the bytes of an earlier version, and the
  ;; history that says otherwise is still sitting in :drive/versions
  (let [{:keys [ws store]} (fixture)
        r (object/write-item ws store "plan" "alice" bytes-b {:object-ref "obj-1"})]
    (is (= :object-ref-already-used (:reason r)))
    (is (= bytes-a (object/-get-object store "obj-1")) "the first version survives")))

(deftest quota-is-checked-before-the-bytes-move
  ;; add-version throws on this too, and by then the upload has happened
  (let [store (mem/store)
        w (-> (ws/workspace "acme" "alice" 4)
              (ws/create-file "plan" "root" {} "alice"))
        r (object/write-item w store "plan" "alice" [1 2 3 4 5] {:object-ref "big"})]
    (is (= :quota-exceeded (:reason r)))
    (is (nil? (object/-get-object store "big"))
        "nothing was uploaded before the refusal")))

(deftest a-write-without-a-reference-is-refused
  (let [{:keys [ws store]} (fixture)]
    (is (= :no-object-ref (:reason (object/write-item ws store "plan" "alice" bytes-b {}))))))

(deftest a-second-version-reads-back-as-the-second
  (let [{:keys [ws store]} (fixture)
        r  (object/write-item ws store "plan" "alice" bytes-b {:object-ref "obj-2"})
        ws (:workspace r)]
    (is (:ok? r))
    (is (= bytes-b (:bytes (object/read-item ws store "plan" "alice"))))
    (is (= 2 (count (:drive/versions (ws/item ws "plan")))))
    (is (= (+ (count bytes-a) (count bytes-b)) (:drive.workspace/used-bytes ws)))
    (testing "and the first version's bytes are still addressable"
      (is (= bytes-a (object/-get-object store "obj-1"))))))

;; ── forgetting ──────────────────────────────────────────────────────────────

(deftest forgetting-removes-every-version-and-returns-the-quota
  (let [{:keys [ws store]} (fixture)
        ws (:workspace (object/write-item ws store "plan" "alice" bytes-b {:object-ref "obj-2"}))
        r  (object/forget-item ws store "plan" "alice")]
    (is (:ok? r))
    (is (= 2 (:deleted r)))
    (is (nil? (object/-get-object store "obj-1")))
    (is (nil? (object/-get-object store "obj-2")))
    (is (= 0 (:drive.workspace/used-bytes (:workspace r)))
        "a workspace that counts bytes nobody can reach fills up for no visible reason")
    (is (= :no-content (:reason (object/read-item (:workspace r) store "plan" "alice"))))))

(deftest forgetting-needs-write-permission
  (let [{:keys [ws store]} (fixture)
        ws (ws/grant ws "plan" "bob" :viewer)
        r  (object/forget-item ws store "plan" "bob")]
    (is (= :not-permitted (:reason r)))
    (is (= bytes-a (object/-get-object store "obj-1")))))

;; ── the adapter ─────────────────────────────────────────────────────────────

(deftest store-of-wraps-four-functions
  ;; how a backend attaches without either library depending on the other
  (let [held (atom {})
        store (object/store-of {:get-object    #(get @held %)
                                :put-object    #(swap! held assoc %1 (vec %2))
                                :delete-object #(swap! held dissoc %)})
        w (-> (ws/workspace "acme" "alice" 100)
              (ws/create-file "plan" "root" {} "alice"))
        r (object/write-item w store "plan" "alice" bytes-a {:object-ref "k"})]
    (is (:ok? r))
    (is (= bytes-a (get @held "k")))
    (is (= bytes-a (:bytes (object/read-item (:workspace r) store "plan" "alice"))))
    (testing "exists? falls back to a get when it is not supplied"
      (is (object/-object-exists? store "k"))
      (is (not (object/-object-exists? store "nope"))))))

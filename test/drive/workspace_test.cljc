(ns drive.workspace-test
  (:require [clojure.test :refer [deftest is testing]]
            [drive.workspace :as workspace]))

(deftest drive-hierarchy-acl-versions-trash-and-quota
  (let [ws (-> (workspace/workspace "acme/ops" "alice" 10)
               (workspace/create-folder "projects" "root" "Projects" "alice")
               (workspace/create-file "plan" "projects" {:drive/title "Plan"} "alice")
               (workspace/grant "plan" "bob" :viewer)
               (workspace/add-version "plan" {:drive.version/id "v1"
                                                :drive.version/object-ref "r2:acme/plan/v1"
                                                :drive.version/size-bytes 6}))]
    (is (workspace/can-read? ws "plan" "bob"))
    (is (not (workspace/can-write? ws "plan" "bob")))
    (is (= "r2:acme/plan/v1" (:drive/object-ref (workspace/item ws "plan"))))
    (is (= 6 (:drive.workspace/used-bytes ws)))
    (is (empty? (workspace/visible-items (workspace/trash ws "plan") "bob")))
    (testing "every version consumes quota"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (workspace/add-version ws "plan" {:drive.version/id "v2"
                                                       :drive.version/object-ref "r2:v2"
                                                       :drive.version/size-bytes 5}))))))

(deftest inherited-permissions-search-and-share-links
  (let [ws (-> (workspace/workspace "w" "owner" 100)
               (workspace/create-folder "team" "root" "Team" "owner")
               (workspace/create-file "plan" "team" {:drive/title "Launch Plan"} "owner")
               (workspace/grant "team" "alice" :viewer)
               (workspace/create-share-link "secret" "plan" :viewer 2000))]
    (is (workspace/can-read? ws "plan" "alice"))
    (is (= ["plan" "team"] (mapv :drive/id (workspace/search ws "alice" ""))))
    (is (= "plan" (:drive.share/item-id (workspace/resolve-share-link ws "secret" 1000))))
    (is (nil? (workspace/resolve-share-link ws "secret" 2000)))
    (is (nil? (workspace/resolve-share-link (workspace/revoke-share-link ws "secret")
                                            "secret" 1000)))))

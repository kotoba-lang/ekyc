(ns ekyc.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [ekyc.core :as ekyc]))

(def valid-doc {:valid? true :extracted-data {:name "Ada Lovelace"} :reasons []})
(def invalid-doc {:valid? false :extracted-data {} :reasons [:expired]})

(def live-good {:live? true :match-score 0.93 :reasons []})
(def live-bad {:live? false :match-score 0.1 :reasons [:no-face-detected]})

(def no-hit {:hit? false :hits []})
(def hit {:hit? true :hits [{:list "OFAC" :match-score 0.99}]})

(deftest evaluate-verdict-priority-order
  (testing "invalid document → rejected, regardless of everything else"
    (is (= {:verdict :rejected :reasons [:expired]}
           (ekyc/evaluate-verdict {:document-result invalid-doc
                                    :liveness-result live-good
                                    :screening-result no-hit}))))

  (testing "invalid document wins even when there is ALSO a watchlist hit"
    (is (= {:verdict :rejected :reasons [:expired]}
           (ekyc/evaluate-verdict {:document-result invalid-doc
                                    :liveness-result live-good
                                    :screening-result hit}))))

  (testing "watchlist hit → review, never an auto-reject"
    (is (= {:verdict :review :reasons [:watchlist-hit]}
           (ekyc/evaluate-verdict {:document-result valid-doc
                                    :liveness-result live-good
                                    :screening-result hit}))))

  (testing "liveness failure → review"
    (is (= {:verdict :review :reasons [:no-face-detected]}
           (ekyc/evaluate-verdict {:document-result valid-doc
                                    :liveness-result live-bad
                                    :screening-result no-hit}))))

  (testing "everything clean → approved"
    (is (= {:verdict :approved :reasons []}
           (ekyc/evaluate-verdict {:document-result valid-doc
                                    :liveness-result live-good
                                    :screening-result no-hit})))))

(deftest match-score-threshold-boundary
  (testing "just below threshold → review, :low-face-match"
    (is (= {:verdict :review :reasons [:low-face-match]}
           (ekyc/evaluate-verdict {:document-result valid-doc
                                    :liveness-result {:live? true :match-score 0.84 :reasons []}
                                    :screening-result no-hit
                                    :match-score-threshold 0.85}))))

  (testing "exactly at threshold → approved (strict <, so a score equal to the threshold passes)"
    (is (= {:verdict :approved :reasons []}
           (ekyc/evaluate-verdict {:document-result valid-doc
                                    :liveness-result {:live? true :match-score 0.85 :reasons []}
                                    :screening-result no-hit
                                    :match-score-threshold 0.85}))))

  (testing "just above threshold → approved"
    (is (= {:verdict :approved :reasons []}
           (ekyc/evaluate-verdict {:document-result valid-doc
                                    :liveness-result {:live? true :match-score 0.86 :reasons []}
                                    :screening-result no-hit
                                    :match-score-threshold 0.85}))))

  (testing "default threshold (0.85) applies when omitted"
    (is (= {:verdict :review :reasons [:low-face-match]}
           (ekyc/evaluate-verdict {:document-result valid-doc
                                    :liveness-result {:live? true :match-score 0.5 :reasons []}
                                    :screening-result no-hit})))))

(deftest run-checks-wiring
  (testing "run-checks! wires the three mocks through to the same verdict evaluate-verdict alone produces, plus the audit trail"
    (let [result (ekyc/run-checks!
                  {:document-verifier (ekyc/mock-document-verifier valid-doc)
                   :liveness-check (ekyc/mock-liveness-check live-good)
                   :watchlist-screen (ekyc/mock-watchlist-screen no-hit)
                   :document-input {:document-type :passport}
                   :liveness-input {:selfie-image "..."}
                   :screening-input {:full-name "Ada Lovelace"}})]
      (is (= {:verdict :approved :reasons []}
             (select-keys result [:verdict :reasons])))
      (is (= valid-doc (:document-result result)))
      (is (= live-good (:liveness-result result)))
      (is (= no-hit (:screening-result result)))))

  (testing "run-checks! respects a custom match-score-threshold"
    (let [result (ekyc/run-checks!
                  {:document-verifier (ekyc/mock-document-verifier valid-doc)
                   :liveness-check (ekyc/mock-liveness-check {:live? true :match-score 0.5 :reasons []})
                   :watchlist-screen (ekyc/mock-watchlist-screen no-hit)
                   :document-input {}
                   :liveness-input {}
                   :screening-input {}
                   :match-score-threshold 0.3})]
      (is (= :approved (:verdict result))))))

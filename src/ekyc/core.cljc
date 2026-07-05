(ns ekyc.core
  "Electronic KYC verdict logic as pure data — combines document
  verification / liveness / watchlist-screening results (each an injected
  host capability; real OCR/document forensics, face-liveness, and
  sanctions/PEP screening cannot be portable `.cljc`) into one auditable
  approve/review/reject decision with reasons. This repo owns the
  business rules, not the checks themselves.

  Priority order in `evaluate-verdict` (only the first matching branch's
  reasons are ever populated):
    1. invalid document      → :rejected
    2. watchlist hit         → :review (never an auto-reject — a
                                sanctions/PEP hit is always routed to a
                                human, by deliberate compliance choice)
    3. liveness failure      → :review
    4. low face-match score  → :review
    5. otherwise             → :approved")

(defprotocol IDocumentVerifier
  "Real document forensics (OCR, tamper/expiry detection) — host-injected."
  (-verify-document [this input]
    "input: {:document-type :front-image :back-image}.
    Returns {:valid? bool :extracted-data {...} :reasons [keyword...]} —
    reasons populated on invalid, e.g. :expired/:tampering-detected/:unreadable."))

(defprotocol ILivenessCheck
  "Real face-liveness + document-photo match — host-injected."
  (-check-liveness [this input]
    "input: {:selfie-image :challenge-response}.
    Returns {:live? bool :match-score (0.0-1.0) :reasons [keyword...]}."))

(defprotocol IWatchlistScreen
  "Real sanctions/PEP screening — host-injected."
  (-screen [this input]
    "input: {:full-name :date-of-birth :nationality}.
    Returns {:hit? bool :hits [{:list :match-score}...]}."))

;; ───────────────────────── test-only deterministic doubles ─────────────────────────

(defn mock-document-verifier
  "TEST-ONLY double. Always returns the given `result` map regardless of input."
  [result]
  (reify IDocumentVerifier
    (-verify-document [_ _] result)))

(defn mock-liveness-check
  "TEST-ONLY double. Always returns the given `result` map regardless of input."
  [result]
  (reify ILivenessCheck
    (-check-liveness [_ _] result)))

(defn mock-watchlist-screen
  "TEST-ONLY double. Always returns the given `result` map regardless of input."
  [result]
  (reify IWatchlistScreen
    (-screen [_ _] result)))

;; ───────────────────────────────── verdict logic ─────────────────────────────────

(defn evaluate-verdict
  "Pure business-rule combination — no I/O. See namespace docstring for the
  priority order. Always returns {:verdict :reasons}, never throws."
  [{:keys [document-result liveness-result screening-result match-score-threshold]
    :or {match-score-threshold 0.85}}]
  (cond
    (not (:valid? document-result))
    {:verdict :rejected :reasons (:reasons document-result)}

    (:hit? screening-result)
    {:verdict :review :reasons [:watchlist-hit]}

    (not (:live? liveness-result))
    {:verdict :review :reasons (:reasons liveness-result)}

    (< (:match-score liveness-result) match-score-threshold)
    {:verdict :review :reasons [:low-face-match]}

    :else
    {:verdict :approved :reasons []}))

(defn run-checks!
  "Orchestrates the three injected capabilities then `evaluate-verdict`.
  Returns the full audit trail — {:verdict :reasons :document-result
  :liveness-result :screening-result} — a compliance record needs to show
  what was checked, not just the outcome."
  [{:keys [document-verifier liveness-check watchlist-screen
           document-input liveness-input screening-input
           match-score-threshold]}]
  (let [document-result (-verify-document document-verifier document-input)
        liveness-result (-check-liveness liveness-check liveness-input)
        screening-result (-screen watchlist-screen screening-input)
        verdict (evaluate-verdict (cond-> {:document-result document-result
                                            :liveness-result liveness-result
                                            :screening-result screening-result}
                                    match-score-threshold
                                    (assoc :match-score-threshold match-score-threshold)))]
    (merge verdict
           {:document-result document-result
            :liveness-result liveness-result
            :screening-result screening-result})))

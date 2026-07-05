# kotoba-lang/ekyc

[![CI](https://github.com/kotoba-lang/ekyc/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/ekyc/actions/workflows/ci.yml)

Electronic KYC verdict logic as pure `.cljc` data — combines document
verification, face-liveness, and sanctions/PEP-watchlist screening results
into one auditable approve/review/reject decision with reasons. Every
namespace is `.cljc`, with zero third-party runtime deps.

## Why this is only the verdict logic

Real document forensics (OCR, tamper/expiry detection), face-liveness
matching, and sanctions/PEP screening all require actual ML models and
external data feeds — none of that is portable `.cljc`. This repo does
not implement any of it. Instead it defines three small host-injected
protocols (`IDocumentVerifier`, `ILivenessCheck`, `IWatchlistScreen`) that
a real host wires up to its actual document-forensics/liveness/screening
services, plus one pure function — `evaluate-verdict` — that combines
their results into a documented, auditable decision.

Priority order (only the first matching branch's reasons are ever
populated):

1. **invalid document** → `:rejected`
2. **watchlist hit** → `:review` — a sanctions/PEP hit is a deliberate
   compliance choice to **never auto-reject**; it always routes to a
   human.
3. **liveness failure** → `:review`
4. **low face-match score** → `:review`
5. otherwise → `:approved`

## Usage

```clojure
(require '[ekyc.core :as ekyc])

(ekyc/run-checks!
 {:document-verifier my-real-document-verifier
  :liveness-check my-real-liveness-check
  :watchlist-screen my-real-watchlist-screen
  :document-input {:document-type :passport :front-image ... :back-image ...}
  :liveness-input {:selfie-image ... :challenge-response ...}
  :screening-input {:full-name "Ada Lovelace" :date-of-birth "1815-12-10" :nationality "GB"}})
;; => {:verdict :approved, :reasons [], :document-result {...}, :liveness-result {...}, :screening-result {...}}
```

`run-checks!` returns the full audit trail (not just the verdict) — a
compliance record needs to show what was checked, not only the outcome.
Call `evaluate-verdict` directly if you already have the three check
results and just need the rule applied.

## Test

```bash
clojure -M:test
```

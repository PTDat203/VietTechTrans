# Phase 6 — CORE MT baseline

Run exactly three model families: OPUS-MT, EnViT5 and M2M-100. Baseline means inference from official pretrained checkpoints only: no fine-tuning, no use of IT `train`, and no checkpoint selection from either final test set.

The entry condition is `PHASE_05_GATE=PASS`. For each model and each direction (EN→VI, VI→EN), resolve the official checkpoint to a commit SHA and save it with the protocol in `resolved_config.json`.

Each run evaluates General Test and IT Test separately. Report chrF++ as primary and SacreBLEU as secondary, then report model size, peak RSS, median latency, and p95 latency. Required artifacts are defined in `configs/evaluation_protocol_v1.json`.

Do not calculate a combined General/IT score. Do not select a teacher, student, adaptation plan, quantization strategy, or deployment candidate until every cell of the CORE comparison table is present.

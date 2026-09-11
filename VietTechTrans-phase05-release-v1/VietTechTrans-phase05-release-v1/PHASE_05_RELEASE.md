# Phase 05 release gate

Phase 06 may start only after this command succeeds. Với máy mới, ưu tiên tải
`phase05_release_v1.zip` từ GitHub Release thay vì cố chạy lại toàn bộ Phase
01–05:

```powershell
python tools/verify_phase05_release.py --archive .\phase05_release_v1.zip
Expand-Archive .\phase05_release_v1.zip -DestinationPath .
python tools/validate_phase05_gate.py
```

Release bundle chứa `data/processed/it_en_vi_v1` và
`data/processed/general_test_flores200_devtest_v1`, gồm manifest/checksum và
split cần cho Phase 06. Nó không chứa raw data hoặc checkpoint model.

The General Test is FLORES-200 `devtest` (`eng_Latn` ↔ `vie_Latn`), versioned as `general_test_flores200_devtest_v1`. It is external to the project’s five IT-corpus sources and is licensed CC-BY-SA 4.0. It is evaluation-only: never use it for training, checkpoint/hyperparameter selection, taxonomy tuning, or manual repair.

The release gate verifies manifests and checksums plus normalized pair and English-source overlap between General Test and IT Test. `train` and `validation` remain in the existing `it_en_vi_v1` dataset release; use validation only for selection during later adaptation, never for a final reported result.

`configs/evaluation_protocol_v1.json` was superseded before any completed
baseline run. Phase 06 uses the frozen
`configs/evaluation_protocol_v2.json`, which scores quality once on every
released sentence and measures latency on a deterministic sample. A change to
test data, metric, decoding setting, hardware measurement policy or reporting
rule requires a new protocol/dataset version and invalidates prior comparisons.

# Phase 05 release gate

Phase 06 may start only after this command succeeds:

```powershell
Invoke-WebRequest -Uri https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz -OutFile data/raw/flores200/flores200_dataset.tar.gz
tar -xzf data/raw/flores200/flores200_dataset.tar.gz -C data/raw/flores200 ./flores200_dataset/devtest/eng_Latn.devtest ./flores200_dataset/devtest/vie_Latn.devtest
python tools/build_general_test.py --en <path-to-eng_Latn.devtest> --vi <path-to-vie_Latn.devtest>
python tools/validate_phase05_gate.py
```

The General Test is FLORES-200 `devtest` (`eng_Latn` ↔ `vie_Latn`), versioned as `general_test_flores200_devtest_v1`. It is external to the project’s five IT-corpus sources and is licensed CC-BY-SA 4.0. It is evaluation-only: never use it for training, checkpoint/hyperparameter selection, taxonomy tuning, or manual repair.

The release gate verifies manifests and checksums plus normalized pair and English-source overlap between General Test and IT Test. `train` and `validation` remain in the existing `it_en_vi_v1` dataset release; use validation only for selection during later adaptation, never for a final reported result.

`configs/evaluation_protocol_v1.json` is frozen before the first baseline run. A change to any test data, metric, decoding setting, hardware measurement policy, or reporting rule requires a new protocol/dataset version and invalidates prior baseline comparisons.

"""Runner chung được hiện thực lần đầu khi có nhánh OPUS-MT.

Runner không chứa ID checkpoint hay prefix OPUS. Nó gọi adapter theo contract,
dịch hai test set riêng, rồi ghi evidence cùng schema để EnViT5 và M2M-100 có
thể dùng lại nguyên vẹn ở các phase sau.
"""
from __future__ import annotations

import platform
import shutil
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .artifacts import REQUIRED_ARTIFACTS, assert_new_run_directory, write_json, write_jsonl
from .benchmark import deterministic_sample, summarize_latency
from .contracts import TranslationAdapter
from .data import LockedEvaluationSet
from .metrics import score_predictions
from .protocol import EvaluationProtocol


def run_baseline(adapter: TranslationAdapter, protocol: EvaluationProtocol, datasets: tuple[LockedEvaluationSet, ...], output: Path, device: str, memory_bytes: Callable[[], int], synchronize: Callable[[], None], progress: Callable[[str], None] | None = None, progress_interval: int = 250, preview_rows: int = 2) -> None:
    """Run one model × direction while preserving General/IT separation.

    Quality translates every released sentence exactly once. Latency then uses
    a fixed row-ID sample, so the benchmark remains reproducible without five
    redundant full-corpus translations.
    """
    if tuple(dataset.name for dataset in datasets) != protocol.evaluation_sets:
        raise ValueError("Dataset order must match frozen evaluation protocol.")
    if progress_interval < 0 or preview_rows < 0:
        raise ValueError("Progress interval and preview rows must be non-negative.")
    assert_new_run_directory(output)
    temporary = output.with_name(output.name + ".in_progress")
    assert_new_run_directory(temporary)
    temporary.mkdir(parents=True)
    try:
        log_path = temporary / "run.log"

        def emit(message: str) -> None:
            with log_path.open("a", encoding="utf-8") as handle:
                handle.write(message + "\n")
            if progress:
                progress(message)

        emit(f"Starting model={adapter.model_key}; direction={adapter.direction.value}; device={device}")
        adapter.load()
        metadata = adapter.metadata()
        emit(f"Resolved checkpoint={metadata.get('model_id')}; revision={metadata.get('resolved_revision')}")
        write_json(temporary / "resolved_config.json", {
            "protocol_version": protocol.protocol_version, "dataset_release": protocol.dataset_release,
            "model": adapter.model_key, "direction": adapter.direction.value, "device": device,
            "generation": {"seed": protocol.inference.seed, "num_beams": protocol.inference.num_beams, "max_new_tokens": protocol.inference.max_new_tokens, "batch_size": protocol.inference.batch_size},
            "adapter": metadata,
        })
        all_predictions: list[dict[str, object]] = []
        metrics, benchmark = {}, {}
        for dataset in datasets:
            examples = dataset.examples(adapter.direction)
            peak_rss = memory_bytes()
            emit(f"[{dataset.name}] quality pass 1/1: {len(examples)} released sentences")
            predictions = []
            for index, (row_id, source, reference) in enumerate(examples, start=1):
                prediction = adapter.translate(source, num_beams=protocol.inference.num_beams, max_new_tokens=protocol.inference.max_new_tokens)
                peak_rss = max(peak_rss, memory_bytes())
                predictions.append({"test_set": dataset.name, "row_id": row_id, "source": source, "reference": reference, "prediction": prediction})
                if index <= preview_rows:
                    emit(f"[{dataset.name}] quality preview {index}: source={source[:180]!r}")
                    emit(f"[{dataset.name}] quality preview {index}: prediction={prediction[:180]!r}")
                if progress_interval and (index % progress_interval == 0 or index == len(examples)):
                    emit(f"[{dataset.name}] quality: {index}/{len(examples)} sentences")
            all_predictions.extend(predictions)
            metrics[dataset.name] = score_predictions([str(row["prediction"]) for row in predictions], [str(row["reference"]) for row in predictions])
            probe = deterministic_sample(examples, lambda row: row[0], protocol.benchmark.latency_sample_size, protocol.benchmark.sampling_seed)
            emit(f"[{dataset.name}] latency probe: {len(probe)} deterministic sentences × {protocol.benchmark.latency_repeats} repeats")
            for _, source, _ in probe[:protocol.benchmark.warmup_sentences]:
                adapter.translate(source, num_beams=protocol.inference.num_beams, max_new_tokens=protocol.inference.max_new_tokens)
            synchronize()
            samples = []
            for repeat in range(protocol.benchmark.latency_repeats):
                emit(f"[{dataset.name}] repeat {repeat + 1}/{protocol.benchmark.latency_repeats} started")
                for index, (_, source, _) in enumerate(probe, start=1):
                    synchronize(); started = time.perf_counter()
                    adapter.translate(source, num_beams=protocol.inference.num_beams, max_new_tokens=protocol.inference.max_new_tokens)
                    synchronize(); samples.append((time.perf_counter() - started) * 1000)
                    peak_rss = max(peak_rss, memory_bytes())
                    if progress_interval and (index % progress_interval == 0 or index == len(probe)):
                        emit(f"[{dataset.name}] repeat {repeat + 1}: {index}/{len(probe)} probe sentences")
            benchmark[dataset.name] = {"quality_sentences": len(examples), "quality_passes": protocol.benchmark.quality_passes, "latency_sample_size": len(probe), "latency_sample_seed": protocol.benchmark.sampling_seed, "warmup_sentences": min(len(probe), protocol.benchmark.warmup_sentences), "latency_repeats": protocol.benchmark.latency_repeats, "latency_unit": protocol.benchmark.latency_unit, "peak_process_rss_bytes": peak_rss, **summarize_latency(samples)}
            emit(f"[{dataset.name}] chrF++={metrics[dataset.name]['chrF++']:.4f}; SacreBLEU={metrics[dataset.name]['sacreBLEU']:.4f}")
        write_jsonl(temporary / "predictions.jsonl", all_predictions)
        write_json(temporary / "metrics.json", {"model": adapter.model_key, "direction": adapter.direction.value, "by_test_set": metrics})
        write_json(temporary / "benchmark.json", {"model": adapter.model_key, "direction": adapter.direction.value, "metadata": metadata, "by_test_set": benchmark})
        (temporary / "environment.txt").write_text(f"created_at_utc={datetime.now(timezone.utc).isoformat()}\nplatform={platform.platform()}\n", encoding="utf-8")
        emit("Baseline completed successfully.")
        missing = [name for name in REQUIRED_ARTIFACTS if not (temporary / name).is_file()]
        if missing:
            raise RuntimeError(f"Missing required artifacts: {missing}")
        temporary.replace(output)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise

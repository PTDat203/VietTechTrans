"""Metric tự động cho so sánh Core MT ở Phase 2.

chrF++ là metric chính; SacreBLEU là metric phụ. Hàm này nhận prediction và
reference đã có sẵn, nên không biết model nào sinh chúng và không thể gộp
General Test với IT Test.
"""
from __future__ import annotations


def score_predictions(predictions: list[str], references: list[str]) -> dict[str, object]:
    """Chấm một tập validation; không tạo điểm tổng hợp để chọn Core MT."""
    if len(predictions) != len(references) or not predictions:
        raise ValueError("Predictions and references must be non-empty and aligned.")
    try:
        import sacrebleu
    except ImportError as error:
        raise RuntimeError("Install sacrebleu before computing baseline metrics.") from error
    return {
        "chrF++": sacrebleu.corpus_chrf(predictions, [references], beta=2, word_order=2).score,
        "sacreBLEU": sacrebleu.corpus_bleu(predictions, [references], tokenize="none", lowercase=False, use_effective_order=False).score,
        "sacrebleu_version": sacrebleu.__version__,
        "metric_config": {
            "chrF++": {"beta": 2, "word_order": 2},
            "sacreBLEU": {"tokenize": "none", "lowercase": False, "use_effective_order": False},
        },
    }

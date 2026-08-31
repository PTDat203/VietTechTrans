"""Preview first, then persist reproducible Phase 05 dataset evidence."""
from __future__ import annotations
import hashlib, json, re, unicodedata
from datetime import datetime, timezone
from pathlib import Path
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ["envitech_reasoning", "tech_viet_translation", "gnome", "ubuntu", "kde4"]
VERSION, SEED = "it_en_vi_v1", 42
RATIOS = {"train": .80, "validation": .10, "it_test": .10}
OUT, FINAL = ROOT / "data" / "processed" / VERSION, ROOT / "data" / "final_report" / VERSION

def sha(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""): h.update(b)
    return h.hexdigest()

def key(value): return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", str(value)).casefold()).strip()
def tokens(value): return re.findall(r"[^\W_]+(?:[-'][^\W_]+)*", key(value), flags=re.UNICODE)
def primary(value): return next(iter([x for x in str(value).split(";") if x]), "unclassified")
def stable(value): return int(hashlib.sha256(f"{SEED}:{value}".encode()).hexdigest()[:16], 16)
def ready(v):
    if isinstance(v, dict): return {str(k): ready(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)): return [ready(x) for x in v]
    if hasattr(v, "item"): return v.item()
    return None if pd.isna(v) else v

def load_sources():
    frames, provenance = [], []
    for source in SOURCES:
        folder = ROOT / "data" / "it_corpus" / source
        report_p, manifest_p, pairs_p = folder / "it_filtering_report.json", folder / "it_filtering_manifest.json", folder / "approved_it_pairs.parquet"
        assert all(p.is_file() for p in (report_p, manifest_p, pairs_p)), f"Thiếu Phase 04 artifact: {source}"
        report, manifest = json.loads(report_p.read_text(encoding="utf-8")), json.loads(manifest_p.read_text(encoding="utf-8"))
        assert report["decision"]["approved_for_dataset_building"] is True and report["manual_review_remaining"] == 0
        artifacts = {x["path"].replace("\\", "/"): x for x in manifest["artifacts"]}
        assert sha(pairs_p) == artifacts[pairs_p.relative_to(ROOT).as_posix()]["sha256"], f"Checksum lỗi: {source}"
        df = pd.read_parquet(pairs_p)
        assert {"source_short_name", "raw_row_index", "en_clean", "vi_clean", "pair_sha256", "it_subdomain"} <= set(df.columns)
        frames.append(df)
        provenance.append({"source_short_name": source, "approved_rows": len(df), "it_filtering_report_sha256": sha(report_p), "it_filtering_manifest_sha256": sha(manifest_p), "approved_pairs_sha256": sha(pairs_p)})
    return pd.concat(frames, ignore_index=True), provenance

def dedup_and_split(pairs):
    x = pairs.copy(); x["en_match_key"] = x.en_clean.map(key); x["vi_match_key"] = x.vi_clean.map(key)
    x["global_pair_key"] = x.en_match_key + "\u241f" + x.vi_match_key
    x["source_order"] = x.source_short_name.map({s: i for i, s in enumerate(SOURCES)})
    x = x.sort_values(["source_order", "raw_row_index"], kind="stable")
    dups = x[x.duplicated("global_pair_key", keep="first")].copy(); dups["phase_05_rejection_reason"] = "global_exact_duplicate"
    x = x.drop_duplicates("global_pair_key", keep="first").drop(columns="source_order").copy()
    x["leakage_group_id"] = x.en_match_key.map(lambda t: hashlib.sha256(t.encode()).hexdigest())
    reps = x.sort_values(["source_short_name", "raw_row_index"], kind="stable").drop_duplicates("leakage_group_id")[["leakage_group_id", "source_short_name", "it_subdomain"]].copy()
    reps["stratum"] = reps.source_short_name + "|" + reps.it_subdomain.map(primary)
    assign = {}
    for _, group in reps.groupby("stratum", sort=True):
        ids, n = sorted(group.leakage_group_id, key=stable), len(group)
        cut1, cut2 = max(1, round(n * RATIOS["train"])), max(1, round(n * RATIOS["train"])) + round(n * RATIOS["validation"])
        for i, gid in enumerate(ids): assign[gid] = "train" if i < cut1 else "validation" if i < cut2 else "it_test"
    x["split"] = x.leakage_group_id.map(assign)
    x["dataset_row_id"] = x.apply(lambda r: hashlib.sha256(f"{r.source_short_name}:{r.raw_row_index}:{r.global_pair_key}".encode()).hexdigest(), axis=1)
    assert x.dataset_row_id.is_unique and not x.duplicated("global_pair_key").any()
    return x, dups

def profile(dataset):
    x = dataset.assign(primary_subdomain=dataset.it_subdomain.map(primary)); rows = []
    for (split, source, domain), g in x.groupby(["split", "source_short_name", "primary_subdomain"]):
        et, vt = [t for s in g.en_clean for t in tokens(s)], [t for s in g.vi_clean for t in tokens(s)]
        ec, vc = g.en_clean.str.len(), g.vi_clean.str.len()
        rows.append({"split":split,"source_short_name":source,"primary_subdomain":domain,"pairs":len(g),"en_chars_mean":ec.mean(),"vi_chars_mean":vc.mean(),"en_tokens_mean":len(et)/len(g),"vi_tokens_mean":len(vt)/len(g),"length_ratio_vi_over_en_mean":(vc/ec.clip(lower=1)).mean(),"en_vocab_size":len(set(et)),"vi_vocab_size":len(set(vt)),"en_type_token_ratio":len(set(et))/len(et) if et else 0,"vi_type_token_ratio":len(set(vt))/len(vt) if vt else 0})
    return pd.DataFrame(rows).sort_values(["split", "source_short_name", "primary_subdomain"]).reset_index(drop=True)

def distribution(dataset):
    x = dataset.assign(primary_subdomain=dataset.it_subdomain.map(primary)).groupby(["split", "primary_subdomain"]).size().rename("pairs").reset_index()
    x["split_pairs"] = x.groupby("split").pairs.transform("sum"); x["percentage"] = 100*x.pairs/x.split_pairs
    return x.sort_values(["primary_subdomain", "split"]).reset_index(drop=True)

def leakage(dataset):
    def grams(series):
        return set().union(*[{tuple(z[i:i+5]) for i in range(len(z)-4)} for z in map(tokens, series)])
    result = {"ngram_size":5,"normalized_exact_pair_overlap":{},"english_leakage_group_overlap":{},"train_ngram_overlap":{}}
    train = dataset[dataset["split"] == "train"]; tg = grams(train.en_clean)
    for split in ("validation", "it_test"):
        test = dataset[dataset["split"] == split]; eg = grams(test.en_clean); common = tg & eg
        result["normalized_exact_pair_overlap"][split] = len(set(train.global_pair_key) & set(test.global_pair_key))
        result["english_leakage_group_overlap"][split] = len(set(train.leakage_group_id) & set(test.leakage_group_id))
        result["train_ngram_overlap"][split] = {"shared_unique_5grams":len(common),"evaluation_unique_5grams":len(eg),"percentage_of_evaluation_5grams_seen_in_train":round(100*len(common)/len(eg),4) if eg else 0}
    assert all(v == 0 for v in result["normalized_exact_pair_overlap"].values()) and all(v == 0 for v in result["english_leakage_group_overlap"].values())
    return result

def attrition(final_rows):
    raw = sum(x["raw_rows"] for x in json.loads((ROOT / "data" / "audit" / "cross_source_audit_summary.json").read_text()))
    clean = sum(json.loads((ROOT / "data" / "interim" / s / "cleaning_report.json").read_text())["kept_rows"] for s in SOURCES)
    approved = sum(json.loads((ROOT / "data" / "it_corpus" / s / "it_filtering_report.json").read_text())["decision_counts"].get("approve", 0) for s in SOURCES)
    return pd.DataFrame({"stage":["RAW collected","After rule-based cleaning","After IT filtering","After global exact deduplication"],"pairs":[raw,clean,approved,final_rows]})

def create_figures(attr, dist, dataset):
    import plotly.express as px
    import plotly.graph_objects as go
    funnel = go.Figure(go.Funnel(y=attr.stage,x=attr.pairs,textinfo="value+percent initial")); funnel.update_layout(title="Data attrition funnel")
    bar = px.bar(dist,x="primary_subdomain",y="percentage",color="split",barmode="group",title="Subdomain distribution by split",labels={"percentage":"Share of split (%)","primary_subdomain":"Primary IT subdomain"})
    x=dataset.assign(primary_subdomain=dataset.it_subdomain.map(primary)); nodes=["s:"+z for z in SOURCES]+["d:"+z for z in sorted(x.primary_subdomain.unique())]+["p:"+z for z in RATIOS]; labels=SOURCES+sorted(x.primary_subdomain.unique())+list(RATIOS); pos={n:i for i,n in enumerate(nodes)}; a=[]
    for (s,d),v in x.groupby(["source_short_name","primary_subdomain"]).size().items(): a.append((pos["s:"+s],pos["d:"+d],int(v)))
    for (d,p),v in x.groupby(["primary_subdomain","split"]).size().items(): a.append((pos["d:"+d],pos["p:"+p],int(v)))
    sankey=go.Figure(go.Sankey(node={"label":labels,"pad":14,"thickness":16},link={"source":[z[0] for z in a],"target":[z[1] for z in a],"value":[z[2] for z in a]})); sankey.update_layout(title="Source → primary subdomain → split")
    return {"data_attrition_funnel":funnel,"subdomain_split_distribution":bar,"source_subdomain_split_sankey":sankey}

def prepare_phase05_evidence():
    approved, provenance = load_sources(); dataset, dups = dedup_and_split(approved); dist = distribution(dataset); leak = leakage(dataset)
    report={"dataset_schema_version":"1.1","dataset_version":VERSION,"prepared_at_utc":datetime.now(timezone.utc).isoformat(),"split_seed":SEED,"split_ratios_target":RATIOS,"input_approved_rows":len(approved),"global_exact_duplicates_removed":len(dups),"rows_after_global_deduplication":len(dataset),"split_counts":{s:int((dataset["split"]==s).sum()) for s in RATIOS},"leakage_check":leak,"source_inputs":provenance,"human_validation_note":"Five-row illustrative worksheet; not a statistical estimate."}
    sample=dataset.sort_values("dataset_row_id")[["dataset_row_id","split","source_short_name","it_subdomain","en_clean","vi_clean"]].head(5).copy()
    for c in ("reviewer_it_relevant","reviewer_translation_aligned","reviewer_notes"): sample[c]=""
    return {"dataset":dataset,"global_duplicates":dups,"profile":profile(dataset),"distribution":dist,"leakage":leak,"attrition":attrition(len(dataset)),"human_sample":sample,"report":report}

def save_phase05_outputs(evidence, figures=None):
    OUT.mkdir(parents=True,exist_ok=True); FINAL.mkdir(parents=True,exist_ok=True); x=evidence["dataset"]; files=[]
    cols=["dataset_row_id","split","source_short_name","raw_row_index","en_clean","vi_clean","it_subdomain","classification_method","pair_sha256","leakage_group_id"]
    for split in RATIOS:
        f=x[x["split"]==split][cols].sort_values("dataset_row_id"); p=OUT/f"{split}.parquet"; f.to_parquet(p,index=False); files.append(p); p=OUT/f"{split}.jsonl"; f.to_json(p,orient="records",lines=True,force_ascii=False); files.append(p)
    p=OUT/"global_duplicates_removed.parquet"; evidence["global_duplicates"].to_parquet(p,index=False); files.append(p)
    p=OUT/"dataset_report.json"; p.write_text(json.dumps(ready(evidence["report"]),ensure_ascii=False,indent=2),encoding="utf-8"); files.append(p)
    p=OUT/"dataset_manifest.json"; p.write_text(json.dumps({"manifest_schema_version":"1.1","dataset_version":VERSION,"split_seed":SEED,"artifacts":[{"path":z.relative_to(ROOT).as_posix(),"bytes":z.stat().st_size,"sha256":sha(z)} for z in files]},ensure_ascii=False,indent=2),encoding="utf-8")
    reports=[]
    for name, frame in (("corpus_profile.csv",evidence["profile"]),("subdomain_split_distribution.csv",evidence["distribution"]),("data_attrition_funnel.csv",evidence["attrition"]),("human_validation_sample_head5.csv",evidence["human_sample"])):
        p=FINAL/name; frame.to_csv(p,index=False,encoding="utf-8-sig"); reports.append(p)
    for name, obj in (("global_dedup_report.json",{"input_approved_rows":evidence["report"]["input_approved_rows"],"global_exact_duplicates_removed":evidence["report"]["global_exact_duplicates_removed"],"retained_rows":evidence["report"]["rows_after_global_deduplication"]}),("leakage_ngram_report.json",evidence["leakage"])):
        p=FINAL/name; p.write_text(json.dumps(ready(obj),ensure_ascii=False,indent=2),encoding="utf-8"); reports.append(p)
    template = ROOT / "DATASET_CARD.md"
    assert template.is_file(), f"Thiếu Dataset Card template: {template}"
    replacements = {
        "{{DATASET_VERSION}}": VERSION,
        "{{INPUT_APPROVED_ROWS}}": f"{evidence['report']['input_approved_rows']:,}",
        "{{GLOBAL_DEDUP_REMOVED}}": f"{evidence['report']['global_exact_duplicates_removed']:,}",
        "{{FINAL_ROWS}}": f"{evidence['report']['rows_after_global_deduplication']:,}",
        "{{TRAIN_ROWS}}": f"{evidence['report']['split_counts']['train']:,}",
        "{{VALIDATION_ROWS}}": f"{evidence['report']['split_counts']['validation']:,}",
        "{{IT_TEST_ROWS}}": f"{evidence['report']['split_counts']['it_test']:,}",
        "{{SPLIT_SEED}}": str(SEED),
    }
    card = template.read_text(encoding="utf-8")
    for placeholder, value in replacements.items(): card = card.replace(placeholder, value)
    p=FINAL/"DATASET_CARD.md"; p.write_text(card,encoding="utf-8"); reports.append(p)
    if figures:
        for name, fig in figures.items(): p=FINAL/f"{name}.html"; fig.write_html(p,include_plotlyjs="cdn"); reports.append(p)
    p=FINAL/"final_report_manifest.json"; p.write_text(json.dumps({"manifest_schema_version":"1.0","dataset_version":VERSION,"split_seed":SEED,"artifacts":[{"path":z.relative_to(ROOT).as_posix(),"bytes":z.stat().st_size,"sha256":sha(z)} for z in reports]},ensure_ascii=False,indent=2),encoding="utf-8")
    return {"dataset_manifest":OUT/"dataset_manifest.json","final_report_manifest":p}

if __name__ == "__main__":
    e=prepare_phase05_evidence(); print(save_phase05_outputs(e,create_figures(e["attrition"],e["distribution"],e["dataset"])))

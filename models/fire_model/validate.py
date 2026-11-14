"""Folds, metrics, comparison tables and charts. Year-blocked splits only - never random.

Backcast folds run in the backcast direction (train on later years, test on earlier ones, and the reverse).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import mean_poisson_deviance, roc_auc_score

# one fixed colour per model (reference categorical slots 1-4, validated as adjacent pairs); actuals in ink
MODEL_COLORS = {"hybrid": "#2a78d6", "glm": "#eb6834", "stacked": "#1baf7a", "naive_ratio": "#eda100"}
MODEL_LABELS = {"naive_ratio": "Naive ratio", "glm": "GLM only", "hybrid": "Hybrid (GLM + RF)", "stacked": "Stacked"}
INK, INK_2, GRID = "#0b0b0b", "#52514e", "#e4e3df"


@dataclass(frozen=True)
class Fold:
    """Explicit train/test year sets (never random rows)."""
    name: str
    train: frozenset[int]
    test: frozenset[int]

    def train_set(self) -> set[int]:
        return set(self.train)

    def test_set(self) -> set[int]:
        return set(self.test)


def _years(r) -> frozenset[int]:
    a, b = int(r[0]), int(r[1])
    return frozenset(range(a, b + 1))


def backcast_folds(cfg) -> list[Fold]:
    """Fold A/B from config, plus optional leave-one-year-out. Raises if any fold leaks a year."""
    v = cfg.section("validation")
    folds = [Fold(k, _years(f["train"]), _years(f["test"])) for k, f in v["folds"].items()]
    if v.get("leave_one_year_out"):
        allv = _years(cfg.viirs_years)
        folds += [Fold(f"LOYO_{y}", allv - {y}, frozenset({y})) for y in sorted(allv)]
    check_no_leakage(folds)
    return folds


def check_no_leakage(folds: list[Fold]) -> None:
    for f in folds:
        if f.train & f.test:
            raise ValueError(f"fold {f.name}: years in both train and test: {sorted(f.train & f.test)}")


def split(df: pd.DataFrame, fold: Fold, target_col: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    obs = df[df[target_col].notna()]
    return (obs[obs["year"].isin(fold.train_set())].reset_index(drop=True),
            obs[obs["year"].isin(fold.test_set())].reset_index(drop=True))


def point_metrics(y: np.ndarray, pred: np.ndarray, q05: np.ndarray | None = None,
                  q95: np.ndarray | None = None) -> dict[str, float]:
    out = {
        "poisson_dev": float(mean_poisson_deviance(y, np.maximum(pred, 1e-6))),
        "mae": float(np.mean(np.abs(y - pred))),
        "rmse_log1p": float(np.sqrt(np.mean((np.log1p(y) - np.log1p(np.maximum(pred, 0))) ** 2))),
    }
    if q05 is not None:
        out["coverage_90"] = float(np.mean((y >= q05) & (y <= q95)))
    return out


def annual_totals(test: pd.DataFrame, y: np.ndarray, pred: np.ndarray) -> pd.DataFrame:
    return (pd.DataFrame({"region": test["region"], "year": test["year"], "actual": y, "pred": pred})
            .groupby(["region", "year"], as_index=False)[["actual", "pred"]].sum())


def region_bias(totals: pd.DataFrame) -> pd.DataFrame:
    """Per region: total bias % over test years and mean absolute annual-total error %."""
    t = totals.assign(ape=lambda d: np.abs(d["pred"] - d["actual"]) / d["actual"].clip(lower=1) * 100)
    g = t.groupby("region")
    return pd.DataFrame({
        "bias_pct": (g["pred"].sum() - g["actual"].sum()) / g["actual"].sum().clip(lower=1) * 100,
        "annual_mape_pct": g["ape"].mean(),
    }).reset_index()


def run_backcast_validation(df: pd.DataFrame, cfg, models_factory) -> dict[str, pd.DataFrame]:
    """Fit every comparator on identical folds. Returns metrics, region bias and annual totals."""
    rows, bias_rows, totals_rows = [], [], []
    tc = cfg.target_col
    for fold in backcast_folds(cfg):
        train, test = split(df, fold, tc)
        if train.empty or test.empty:
            continue
        y_tr, y_te = train[tc].to_numpy(float), test[tc].to_numpy(float)
        for name, model in models_factory().items():
            model.fit(train, y_tr)
            p = model.predict(test)
            m = point_metrics(y_te, p["pred"].to_numpy(), p["q05"].to_numpy(), p["q95"].to_numpy())
            tot = annual_totals(test, y_te, p["pred"].to_numpy())
            rb = region_bias(tot)
            notes = []
            glm = getattr(model, "glm", None) or (model if name == "glm" else None)
            if glm is not None and glm.design.dropped:
                notes.append("dropped (constant in training): " + ", ".join(glm.design.dropped))
            rows.append({"fold": fold.name, "train": f"{min(fold.train_set())}-{max(fold.train_set())}",
                         "test": f"{min(fold.test_set())}-{max(fold.test_set())}", "model": name,
                         **m, "abs_region_bias_pct": float(rb["bias_pct"].abs().mean()),
                         "annual_mape_pct": float(rb["annual_mape_pct"].mean()),
                         "extrapolated_share": float(p["extrapolated"].mean()), "n_test": len(test),
                         "notes": "; ".join(notes)})
            bias_rows.append(rb.assign(fold=fold.name, model=name))
            totals_rows.append(tot.assign(fold=fold.name, model=name))
            print(f"  fold {fold.name} {name:12s} dev={m['poisson_dev']:.3f} mae={m['mae']:.3f} "
                  f"cov90={m['coverage_90']:.3f} |bias|={rows[-1]['abs_region_bias_pct']:.1f}%", flush=True)
    res = pd.DataFrame(rows)
    naive = res[res["model"] == "naive_ratio"].set_index("fold")
    for metric in ("poisson_dev", "mae", "rmse_log1p"):
        base = res["fold"].map(naive[metric])
        res[f"{metric}_vs_naive_pct"] = (base - res[metric]) / base * 100
    # bias is already a percentage: compare in percentage points (positive = less biased than naive)
    res["abs_region_bias_vs_naive_pp"] = res["fold"].map(naive["abs_region_bias_pct"]) - res["abs_region_bias_pct"]
    return {"metrics": res, "region_bias": pd.concat(bias_rows, ignore_index=True),
            "annual_totals": pd.concat(totals_rows, ignore_index=True)}


def summarise(metrics: pd.DataFrame) -> pd.DataFrame:
    """Average over folds (A and B weigh equally), one row per model, in display order."""
    cols = ["poisson_dev", "mae", "rmse_log1p", "coverage_90", "abs_region_bias_pct", "annual_mape_pct",
            "poisson_dev_vs_naive_pct", "mae_vs_naive_pct", "rmse_log1p_vs_naive_pct", "abs_region_bias_vs_naive_pp"]
    order = [m for m in MODEL_LABELS if m in metrics["model"].unique()]
    return metrics.groupby("model")[cols].mean().loc[order].reset_index()


def recommend(summary: pd.DataFrame, default: str = "hybrid") -> tuple[str, str]:
    """Winner = lowest mean Poisson deviance; the default (hybrid) is kept unless another model beats it
    on deviance AND |region bias|."""
    s = summary.set_index("model")
    best = s["poisson_dev"].idxmin()
    if best == default or default not in s.index:
        return best, f"{best} has the lowest mean Poisson deviance."
    if s.loc[best, "abs_region_bias_pct"] < s.loc[default, "abs_region_bias_pct"]:
        return best, (f"{best} beats {default} on Poisson deviance ({s.loc[best, 'poisson_dev']:.3f} vs "
                      f"{s.loc[default, 'poisson_dev']:.3f}) and on |region bias|.")
    return default, (f"{best} has slightly lower deviance but {default} has lower |region bias| "
                     f"(what the calendar shows), so {default} stays the default.")


# ------------------------------------------------------------------------------------- forecast

def forecast_metrics(y: np.ndarray, pred: np.ndarray, p_any: np.ndarray) -> dict[str, float]:
    fire = (y > 0).astype(int)
    out = point_metrics(y, pred)
    out["brier"] = float(np.mean((p_any - fire) ** 2))
    out["auc"] = float(roc_auc_score(fire, p_any)) if 0 < fire.sum() < len(fire) else float("nan")
    return out


# ---------------------------------------------------------------------------------------- output

def to_markdown(df: pd.DataFrame, floatfmt: str = ".3f") -> str:
    def fmt(v):
        if isinstance(v, (float, np.floating)):
            return "" if np.isnan(v) else format(v, floatfmt)
        return str(v)
    head = "| " + " | ".join(map(str, df.columns)) + " |"
    sep = "| " + " | ".join("---" for _ in df.columns) + " |"
    body = ["| " + " | ".join(fmt(v) for v in row) + " |" for row in df.itertuples(index=False)]
    return "\n".join([head, sep, *body]) + "\n"


def _style(ax) -> None:
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(GRID)
    ax.tick_params(colors=INK_2, labelsize=8)
    ax.grid(axis="y", color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)


def plot_annual_totals(totals: pd.DataFrame, path: Path, target_label: str) -> None:
    """Small multiples, one panel per region: actual vs each model's predicted annual total. Each test year is
    predicted by the fold that did not train on it (A covers 2012-2016, B covers 2017+)."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    t = totals[~totals["fold"].str.startswith("LOYO")]
    regions = sorted(t["region"].unique())
    models = [m for m in MODEL_LABELS if m in t["model"].unique()]
    ncol = min(3, len(regions))
    nrow = int(np.ceil(len(regions) / ncol))
    fig, axes = plt.subplots(nrow, ncol, figsize=(4.2 * ncol, 3.0 * nrow + 0.8), squeeze=False)
    for ax, region in zip(axes.flat, regions):
        r = t[t["region"] == region]
        act = r[r["model"] == models[0]].sort_values("year")
        ax.plot(act["year"], act["actual"], color=INK, linewidth=2.6, label="Observed VIIRS", zorder=5)
        for m in models:
            mm = r[r["model"] == m].sort_values("year")
            ax.plot(mm["year"], mm["pred"], color=MODEL_COLORS[m], linewidth=2, label=MODEL_LABELS[m],
                    marker="o", markersize=3.5)
        ax.set_title(region.replace("_", " "), fontsize=10, color=INK, loc="left")
        _style(ax)
    for ax in list(axes.flat)[len(regions):]:
        ax.axis("off")
    h, lab = axes.flat[0].get_legend_handles_labels()
    fig.suptitle(f"Held-out annual {target_label} by region: observed vs predicted", y=0.985, fontsize=12,
                 color=INK, x=0.01, ha="left")
    fig.legend(h, lab, loc="upper left", bbox_to_anchor=(0.01, 0.95), ncol=len(lab), frameon=False, fontsize=9)
    fig.tight_layout(rect=(0, 0, 1, 0.9))
    fig.savefig(path, dpi=160)
    plt.close(fig)


def plot_improvement(summary: pd.DataFrame, path: Path) -> None:
    """Horizontal bars: % improvement over the naive ratio for each metric (higher = better)."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    metrics = [("poisson_dev_vs_naive_pct", "Poisson deviance (%)", "%"),
               ("rmse_log1p_vs_naive_pct", "RMSE log1p (%)", "%"),
               ("mae_vs_naive_pct", "MAE (%)", "%"),
               ("abs_region_bias_vs_naive_pp", "|Region bias| (pp)", " pp")]
    models = [m for m in summary["model"] if m != "naive_ratio"]
    fig, axes = plt.subplots(1, len(metrics), figsize=(3.3 * len(metrics), 2.6), sharey=True)
    for ax, (col, label, unit) in zip(axes, metrics):
        vals = summary.set_index("model").loc[models, col]
        ypos = np.arange(len(models))
        ax.barh(ypos, vals, color=[MODEL_COLORS[m] for m in models], height=0.6)
        ax.axvline(0, color=INK_2, linewidth=0.8)
        for yv, v in zip(ypos, vals):
            ax.text(v + (1 if v >= 0 else -1), yv, f"{v:+.1f}{unit}" if unit == " pp" else f"{v:+.0f}{unit}", va="center", ha="left" if v >= 0 else "right",
                    fontsize=8, color=INK)
        ax.set_yticks(ypos, [MODEL_LABELS[m] for m in models], fontsize=9, color=INK)
        ax.set_title(label, fontsize=9, color=INK, loc="left")
        lo, hi = min(0, vals.min()), max(0, vals.max())
        pad = (hi - lo) * 0.35 + 5
        ax.set_xlim(lo - (pad if lo < 0 else 2), hi + pad)
        _style(ax)
        ax.grid(axis="x", color=GRID, linewidth=0.6)
        ax.grid(axis="y", visible=False)
    axes[0].invert_yaxis()
    fig.suptitle("Improvement over the naive global ratio, held-out years (positive = better)",
                 fontsize=11, color=INK, x=0.01, ha="left")
    fig.tight_layout()
    fig.savefig(path, dpi=160)
    plt.close(fig)

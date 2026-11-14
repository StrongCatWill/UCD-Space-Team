"""Model 1 - backcast: translate MODIS cell-month activity into VIIRS-equivalent (S-NPP scale).

Unit of analysis: one grid cell per month. This does NOT recreate individual 375 m VIIRS pixels; it
estimates the cell-month VIIRS total (detection count, or summed FRP) that VIIRS would have reported.

Hybrid design:
  1. GLM baseline - negative binomial (log link, dispersion estimated) for counts, Tweedie (log link) for FRP.
     Each land cover gets its own MODIS->VIIRS conversion: land cover x log1p(MODIS count), plus a
     land cover x 1{MODIS count > 0} jump so the slope describes positive counts only.
  2. Residual random forest on r = log((y+1)/(mu+1)); prediction (mu+1)*exp(r_hat)*S - 1, clipped at 0,
     where S is a totals calibration per region x decile of mu (ratio of sums on out-of-bag predictions,
     config backcast.smearing) so region and fire-size totals match training instead of the log-scale fit's
     typical value. (Duan smearing left real-data regional totals off by -40%..+72% even in training.)
  3. Extrapolation guard: beyond the per-land-cover training percentile (config guard_percentile, over
     cell-months with MODIS > 0) of MODIS count/FRP, the RF
     correction shrinks linearly to 0 at 2x that limit, so the GLM carries the extrapolation; rows are flagged.
  4. Intervals: quantile regression forest on the same residuals -> 5/50/95% on the count/FRP scale.
     For extrapolated rows the interval centre shrinks with the correction and the spread is widened.

Comparators (validation only): naive global ratio, GLM only, stacked trees + meta-model.
"""
from __future__ import annotations

import warnings
from dataclasses import dataclass, field

import numpy as np
import pandas as pd
import statsmodels.api as sm
from quantile_forest import RandomForestQuantileRegressor
from scipy import stats
from sklearn.ensemble import ExtraTreesRegressor, RandomForestRegressor
from sklearn.linear_model import LinearRegression
from sklearn.model_selection import GroupKFold
from statsmodels.tools.sm_exceptions import ConvergenceWarning

from .features import GlmDesign, Levels, add_backcast_features, rf_matrix

OUT_COLS = ["pred", "q05", "q50", "q95", "extrapolated"]


def _residual_quantile_intervals(point: np.ndarray, resid_q: np.ndarray) -> np.ndarray:
    """Apply empirical log-scale residual quantiles r_q around a point prediction."""
    return np.maximum((point[:, None] + 1) * np.exp(resid_q[None, :]) - 1, 0)


def _frame(pred, q, extrapolated, index) -> pd.DataFrame:
    q = np.sort(np.asarray(q, dtype=float), axis=1)  # guarantees q05 <= q50 <= q95
    return pd.DataFrame({"pred": np.maximum(pred, 0), "q05": q[:, 0], "q50": q[:, 1], "q95": q[:, 2],
                         "extrapolated": np.asarray(extrapolated, dtype=bool)}, index=index)


@dataclass
class BinnedSmearing:
    """Duan smearing per decile of a log-scale key (e.g. log1p(mu)). Log residuals are heteroscedastic - their
    spread changes with fire size - so one global factor over-predicts small cell-months and under-predicts
    mid-size ones. factor(bin) = mean(exp(residual)) of the out-of-sample residuals in that bin."""
    n_bins: int = 10
    edges: np.ndarray | None = None
    factors: np.ndarray | None = None

    def fit(self, key: np.ndarray, resid: np.ndarray) -> "BinnedSmearing":
        self.edges = np.unique(np.quantile(key, np.linspace(0, 1, self.n_bins + 1)[1:-1]))
        b = np.searchsorted(self.edges, key, side="right")
        e = np.exp(resid)
        overall = float(e.mean())
        self.factors = np.array([e[b == i].mean() if (b == i).any() else overall for i in range(len(self.edges) + 1)])
        return self

    def __call__(self, key: np.ndarray) -> np.ndarray:
        if self.factors is None:
            return np.ones(len(key))
        return self.factors[np.searchsorted(self.edges, key, side="right")]


@dataclass
class TotalsCalibration:
    """Ratio-of-sums correction per (group, decile of a log-scale key), fitted on out-of-bag predictions.

    factor = sum(y + 1) / sum(base + 1) in each (group, bin), so (base + 1) * factor - 1 reproduces the training
    totals of every group x size bin. Log-scale fits (and NB2 with large alpha) match the typical cell-month,
    not totals, which are dominated by a few large fires; Duan smearing (mean of exp(residual)) does not fix
    that. Groups (e.g. regions) unseen in training fall back to the pooled factor of their bin."""
    n_bins: int = 10
    edges: np.ndarray | None = None
    factors: dict = field(default_factory=dict)
    pooled: np.ndarray | None = None

    def fit(self, group: np.ndarray, key: np.ndarray, y: np.ndarray, base: np.ndarray) -> "TotalsCalibration":
        self.edges = np.unique(np.quantile(key, np.linspace(0, 1, self.n_bins + 1)[1:-1]))
        b = np.searchsorted(self.edges, key, side="right")
        nb = len(self.edges) + 1
        num, den = y + 1.0, base + 1.0

        def ratios(mask):
            n = np.bincount(b[mask], weights=num[mask], minlength=nb)
            d = np.bincount(b[mask], weights=den[mask], minlength=nb)
            return np.where(d > 0, n / np.where(d > 0, d, 1), np.nan)

        overall = float(num.sum() / den.sum())
        self.pooled = np.nan_to_num(ratios(np.ones(len(y), bool)), nan=overall)
        self.factors = {str(g): np.where(np.isnan(r := ratios(group == g)), self.pooled, r) for g in np.unique(group)}
        return self

    def __call__(self, group: np.ndarray, key: np.ndarray) -> np.ndarray:
        if self.pooled is None:
            return np.ones(len(key))
        b = np.searchsorted(self.edges, key, side="right")
        out = self.pooled[b].copy()
        for g, f in self.factors.items():
            m = group == g
            out[m] = f[b[m]]
        return out


# ------------------------------------------------------------------------------------------ naive

@dataclass
class NaiveRatio:
    """VIIRS = one global ratio x MODIS (fitted on training years). Intervals from training log residuals."""
    target: str = "count"
    quantiles: tuple[float, ...] = (0.05, 0.5, 0.95)
    ratio: float = 1.0
    resid_q: np.ndarray | None = None

    def _x(self, df: pd.DataFrame) -> np.ndarray:
        return df["modis_count" if self.target == "count" else "modis_frp_sum"].to_numpy(dtype=float)

    def fit(self, df: pd.DataFrame, y: np.ndarray) -> "NaiveRatio":
        x = self._x(df)
        self.ratio = float(y.sum() / max(x.sum(), 1e-9))
        r = np.log((y + 1) / (self.ratio * x + 1))
        self.resid_q = np.quantile(r, self.quantiles)
        return self

    def predict(self, df: pd.DataFrame) -> pd.DataFrame:
        p = self.ratio * self._x(df)
        return _frame(p, _residual_quantile_intervals(p, self.resid_q), np.zeros(len(df), bool), df.index)


# ------------------------------------------------------------------------------------------- GLM

@dataclass
class GlmBackcast:
    """Count: log-link GLM, mean by Poisson pseudo-ML (HC0 errors), NB2 dispersion alpha by profile ML for the
    predictive distribution. FRP: Tweedie GLM with log link."""
    target: str = "count"
    drift_cutoff: int = 2021
    tweedie_power: float = 1.5
    quantiles: tuple[float, ...] = (0.05, 0.5, 0.95)
    design: GlmDesign = field(default_factory=GlmDesign)
    params: pd.Series | None = None
    cov: pd.DataFrame | None = None
    alpha: float | None = None
    alpha_method: str = ""
    scale: float = 1.0
    resid_q: np.ndarray | None = None
    train_ref: dict = field(default_factory=dict)

    def fit(self, df: pd.DataFrame, y: np.ndarray) -> "GlmBackcast":
        f = add_backcast_features(df, self.drift_cutoff)
        X = self.design.fit(f).transform(f)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", ConvergenceWarning)
            warnings.simplefilter("ignore", RuntimeWarning)
            if self.target == "count":
                # Mean by Poisson pseudo-ML (robust HC0 errors): its score equations reproduce training totals per
                # term level. NB2 MLE (alpha ~4 on real data) down-weights large cell-months by ~1/(1 + alpha*mu),
                # so the mean followed millions of small cell-months and missed the big fires that make up the
                # totals (held-out regional bias 50-80%). alpha is then estimated with the mean fixed and is used
                # only for the GLM's predictive quantiles and p_any.
                res = sm.GLM(y, X, family=sm.families.Poisson()).fit(maxiter=200, cov_type="HC0")
                mu_fit = np.exp(np.clip(X.to_numpy() @ res.params.to_numpy(), -30, 30))
                self.alpha, self.alpha_method = self._profile_alpha(y, mu_fit)
            else:
                family = sm.families.Tweedie(var_power=self.tweedie_power, link=sm.families.links.Log())
                res = sm.GLM(y, X, family=family).fit(maxiter=200)
        self.params, self.cov = res.params, res.cov_params()
        self.scale = float(res.scale)
        mu = self._mu(X)
        self.resid_q = np.quantile(np.log((y + 1) / (mu + 1)), self.quantiles)
        pos = f[f["modis_count"] > 0]
        self.train_ref = {
            "frp_per_detection": float((pos["modis_frp_sum"] / pos["modis_count"]).median()) if len(pos) else 30.0,
            "region_share": pos["region"].value_counts(normalize=True).to_dict(),
            "month_share": pos["month"].astype(int).value_counts(normalize=True).to_dict(),
        }
        return self

    @staticmethod
    def _profile_alpha(y: np.ndarray, mu: np.ndarray) -> tuple[float, str]:
        """NB2 alpha by maximum likelihood with the mean held fixed (profile likelihood over log alpha)."""
        from scipy.optimize import minimize_scalar
        from scipy.special import gammaln

        def nll(log_a: float) -> float:
            a = np.exp(log_a)
            r = 1.0 / a
            return -float(np.sum(gammaln(y + r) - gammaln(r) - gammaln(y + 1)
                                 + r * np.log(r / (r + mu)) + y * np.log(mu / (r + mu))))

        opt = minimize_scalar(nll, bounds=(np.log(1e-4), np.log(1e3)), method="bounded")
        return float(np.exp(opt.x)), "profile_ml (poisson mean)"

    @staticmethod
    def _estimate_alpha(X: pd.DataFrame, y: np.ndarray) -> tuple[float, str]:
        """NB2 alpha by maximum likelihood (Poisson start); method-of-moments fallback if it fails."""
        pois = sm.GLM(y, X, family=sm.families.Poisson()).fit(maxiter=200)
        try:
            nb = sm.NegativeBinomial(y, X, loglike_method="nb2").fit(
                start_params=np.append(pois.params.to_numpy(), 0.5), method="bfgs", maxiter=1000, disp=0)
            alpha = float(nb.params.iloc[-1])
            if nb.mle_retvals.get("converged", False) and np.isfinite(alpha) and alpha > 0:
                return alpha, "mle"
        except (np.linalg.LinAlgError, ValueError):
            pass
        mu = pois.mu
        alpha = float(np.sum(((y - mu) ** 2 - y) / mu ** 2) / max(len(y) - X.shape[1], 1))
        return max(alpha, 1e-3), "moments"

    def _mu(self, X: pd.DataFrame) -> np.ndarray:
        eta = np.clip(X.to_numpy() @ self.params.to_numpy(), -30, 30)
        return np.exp(eta)

    def mu(self, df: pd.DataFrame) -> np.ndarray:
        f = add_backcast_features(df, self.drift_cutoff)
        return self._mu(self.design.transform(f))

    def predict(self, df: pd.DataFrame) -> pd.DataFrame:
        mu = self.mu(df)
        if self.target == "count":  # exact NB predictive quantiles
            n = 1.0 / self.alpha
            p = n / (n + mu)
            q = np.column_stack([stats.nbinom.ppf(qq, n, p) for qq in self.quantiles])
        else:
            q = _residual_quantile_intervals(mu, self.resid_q)
        return _frame(mu, q, np.zeros(len(df), bool), df.index)

    def p_any(self, mean: np.ndarray) -> np.ndarray:
        """P(y > 0) under the fitted distribution at the given mean: NB2 zero mass for counts,
        Tweedie (compound Poisson-gamma) zero mass for FRP."""
        mean = np.maximum(np.asarray(mean, dtype=float), 0)
        if self.target == "count":
            return 1 - (1 + self.alpha * mean) ** (-1 / self.alpha)
        p = self.tweedie_power
        return 1 - np.exp(-mean ** (2 - p) / (self.scale * (2 - p)))

    # ---- readable coefficients
    def coefficient_table(self) -> pd.DataFrame:
        se = np.sqrt(np.diag(self.cov.to_numpy()))
        z = stats.norm.ppf(0.975)
        t = pd.DataFrame({"term": self.params.index, "coef": self.params.to_numpy(), "se": se})
        t["ci_low"], t["ci_high"] = t["coef"] - z * se, t["coef"] + z * se
        t["exp_coef"] = np.exp(t["coef"])
        return t

    def conversion_factors(self, ref_count: float = 10.0) -> pd.DataFrame:
        """Per land cover: MODIS->VIIRS slope (elasticity) and implied VIIRS:MODIS ratio at `ref_count`
        MODIS detections (FRP at the training median per detection; region/month averaged over training
        fire months; drift era off), with 95% delta-method confidence intervals."""
        names = list(self.params.index)
        beta, cov = self.params.to_numpy(), self.cov.to_numpy()
        z = stats.norm.ppf(0.975)
        rows = []
        for lc in self.design.levels.land_cover:
            L = np.zeros(len(names))

            def put(term: str, value: float) -> None:
                if term in names:
                    L[names.index(term)] += value
            put("const", 1.0)
            put("log_frp", np.log1p(ref_count * self.train_ref["frp_per_detection"]))
            put(f"lc[{lc}]", 1.0)
            put(f"any[{lc}]", 1.0)
            put(f"slope[{lc}]", np.log1p(ref_count))
            for r, s in self.train_ref["region_share"].items():
                put(f"region[{r}]", s)
            for m, s in self.train_ref["month_share"].items():
                put(f"month[{m}]", s)
            eta, se = float(L @ beta), float(np.sqrt(L @ cov @ L))
            sl = f"slope[{lc}]"
            k = names.index(sl) if sl in names else None
            rows.append({
                "land_cover": lc,
                "slope": beta[k] if k is not None else np.nan,
                "slope_ci_low": beta[k] - z * np.sqrt(cov[k, k]) if k is not None else np.nan,
                "slope_ci_high": beta[k] + z * np.sqrt(cov[k, k]) if k is not None else np.nan,
                f"ratio_at_{ref_count:g}": np.exp(eta) / ref_count,
                "ratio_ci_low": np.exp(eta - z * se) / ref_count,
                "ratio_ci_high": np.exp(eta + z * se) / ref_count,
            })
        return pd.DataFrame(rows)


# ---------------------------------------------------------------------------------------- hybrid

@dataclass
class HybridBackcast:
    """GLM + residual RF + QRF intervals + extrapolation guard (see module docstring)."""
    target: str = "count"
    drift_cutoff: int = 2021
    tweedie_power: float = 1.5
    guard_percentile: float = 99.5
    smearing: bool = True
    quantiles: tuple[float, ...] = (0.05, 0.5, 0.95)
    rf_params: dict = field(default_factory=dict)
    qrf_params: dict = field(default_factory=dict)
    seed: int = 42
    glm: GlmBackcast | None = None
    rf: RandomForestRegressor | None = None
    qrf: RandomForestQuantileRegressor | None = None
    smear: TotalsCalibration = field(default_factory=TotalsCalibration)
    limits: dict = field(default_factory=dict)

    def _features(self, df: pd.DataFrame, mu: np.ndarray) -> pd.DataFrame:
        f = add_backcast_features(df, self.drift_cutoff)
        return rf_matrix(f, self.glm.design.levels, extra={"glm_log_mu": np.log1p(mu)})

    def fit(self, df: pd.DataFrame, y: np.ndarray) -> "HybridBackcast":
        self.glm = GlmBackcast(self.target, self.drift_cutoff, self.tweedie_power, self.quantiles).fit(df, y)
        mu = self.glm.mu(df)
        X = self._features(df, mu)
        r = np.log((y + 1) / (mu + 1))
        self.rf = RandomForestRegressor(random_state=self.seed, oob_score=True, **self.rf_params).fit(X, r)
        oob = self.rf.oob_prediction_
        ok = np.isfinite(oob)
        self.smear = TotalsCalibration()
        if self.smearing and ok.any():
            base = (mu[ok] + 1) * np.exp(oob[ok]) - 1
            self.smear.fit(df["region"].to_numpy(str)[ok], np.log1p(mu[ok]), y[ok], base)
        self.qrf = RandomForestQuantileRegressor(random_state=self.seed, **self.qrf_params).fit(X, r)
        # limits from cell-months WITH MODIS detections: over all rows (>80% zeros) the percentile is tiny and
        # every large fire was "extrapolated" back to the raw GLM even inside the training years
        self.limits = {}
        pos = df[df["modis_count"] > 0]
        for lc, g in (pos if len(pos) else df).groupby("land_cover"):
            self.limits[lc] = {
                "modis_count": max(float(np.percentile(g["modis_count"], self.guard_percentile)), 1.0),
                "modis_frp_sum": max(float(np.percentile(g["modis_frp_sum"], self.guard_percentile)), 1.0),
            }
        return self

    def guard(self, df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
        """Returns (excess, shrink). excess = relative distance beyond the per-land-cover training limit
        (inf for an unseen land cover); shrink = clip(1 - excess, 0, 1)."""
        excess = np.zeros(len(df))
        lc = df["land_cover"].to_numpy()
        for col in ("modis_count", "modis_frp_sum"):
            x = df[col].to_numpy(dtype=float)
            lim = np.array([self.limits.get(c, {}).get(col, np.nan) for c in lc])
            e = np.where(np.isnan(lim), np.inf, np.maximum((x - lim) / np.where(np.isnan(lim), 1, lim), 0))
            excess = np.maximum(excess, e)
        return excess, np.clip(1 - excess, 0, 1)

    def predict(self, df: pd.DataFrame) -> pd.DataFrame:
        mu = self.glm.mu(df)
        X = self._features(df, mu)
        excess, shrink = self.guard(df)
        r_hat = self.rf.predict(X) * shrink
        pred = np.maximum((mu + 1) * np.exp(r_hat) * self.smear(df["region"].to_numpy(str), np.log1p(mu)) ** shrink - 1, 0)
        rq = np.asarray(self.qrf.predict(X, quantiles=list(self.quantiles)))
        mid = rq[:, [1]]
        widen = 1 + np.minimum(excess, 1)[:, None]
        rq = shrink[:, None] * mid + (rq - mid) * widen
        q = (mu[:, None] + 1) * np.exp(rq) - 1
        return _frame(pred, np.maximum(q, 0), excess > 0, df.index)


# --------------------------------------------------------------------------------------- stacked

@dataclass
class StackedBackcast:
    """Comparison only: GLM, RF and ExtraTrees (on log1p y) combined by a non-negative linear meta-model
    trained ONLY on out-of-fold predictions (folds grouped by year, so no year appears on both sides)."""
    target: str = "count"
    drift_cutoff: int = 2021
    tweedie_power: float = 1.5
    smearing: bool = True
    quantiles: tuple[float, ...] = (0.05, 0.5, 0.95)
    rf_params: dict = field(default_factory=dict)
    inner_folds: int = 5
    seed: int = 42
    levels: Levels | None = None
    bases: tuple | None = None
    meta: LinearRegression | None = None
    smear: BinnedSmearing = field(default_factory=BinnedSmearing)
    resid_q: np.ndarray | None = None

    def _fit_bases(self, df, y):
        glm = GlmBackcast(self.target, self.drift_cutoff, self.tweedie_power, self.quantiles).fit(df, y)
        X = rf_matrix(add_backcast_features(df, self.drift_cutoff), self.levels)
        rf = RandomForestRegressor(random_state=self.seed, **self.rf_params).fit(X, np.log1p(y))
        et_params = dict(self.rf_params)
        if et_params.get("max_samples") is not None:   # subsampling needs bootstrap (off by default for ET)
            et_params["bootstrap"] = True
        et = ExtraTreesRegressor(random_state=self.seed, **et_params).fit(X, np.log1p(y))
        return glm, rf, et

    def _base_preds(self, bases, df) -> np.ndarray:
        glm, rf, et = bases
        X = rf_matrix(add_backcast_features(df, self.drift_cutoff), self.levels)
        return np.column_stack([np.log1p(glm.mu(df)), rf.predict(X), et.predict(X)])

    def fit(self, df: pd.DataFrame, y: np.ndarray) -> "StackedBackcast":
        self.levels = Levels.from_frame(df)
        years = df["year"].to_numpy()
        k = min(self.inner_folds, len(np.unique(years)))
        oof = np.zeros((len(df), 3))
        for tr, te in GroupKFold(n_splits=k).split(df, y, groups=years):
            b = self._fit_bases(df.iloc[tr], y[tr])
            oof[te] = self._base_preds(b, df.iloc[te])
        self.meta = LinearRegression(positive=True).fit(oof, np.log1p(y))
        z_oof = self.meta.predict(oof)
        e = np.log1p(y) - z_oof
        self.smear = BinnedSmearing().fit(z_oof, e) if self.smearing else BinnedSmearing()
        self.resid_q = np.quantile(e, self.quantiles)
        self.bases = self._fit_bases(df, y)
        return self

    def predict(self, df: pd.DataFrame) -> pd.DataFrame:
        z = self.meta.predict(self._base_preds(self.bases, df))
        pred = np.exp(z) * self.smear(z) - 1
        q = np.maximum(np.exp(z[:, None] + self.resid_q[None, :]) - 1, 0)
        return _frame(pred, q, np.zeros(len(df), bool), df.index)


# ---------------------------------------------------------------------------------------- factory

def build_models(cfg, include_stacked: bool | None = None) -> dict[str, object]:
    """All comparators, configured from config.yaml, in display order."""
    b = cfg.section("backcast")
    common = dict(target=cfg.target, quantiles=tuple(b["quantiles"]))
    models: dict[str, object] = {
        "naive_ratio": NaiveRatio(**common),
        "glm": GlmBackcast(drift_cutoff=cfg.drift_cutoff, tweedie_power=b["tweedie_power"], **common),
        "hybrid": HybridBackcast(drift_cutoff=cfg.drift_cutoff, tweedie_power=b["tweedie_power"],
                                 guard_percentile=b["guard_percentile"], smearing=b["smearing"],
                                 rf_params=b["rf"], qrf_params=b["qrf"], seed=cfg.seed, **common),
    }
    if b["stacked"]["enabled"] if include_stacked is None else include_stacked:
        models["stacked"] = StackedBackcast(drift_cutoff=cfg.drift_cutoff, tweedie_power=b["tweedie_power"],
                                            smearing=b["smearing"], rf_params=b["rf"],
                                            inner_folds=b["stacked"]["inner_folds"], seed=cfg.seed, **common)
    return models

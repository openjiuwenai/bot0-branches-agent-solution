# -*- coding: utf-8 -*-
"""
加氢裂化收率预测——机理模型（集总动力学模型）

原理：将物料按沸点分为若干"集总"(lump)，重集总通过裂化反应转化为轻集总。
每个反应路径的速率常数遵循 Arrhenius 方程，并受催化剂活性衰减和氢分压影响。
反应器按平推流(PFR)建模，通过求解常微分方程组得到产品分布。

参数估计：用历史数据拟合反应速率常数，使预测收率与实际收率偏差最小。
混合模型：机理模型预测基础趋势 + 数据驱动模型修正残差。
"""
import numpy as np
from scipy.integrate import odeint
from scipy.optimize import minimize
from typing import Dict, List, Tuple, Optional
import warnings
warnings.filterwarnings("ignore")


# ============================================================
# 一、集总定义：按沸点将物料分组
# ============================================================

# 5个集总，从重到轻
# L5: 重油(>350C)   L4: 柴油(250-350C)   L3: 航煤(150-250C)
# L2: 重石脑油(80-150C)   L1: 轻组分+气体(<80C)

LUMP_NAMES = ["L1_轻组分", "L2_重石脑油", "L3_航煤", "L4_柴油", "L5_重油"]
N_LUMPS = 5

# 进料物料 -> 集总的映射
FEED_TO_LUMP = {
    "直馏柴油": 4,        # 柴油馏分
    "罐区柴油": 4,
    "精制柴油": 4,
    "DCC柴油": 4,
    "蜡油加氢柴油": 4,
    "3#直馏石脑油": 2,    # 石脑油馏分
    "裂柴加氢石脑油": 2,
    "蜡油加氢石脑油": 2,
    "裂解石脑油C5组分": 1, # 轻组分
    "新氢": 0,            # 反应物，不归入集总(消耗)
}

# 出料物料 -> 集总的映射
PRODUCT_TO_LUMP = {
    "轻石脑油": 1,
    "粗液化气": 1,
    "含硫干气": 1,
    "含硫低分气": 1,
    "损失": 1,
    "重石脑油": 2,
    "航煤": 3,
    "柴油": 4,
    "污油": 5,
    "轻质燃料油": 5,
}


# ============================================================
# 二、反应网络：重集总 -> 轻集总（裂化反应）
# ============================================================

# 每个重集总可以裂化为任意更轻的集总
# 反应路径: (from_lump, to_lump) 从1开始编号
# L5->L4, L5->L3, L5->L2, L5->L1
# L4->L3, L4->L2, L4->L1
# L3->L2, L3->L1
# L2->L1
# 共 4+3+2+1 = 10 条路径

REACTION_PATHS = []
for i in range(N_LUMPS, 1, -1):      # from L5 down to L2
    for j in range(i - 1, 0, -1):     # to all lighter lumps
        REACTION_PATHS.append((i, j))  # (from, to), 1-indexed

N_PATHS = len(REACTION_PATHS)  # 10


# ============================================================
# 三、动力学方程
# ============================================================

def rate_constant(A: float, Ea: float, T: float, R: float = 8.314) -> float:
    """Arrhenius 方程: k = A * exp(-Ea / RT)

    A:   指前因子
    Ea:  活化能 (J/mol)
    T:   反应温度 (K)
    """
    return A * np.exp(-Ea / (R * T))


def catalyst_activity(cat_days: float, alpha: float = 0.001) -> float:
    """催化剂活性衰减函数: a(t) = exp(-alpha * t)

    cat_days: 自换剂/再生以来的运行天数
    alpha:    衰减系数(1/天)，需拟合
    """
    return np.exp(-alpha * cat_days)


def effective_rate(params: Dict, T: float, cat_days: float,
                   P_h2: float = 1.0) -> np.ndarray:
    """计算所有反应路径的有效速率常数。

    k_{i->j} = A_{i->j} * exp(-Ea_{i->j} / RT) * a(cat_days) * (P_H2)^n

    返回: 长度为 N_PATHS 的速率常数数组
    """
    k = np.zeros(N_PATHS)
    activity = catalyst_activity(cat_days, params.get("alpha", 0.001))
    h2_factor = P_h2 ** params.get("n_h2", 0.5)
    R = 8.314

    for idx, (i, j) in enumerate(REACTION_PATHS):
        A = params[f"A_{i}_{j}"]
        Ea = params[f"Ea_{i}_{j}"]
        k[idx] = rate_constant(A, Ea, T + 273.15, R) * activity * h2_factor

    return k


# ============================================================
# 四、反应器模型：平推流(PFR)
# ============================================================

def pfr_ode(C: np.ndarray, tau: float, k: np.ndarray) -> np.ndarray:
    """PFR 中各集总浓度随停留时间的变化。

    dC_i/dtau = -sum_j(k_{i->j}) * C_i + sum_j(k_{j->i} * C_j)

    消耗项: 集总i裂化为所有更轻集总的速率之和
    生成项: 所有更重集总裂化为集总i的速率之和

    C:   各集总质量分率 [C1, C2, C3, C4, C5]（0-indexed）
    tau: 停留时间 = 1/LHSV
    k:   速率常数数组
    """
    dC = np.zeros(N_LUMPS)
    # 0-indexed: L1=0, L2=1, ..., L5=4

    for idx, (i, j) in enumerate(REACTION_PATHS):
        # i, j are 1-indexed; convert to 0-indexed
        i0 = i - 1
        j0 = j - 1
        rate = k[idx] * C[i0]
        dC[i0] -= rate   # 消耗：集总i减少
        dC[j0] += rate   # 生成：集总j增加

    return dC


def predict_yields(feed_composition: Dict[str, float],
                   T: float, cat_days: float, LHSV: float,
                   P_h2: float, params: Dict) -> np.ndarray:
    """预测各集总收率。

    feed_composition: {物料名: 流量} 或 {物料名: 占比}
    T:     反应温度 (C)
    cat_days: 催化剂运行天数
    LHSV:  液时空速 (h-1)
    P_h2:  氢分压 (MPa)
    params: 拟合参数

    返回: 各集总收率 [L1, L2, L3, L4, L5]（百分比）
    """
    # 1. 将进料物料映射为集总初始浓度
    C0 = np.zeros(N_LUMPS)
    total = sum(feed_composition.values())
    for mat, flow in feed_composition.items():
        lump = FEED_TO_LUMP.get(mat, 0)
        if lump > 0:
            C0[lump - 1] += flow / total  # 归一化为质量分率

    # 2. 计算速率常数
    k = effective_rate(params, T, cat_days, P_h2)

    # 3. 积分PFR方程: tau = 1/LHSV
    tau = np.linspace(0, 1.0 / LHSV, 100)
    C_profile = odeint(pfr_ode, C0, tau, args=(k,))

    # 4. 出口浓度即为产品分布
    C_out = C_profile[-1]

    # 5. 转换为百分比
    yields_pct = C_out / C_out.sum() * 100.0

    return yields_pct


# ============================================================
# 五、参数估计：用历史数据拟合
# ============================================================

def build_param_template() -> Dict:
    """构建参数模板（初始猜测值）。

    需要估计的参数:
    - 每条反应路径的指前因子 A 和活化能 Ea (共 10*2 = 20 个)
    - 催化剂衰减系数 alpha (1 个)
    - 氢分压指数 n_h2 (1 个)
    共 22 个参数
    """
    params = {}
    # 初始猜测: A ~ 1e6 (典型指前因子), Ea ~ 80000 J/mol (典型活化能)
    for i, j in REACTION_PATHS:
        params[f"A_{i}_{j}"] = 1e6
        params[f"Ea_{i}_{j}"] = 80000.0
    params["alpha"] = 0.001
    params["n_h2"] = 0.5
    return params


def params_to_vector(params: Dict) -> np.ndarray:
    """参数字典 -> 向量（用于优化器）"""
    keys = sorted(params.keys())
    return np.array([params[k] for k in keys])


def vector_to_params(vec: np.ndarray) -> Dict:
    """向量 -> 参数字典"""
    template = build_param_template()
    keys = sorted(template.keys())
    return {k: v for k, v in zip(keys, vec)}


def estimate_parameters(
    historical_data: List[Dict],
    param_init: Optional[Dict] = None,
    max_iter: int = 200
) -> Tuple[Dict, float]:
    """用历史数据拟合机理模型参数。

    historical_data: 历史数据列表，每条包含:
      - feed_composition: {物料名: 流量}
      - T: 反应温度 (C)
      - cat_days: 催化剂运行天数
      - LHSV: 液时空速
      - P_h2: 氢分压
      - actual_yields: {集总名: 实际收率%} 或 {物料名: 实际收率%}

    返回: (最优参数, 最优目标函数值)
    """
    if param_init is None:
        param_init = build_param_template()

    keys = sorted(param_init.keys())
    x0 = np.array([param_init[k] for k in keys])

    def objective(x):
        params = vector_to_params(x)
        total_error = 0.0
        for record in historical_data:
            try:
                pred = predict_yields(
                    record["feed_composition"],
                    record["T"], record["cat_days"],
                    record["LHSV"], record["P_h2"],
                    params
                )
                # 将实际收率映射到集总
                actual = np.zeros(N_LUMPS)
                for mat, yld in record["actual_yields"].items():
                    lump = PRODUCT_TO_LUMP.get(mat, 0)
                    if lump > 0:
                        actual[lump - 1] += yld
                actual = actual / actual.sum() * 100.0

                total_error += np.sum((pred - actual) ** 2)
            except Exception:
                total_error += 1e10
        return total_error

    # 参数边界: A>0, Ea>0, alpha>0, 0<n_h2<2
    bounds = []
    for k in keys:
        if k.startswith("A_"):
            bounds.append((1e3, 1e12))
        elif k.startswith("Ea_"):
            bounds.append((20000, 200000))
        elif k == "alpha":
            bounds.append((0, 0.01))
        elif k == "n_h2":
            bounds.append((0, 2))

    # 取对数优化 A 和 Ea（数值稳定性）
    def objective_log(log_x):
        x = log_x.copy()
        for idx, k in enumerate(keys):
            if k.startswith("A_") or k.startswith("Ea_"):
                x[idx] = np.exp(log_x[idx])
        return objective(x)

    log_x0 = x0.copy()
    for idx, k in enumerate(keys):
        if k.startswith("A_") or k.startswith("Ea_"):
            log_x0[idx] = np.log(x0[idx])

    log_bounds = []
    for idx, k in enumerate(keys):
        if k.startswith("A_"):
            log_bounds.append((np.log(1e3), np.log(1e12)))
        elif k.startswith("Ea_"):
            log_bounds.append((np.log(20000), np.log(200000)))
        elif k == "alpha":
            log_bounds.append((0, 0.01))
        elif k == "n_h2":
            log_bounds.append((0, 2))

    result = minimize(objective_log, log_x0, method="L-BFGS-B",
                      bounds=log_bounds, options={"maxiter": max_iter})

    # 转换回原始参数
    opt_x = result.x.copy()
    for idx, k in enumerate(keys):
        if k.startswith("A_") or k.startswith("Ea_"):
            opt_x[idx] = np.exp(result.x[idx])

    return vector_to_params(opt_x), result.fun


# ============================================================
# 六、混合模型：机理 + 数据驱动残差修正
# ============================================================

def hybrid_predict(feed_composition: Dict[str, float],
                   T: float, cat_days: float, LHSV: float,
                   P_h2: float, mech_params: Dict,
                   correction_model=None) -> Tuple[np.ndarray, np.ndarray]:
    """混合模型预测。

    机理模型: 预测基础收率趋势，保证外推合理性
    修正模型: 学习机理模型的残差（GBDT/神经网络），捕捉装置特性

    返回: (机理预测, 修正后预测)
    """
    # 机理模型预测
    mech_pred = predict_yields(feed_composition, T, cat_days, LHSV, P_h2, mech_params)

    if correction_model is None:
        return mech_pred, mech_pred

    # 构造修正模型输入特征
    features = _build_correction_features(feed_composition, T, cat_days, LHSV, P_h2, mech_pred)

    # 数据驱动修正
    correction = correction_model.predict(features.reshape(1, -1))[0]

    corrected = mech_pred + correction
    # 守恒约束: 归一化到100%
    corrected = np.maximum(corrected, 0)
    corrected = corrected / corrected.sum() * 100.0

    return mech_pred, corrected


def _build_correction_features(feed_composition, T, cat_days, LHSV, P_h2, mech_pred):
    """构造修正模型的输入特征"""
    # 进料集总占比
    C0 = np.zeros(N_LUMPS)
    total = sum(feed_composition.values())
    for mat, flow in feed_composition.items():
        lump = FEED_TO_LUMP.get(mat, 0)
        if lump > 0:
            C0[lump - 1] += flow / total

    features = np.concatenate([
        C0,                        # 进料集总占比 (5)
        [T, cat_days, LHSV, P_h2], # 操作条件 (4)
        mech_pred,                 # 机理预测 (5)
    ])
    return features


def train_correction_model(historical_data: List[Dict], mech_params: Dict):
    """训练残差修正模型（GBDT）

    残差 = 实际收率 - 机理模型预测
    """
    from sklearn.ensemble import GradientBoostingRegressor

    X_list = []
    residual_list = []  # 每个集总一个模型

    for record in historical_data:
        mech_pred = predict_yields(
            record["feed_composition"],
            record["T"], record["cat_days"],
            record["LHSV"], record["P_h2"],
            mech_params
        )
        actual = np.zeros(N_LUMPS)
        for mat, yld in record["actual_yields"].items():
            lump = PRODUCT_TO_LUMP.get(mat, 0)
            if lump > 0:
                actual[lump - 1] += yld
        actual = actual / actual.sum() * 100.0

        residual = actual - mech_pred
        features = _build_correction_features(
            record["feed_composition"], record["T"],
            record["cat_days"], record["LHSV"], record["P_h2"], mech_pred
        )
        X_list.append(features)
        residual_list.append(residual)

    X = np.array(X_list)
    residuals = np.array(residual_list)

    # 每个集总训练一个GBDT
    models = []
    for i in range(N_LUMPS):
        m = GradientBoostingRegressor(
            n_estimators=50, max_depth=3, learning_rate=0.1, random_state=42
        )
        m.fit(X, residuals[:, i])
        models.append(m)

    return models


# ============================================================
# 七、示例：从物料平衡数据构造训练集
# ============================================================

def build_training_data_from_store(store: Dict, default_T: float = 385.0,
                                    default_LHSV: float = 1.0,
                                    default_P_h2: float = 14.0,
                                    cat_start_date: str = "2026-01-01") -> List[Dict]:
    """从已导入的物料平衡数据构造机理模型训练集。

    注意: 温度/LHSV/氢压目前无实际数据，用默认值占位。
    补充DCS数据后替换为真实值。
    """
    import pandas as pd

    df = store.get("long_df")
    time_col = store.get("time_col")
    if df is None or time_col is None:
        return []

    df = df.copy()
    df[time_col] = pd.to_datetime(df[time_col], errors="coerce")
    df = df.dropna(subset=[time_col])

    # 识别方向列和物料列
    io_col = None
    for c in df.columns:
        if c == time_col:
            continue
        vals = set(df[c].dropna().astype(str).unique())
        if vals and vals <= {"进", "出"}:
            io_col = c
            break
    if io_col is None:
        return []

    name_col = None
    for c in df.columns:
        if c in (time_col, io_col):
            continue
        if "display" in str(c).lower() or "名称" in str(c):
            name_col = c
            break
    if name_col is None:
        return []

    # 数值列
    value_cols = store.get("value_cols") or []
    val_col = None
    for vc in value_cols:
        if "bal" in str(vc).lower():
            val_col = vc
            break
    if val_col is None and value_cols:
        val_col = value_cols[0]
    if val_col is None:
        return []

    df[val_col] = pd.to_numeric(df[val_col], errors="coerce")
    df = df.dropna(subset=[val_col])

    cat_start = pd.Timestamp(cat_start_date)
    training_data = []

    for date, group in df.groupby(time_col):
        cat_days = (date - cat_start).days
        if cat_days < 0:
            cat_days = 0

        feed_comp = {}
        actual_yields = {}

        for _, row in group.iterrows():
            mat = str(row[name_col]).strip()
            val = float(row[val_col])
            direction = str(row[io_col]).strip()

            if direction == "进" and mat != "新氢":
                feed_comp[mat] = feed_comp.get(mat, 0) + val
            elif direction == "出":
                actual_yields[mat] = actual_yields.get(mat, 0) + val

        # 将实际收率转为百分比
        total_out = sum(actual_yields.values())
        if total_out > 0:
            actual_yields = {k: v / total_out * 100 for k, v in actual_yields.items()}

        if feed_comp and actual_yields:
            training_data.append({
                "feed_composition": feed_comp,
                "T": default_T,          # 占位，待DCS数据替换
                "cat_days": cat_days,
                "LHSV": default_LHSV,    # 占位
                "P_h2": default_P_h2,    # 占位
                "actual_yields": actual_yields,
                "date": date.strftime("%Y-%m-%d"),
            })

    return training_data

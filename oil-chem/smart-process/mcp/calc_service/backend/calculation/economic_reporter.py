# -*- coding: utf-8 -*-
"""经济效益报告生成（从 solve_service.py 下移）。"""


def aggregate_economics(optimal_explanations: list,
                        monthly_load: dict = None,
                        start_device_id: str = None) -> dict:
    """统一聚合最优组合各批次 explanation（SSOT，供文本/结构化两个渲染方法共用）。

    口径基准（与 batch_optimizer 折减逻辑同源）：
      - theoretical_profit = Σ exp.total_economic_benefit（顶层字段，未折减）
      - crude_cost / energy_cost = Σ exp.crude_cost / exp.energy_cost（顶层字段）
      - crude_input = Σ exp.batch_input（原油加工量，吨油指标分母）
      - 装置级明细从 economic_analysis / feed_details / process_details 聚合
      - CDU 产出从 exp.all_product_outputs[start_device_id] 聚合（修复原 dev_map 取不到的 bug）
    返回 None 当 optimal_explanations 为空。
    """
    if not optimal_explanations:
        return None

    # ── 总量（顶层字段，与 batch_optimizer 折减同源）──
    total_crude_cost = sum(exp.get('crude_cost', 0) for exp in optimal_explanations)
    total_energy_cost = sum(exp.get('energy_cost', 0) for exp in optimal_explanations)
    theoretical_profit = sum(exp.get('total_economic_benefit', 0) for exp in optimal_explanations)
    total_days = sum(exp.get('days', 0) for exp in optimal_explanations)
    crude_input = sum(exp.get('batch_input', 0) for exp in optimal_explanations)

    # ── 装置级聚合：device_id → 跨批次合并（全部为批次值，直接累加）──
    dev_map: dict = {}
    for exp in optimal_explanations:
        # 进料明细（feed_cost/feed_qty/cost 均为批次值）
        for fd in exp.get('feed_details', []):
            did = fd.get('device_id', '')
            if not did:
                continue
            if did not in dev_map:
                dev_map[did] = {'feeds': {}, 'products': {}, 'revenue': 0,
                                'crude_cost': 0, 'energy_cost': 0, 'input_amount': 0,
                                'effective_input': 0, 'device_name': ''}
            d = dev_map[did]
            d['crude_cost'] += fd.get('feed_cost', 0) or 0
            for it in fd.get('items', []):
                pid = it.get('product_id', '') or it.get('name', '')
                if pid not in d['feeds']:
                    d['feeds'][pid] = {'name': it.get('name', ''), 'label': it.get('label', ''),
                                       'qty': 0, 'price': it.get('price', 0), 'cost': 0}
                d['feeds'][pid]['qty'] += it.get('feed_qty', 0) or 0
                d['feeds'][pid]['cost'] += it.get('cost', 0) or 0
        # 产出明细（economic_analysis 全部为批次值，直接累加）
        for item in exp.get('economic_analysis', []):
            did = item.get('device_id', '')
            if not did:
                continue
            if did not in dev_map:
                dev_map[did] = {'feeds': {}, 'products': {}, 'revenue': 0,
                                'crude_cost': 0, 'energy_cost': 0, 'input_amount': 0,
                                'effective_input': 0, 'device_name': ''}
            d = dev_map[did]
            d['device_name'] = item.get('device_name', did)
            d['revenue'] += item.get('revenue', 0) or 0
            d['input_amount'] += item.get('input_amount', 0) or 0
            d['effective_input'] += item.get('effective_input', 0) or 0
            for prod in item.get('products', []):
                pn = prod.get('product_name', '')
                if pn not in d['products']:
                    d['products'][pn] = {'output': 0, 'revenue': 0, 'price': prod.get('price', 0),
                                         'yield_rate': prod.get('yield_rate', 0),
                                         'yield_type': prod.get('yield_type', ''),
                                         'yield_reason': prod.get('yield_reason', ''),
                                         'product_id': prod.get('product_id', '')}
                d['products'][pn]['output'] += prod.get('output', 0) or 0
                d['products'][pn]['revenue'] += prod.get('revenue', 0) or 0
        # 加工成本（process_cost 为批次值，直接累加）
        for pd_item in exp.get('process_details', []):
            did = pd_item.get('device_id', '')
            if did in dev_map:
                dev_map[did]['energy_cost'] += pd_item.get('process_cost', 0) or 0

    # ── CDU 产出（从 all_product_outputs 聚合，修复原 dev_map 取不到的 bug）──
    cdu_outputs: dict = {}
    for exp in optimal_explanations:
        apo = exp.get('all_product_outputs', {}) or {}
        # 优先用传入的 start_device_id，否则回退到 key 含 'cjy' 的
        cdu_key = start_device_id if start_device_id else next(
            (k for k in apo if 'cjy' in str(k)), None)
        if not cdu_key:
            continue
        for pn, p in (apo.get(cdu_key) or {}).items():
            if pn not in cdu_outputs:
                cdu_outputs[pn] = {'output': 0}
            cdu_outputs[pn]['output'] += p.get('output', 0) or 0

    # ── 产品级跨装置聚合 ──
    all_products: dict = {}
    for did, d in dev_map.items():
        for pn, p in d['products'].items():
            if pn not in all_products:
                all_products[pn] = {'quantity': 0, 'revenue': 0, 'price': p['price'],
                                    'sources': []}
            all_products[pn]['quantity'] += p['output']
            all_products[pn]['revenue'] += p['revenue']
            all_products[pn]['sources'].append((d['device_name'], p['output'], p['revenue']))
    product_revenue_total = sum(p['revenue'] for p in all_products.values())

    # ── 月负荷折减信息 ──
    ml_devices = {}
    if monthly_load:
        for d in monthly_load.get('devices', []):
            ml_devices[d['device_id']] = d

    return {
        'totals': {
            'crude_cost': total_crude_cost,
            'energy_cost': total_energy_cost,
            'theoretical_profit': theoretical_profit,
            'crude_input': crude_input,           # 原油加工量（吨油指标分母）
            'total_days': total_days,
            'product_revenue': product_revenue_total,
        },
        'devices': dev_map,                        # device_id → 聚合（未过滤）
        'all_products': all_products,
        'cdu_outputs': cdu_outputs,
        'ml_devices': ml_devices,
    }


def build_economic_explanation(agg: dict,
                               actual_profit: float = None,
                               near_feasible: bool = False) -> str:
    """从聚合数据生成经济效益说明文字（纯渲染层，不做任何聚合计算）。

    agg = aggregate_economics(...) 的返回值。
    actual_profit=optimal_revenue(折减后)，理论效益=agg.totals.theoretical_profit。
    """
    if not agg:
        return ""

    SEP = "═" * 55
    t = agg['totals']
    dev_map = agg['devices']
    all_products = agg['all_products']
    cdu_outputs = agg['cdu_outputs']
    ml_devices = agg['ml_devices']

    total_crude_cost = t['crude_cost']
    total_energy_cost = t['energy_cost']
    theoretical_profit = t['theoretical_profit']
    total_input = t['crude_input']          # 原油加工量
    total_days = t['total_days']
    product_revenue_total = t['product_revenue']

    # 折减后利润
    if actual_profit is None:
        actual_profit = theoretical_profit
    reduced_profit = theoretical_profit - actual_profit
    has_reduction = abs(reduced_profit) > 0.01

    # ══════════ 组装文本 ══════════
    tag = "（接近可行 · 月负荷超容已折减）" if (near_feasible and has_reduction) else ""
    text = f"【经济效益分析说明】{tag}\n\n"
    text += f"总收益：{actual_profit:,.2f} 元（{actual_profit / 10000:,.2f} 万元）\n\n"

    # ── 一、装置经济效益明细 ──
    text += f"{SEP}\n一、装置经济效益明细（{'折减后口径' if has_reduction else '全额口径'}）\n{SEP}\n\n"
    dev_idx = 0
    for did, d in dev_map.items():
        # 跳过常减压(cjy_01)和储罐，只展示加工装置
        if 'cjy_01' in did or 'tank' in did:
            continue
        if d['revenue'] <= 0 and d['input_amount'] <= 0:
            continue
        dev_idx += 1
        dname = d['device_name'] or did
        text += f"【{dev_idx}】{dname}（{did}）\n"

        # 进料明细
        feeds = list(d['feeds'].values())
        total_feed_qty = sum(f['qty'] for f in feeds)
        if feeds:
            text += "  ── 进料明细 ──\n"
            for f in feeds:
                ratio = (f['qty'] / total_feed_qty * 100) if total_feed_qty > 0 else 0
                daily_qty = (f['qty'] / total_days) if total_days > 0 else 0
                lbl = f"【{f['label']}】" if f['label'] else ""
                text += (f"  {lbl}{f['name']}  配比 {ratio:.1f}%  "
                         f"日量 {daily_qty:,.0f} 吨/天  ×{total_days:.0f}天 = {f['qty']:,.0f} 吨\n")
            text += f"  总进料：{(total_feed_qty / total_days) if total_days > 0 else 0:,.0f} 吨/天 × {total_days:.0f}天 = {total_feed_qty:,.0f} 吨\n"

        # 产出明细
        prods = sorted(d['products'].items(), key=lambda x: x[1]['revenue'], reverse=True)
        if prods:
            text += "  ── 产出明细 ──\n"
            for pn, p in prods:
                out = p['output']
                oy = (out / total_feed_qty * 100) if total_feed_qty > 0 else 0
                price = p['price']
                rev = p['revenue']
                ytag = f"综合收率 {oy:.1f}%" if out else ""
                text += (f"  - {pn}  {ytag}  产量 {out:,.0f} 吨 × "
                         f"{price:,.0f} 元/吨 = {rev:,.0f} 元\n")
            text += "  ── 效益核算 ──\n"
            text += f"  产品销售收入：{d['revenue']:,.0f} 元\n"
            text += f"  原料成本：    {d['crude_cost']:,.0f} 元\n"
            text += f"  加工成本：    {d['energy_cost']:,.0f} 元\n"
            dev_profit = d['revenue'] - d['crude_cost'] - d['energy_cost']
            text += f"  装置利润：    {dev_profit:,.0f} 元\n"

        # 月负荷折减
        ml = ml_devices.get(did)
        if ml:
            if ml.get('is_overloaded'):
                text += "  ── 月负荷折减 ──\n"
                text += f"  ⚠ 月负荷 {ml['monthly_util']:.1f}% 超容\n"
                text += f"     月度能力 = {ml['monthly_capacity']:,.0f} 吨（有效天数 {ml['effective_days']:.1f}天）\n"
                text += f"     应加工量 = {ml['monthly_input']:,.0f} 吨\n"
                text += f"     未加工量 = {ml['unprocessed_material']:,.0f} 吨（缓存在中间罐）\n"
                text += f"     理论利润 → 折减 → 实际利润（见总体汇总）\n"
            else:
                text += f"  ✓ 月负荷 {ml['monthly_util']:.1f}% 未超容，无折减\n"
        text += "\n"

    # ── 二、常减压装置产出汇总 ──
    if cdu_outputs:
        text += f"{SEP}\n二、常减压装置产出汇总\n{SEP}\n"
        text += f"  原油加工：{(total_input / total_days) if total_days > 0 else 0:,.0f} 吨/天 × {total_days:.0f}天 = {total_input:,.0f} 吨\n"
        text += "  ── 侧线产出 ──\n"
        for pn, p in sorted(cdu_outputs.items(), key=lambda x: x[1]['output'], reverse=True):
            out = p['output']
            oy = (out / total_input * 100) if total_input > 0 else 0
            ytag = f"综合收率 {oy:.1f}%" if out else ""
            text += f"  - {pn}  {ytag}  产量 {out:,.0f} 吨\n"
        text += "  常减压不计效益（仅产出中间料，价值转移到下游装置）\n\n"

    # ── 三、总体经济效益汇总 ──
    text += f"{SEP}\n三、总体经济效益汇总\n{SEP}\n\n"

    # 主要产品产出汇总
    if all_products:
        text += "  ── 主要产品产出汇总（跨装置聚合）──\n"
        sorted_prods = sorted(all_products.items(), key=lambda x: x[1]['revenue'], reverse=True)
        for i, (pn, p) in enumerate(sorted_prods[:15], 1):
            share = (p['revenue'] / product_revenue_total * 100) if product_revenue_total > 0 else 0
            text += (f"  {i}. {pn}  产量 {p['quantity']:,.0f} 吨 × {p['price']:,.0f} 元/吨 "
                     f"= {p['revenue']:,.0f} 元  占比 {share:.1f}%\n")
            if len(p['sources']) > 1:
                for src_name, src_out, src_rev in p['sources']:
                    text += f"     └ {src_name}  {src_out:,.0f} 吨  {src_rev:,.0f} 元\n"
        text += f"  ─────────────────────────────────────────────────\n"
        text += f"  合计（产品销售收入）：{product_revenue_total:,.0f} 元\n\n"

    # 成本汇总
    text += "  ── 成本汇总 ──\n"
    text += f"  装置原料成本：{total_crude_cost:,.2f} 元\n"
    text += f"  加工成本：    {total_energy_cost:,.2f} 元\n"
    text += f"  总成本：      {total_crude_cost + total_energy_cost:,.2f} 元\n\n"

    # 效益对比
    text += "  ── 效益对比 ──\n"
    text += f"  理论效益（未折减）：{theoretical_profit:,.2f} 元\n"
    if has_reduction:
        text += f"  月负荷折减：        {reduced_profit:,.2f} 元\n"
        text += f"  实际效益（折减后）：{actual_profit:,.2f} 元 ← 与最优组合效益一致\n"
    else:
        text += f"  实际效益：          {actual_profit:,.2f} 元\n\n"

    # 吨油指标
    if total_input > 0:
        text += "  ── 吨油指标 ──\n"
        text += f"  原油加工量：{total_input:,.0f} 吨\n"
        text += f"  吨油收入：  {product_revenue_total / total_input:,.0f} 元/吨\n"
        text += f"  吨油成本：  {(total_crude_cost + total_energy_cost) / total_input:,.0f} 元/吨\n"
        text += f"  吨油利润：  {actual_profit / total_input:,.0f} 元/吨\n"
    text += "\n"

    # ── 四、月负荷折减明细汇总 ──
    if has_reduction and ml_devices:
        text += f"{SEP}\n四、月负荷折减明细汇总\n{SEP}\n"
        text += f"  {'装置':<12} {'月负荷':>8} {'能力(吨)':>12} {'应加工(吨)':>12} {'未加工(吨)':>12}\n"
        for did, ml in ml_devices.items():
            dname = ml.get('name', did)
            tag = "⚠" if ml.get('is_overloaded') else "✓"
            text += (f"  {tag} {dname:<10} {ml['monthly_util']:>6.1f}% "
                     f"{ml['monthly_capacity']:>10,.0f} {ml['monthly_input']:>10,.0f} "
                     f"{ml['unprocessed_material']:>10,.0f}\n")
        total_unprocessed = sum(ml['unprocessed_material'] for ml in ml_devices.values())
        text += f"  合计未加工中间料：{total_unprocessed:,.0f} 吨\n"
        text += f"  合计折减利润：{reduced_profit:,.2f} 元\n\n"

    # ── 五、关键假设与说明 ──
    text += f"{SEP}\n五、关键假设与说明\n{SEP}\n"
    text += "  - 产品价格：引用自系统定价表（随市场波动定期更新）\n"
    text += "  - 原料价格：采用用户输入的采购单价\n"
    text += "  - 加工成本：基于标准能耗系数估算，实际可能因设备状态波动\n"
    text += "  - 装置收率：采用固定收率（实际可能因原油性质变化而略有偏差）\n"
    if has_reduction:
        text += "  - 月负荷超容时按满负荷折减（先产先加工），未加工原料缓存在中间罐\n"
        text += "  - 接近可行方案：罐容无违规 BUT 月负荷超容，折减后效益供决策参考\n"
    text += "  - 经济效益正值表示盈利，负值表示亏损\n"
    return text


def build_economic_breakdown(agg: dict,
                             processing_device_ids: set = None,
                             actual_profit: float = None) -> dict:
    """从聚合数据生成结构化效益拆解（纯渲染层，与 build_economic_explanation 同源同值）。

    agg = aggregate_economics(...) 的返回值。
    总量口径与 explanation 完全一致（顶层字段聚合），不从装置级反推。
    返回 {} 当 agg 为空。
    """
    if not agg:
        return {}

    t = agg['totals']
    dev_map = agg['devices']
    all_products = agg['all_products']
    ml_devices = agg['ml_devices']

    # ── 装置级：从 dev_map 转为列表，过滤为加工装置 ──
    devices = []
    for did, d in dev_map.items():
        # 过滤为加工装置（排除常减压CDU和储罐）；processing_device_ids 为空时保留全部
        if processing_device_ids and did not in processing_device_ids:
            continue
        if d['revenue'] <= 0 and d['input_amount'] <= 0:
            continue
        dev_profit = d['revenue'] - d['crude_cost'] - d['energy_cost']
        # 进料明细（与 explanation 同源，含配比/单价/成本）
        feeds_list = list(d['feeds'].values())
        total_feed_qty = sum(f['qty'] for f in feeds_list)
        feeds = []
        for f in feeds_list:
            ratio = (f['qty'] / total_feed_qty * 100) if total_feed_qty > 0 else 0
            feeds.append({
                'name': f['name'],
                'label': f['label'],           # 主料/辅料
                'quantity': f['qty'],           # 总量(吨)
                'ratio': round(ratio, 2),       # 配比(%)
                'price': f['price'],            # 单价(元/吨)
                'cost': f['cost'],              # 成本(元)
            })
        feeds.sort(key=lambda x: x['quantity'], reverse=True)
        # 连接主料（主料中 feed_qty 最大者）+ 主料负荷量（Σ 主料 qty）
        main_feeds = [f for f in feeds if f['label'] == '主料']
        main_load_qty = sum(f['quantity'] for f in main_feeds)
        conn_main = max(main_feeds, key=lambda x: x['quantity']) if main_feeds else None
        main_feed_name = conn_main['name'] if conn_main else ''
        main_feed_qty = conn_main['quantity'] if conn_main else 0
        devices.append({
            'device_id': did,
            'device_name': d['device_name'] or did,
            'input_amount': d['input_amount'],
            'effective_input': d.get('effective_input', 0),
            'total_feed_qty': total_feed_qty,   # 总进料量(吨，含辅料)
            'main_feed_name': main_feed_name,   # 连接主料名
            'main_feed_qty': main_feed_qty,     # 连接主料量(吨)
            'main_load_qty': main_load_qty,     # 主料负荷量(吨，Σ 主料)
            'revenue': d['revenue'],
            'crude_cost': d['crude_cost'],
            'energy_cost': d['energy_cost'],
            'profit': dev_profit,
            'feeds': feeds,
            'products': [
                {'product_id': p.get('product_id', ''), 'product_name': pn,
                 'overall_yield': round((p['output'] / total_feed_qty * 100), 2) if total_feed_qty > 0 and p['output'] else 0,
                 'price': p['price'], 'output': p['output'], 'revenue': p['revenue']}
                for pn, p in d['products'].items()
            ],
        })
    # 装置级收入合计用于算占比
    device_revenue_total = sum(d['revenue'] for d in devices)
    for d in devices:
        d['share'] = round(d['revenue'] / device_revenue_total * 100, 2) if device_revenue_total else 0
    devices.sort(key=lambda x: x['revenue'], reverse=True)

    # ── 产品级聚合：转为列表 ──
    products = []
    for pn, p in all_products.items():
        products.append({
            'product_name': pn,
            'quantity': p['quantity'],
            'price': p['price'],
            'revenue': p['revenue'],
        })
    product_revenue_total = sum(p['revenue'] for p in products)
    for p in products:
        p['share'] = round(p['revenue'] / product_revenue_total * 100, 2) if product_revenue_total else 0
    products.sort(key=lambda x: x['revenue'], reverse=True)

    # ── 总量（与 explanation 同源：顶层字段聚合，不从装置级反推）──
    total_crude_cost = t['crude_cost']
    total_energy_cost = t['energy_cost']
    theoretical_profit = t['theoretical_profit']
    crude_input = t['crude_input']          # 原油加工量（吨油指标分母，与 explanation 一致）

    # 折减信息
    if actual_profit is not None:
        reduced_profit = theoretical_profit - actual_profit
    else:
        actual_profit = theoretical_profit
        reduced_profit = 0.0
    has_reduction = abs(reduced_profit) > 0.01

    # ── 月负荷折减明细 ──
    reduction_devices = []
    if has_reduction and ml_devices:
        for did, ml_d in ml_devices.items():
            if ml_d.get('is_overloaded'):
                reduction_devices.append({
                    'device_id': did,
                    'device_name': ml_d.get('name', did),
                    'monthly_util': ml_d.get('monthly_util', 0),
                    'capacity': ml_d.get('monthly_capacity', 0),
                    'raw_material': ml_d.get('monthly_input', 0),
                    'unprocessed': ml_d.get('unprocessed_material', 0),
                })

    return {
        'totals': {
            'crude_cost': total_crude_cost,
            'energy_cost': total_energy_cost,
            'product_revenue': product_revenue_total,   # 与 explanation 合计一致
            'total_cost': total_crude_cost + total_energy_cost,
            'theoretical_profit': theoretical_profit,   # 未折减（与 explanation 同源）
            'reduced_profit': reduced_profit,           # 折减额
            'profit': actual_profit,                    # 折减后（= optimal_revenue）
            'total_input': crude_input,                 # 原油加工量（与 explanation 一致）
        },
        'devices': devices,
        'products': products,
        'reduction': {
            'is_reduced': has_reduction,
            'devices': reduction_devices,
            'total_unprocessed': sum(d['unprocessed'] for d in reduction_devices),
            'total_reduced_profit': reduced_profit,
        } if has_reduction else None,
    }

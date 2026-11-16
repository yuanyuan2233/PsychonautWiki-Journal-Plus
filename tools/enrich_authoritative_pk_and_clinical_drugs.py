# -*- coding: utf-8 -*-
"""
Phase 5: Authoritative Pharmacopoeia PK Enrichment & Clinical Co-Prescribed Drug Expansion.

Idempotently:
1. Enriches ~98 existing substances in Substances.json that have verified human clinical PK
   parameters (sourced from DrugBank, FDA DailyMed, PubMed/NCBI, EMA SmPC, and peer-reviewed
   clinical PK studies), while explicitly leaving unverified / animal-only / novel RC substances
   without fabricated pharmacokinetics.
2. Adds 10 clinical co-prescribed Rx drugs across:
   - app/src/main/assets/Substances.json
   - app/src/main/assets/substances_zh_dict.json
   - app/src/main/java/com/isaakhanimann/journal/ui/tabs/search/SubstanceLocalizationHelper.kt
3. Validates JSON integrity, strictly monotonic dose tiers, positive numeric PK fields,
   valid pkRole values, valid categories, and intentional omissions.

Usage:
    python tools/enrich_authoritative_pk_and_clinical_drugs.py
"""

from __future__ import annotations

import copy
import json
import re
from pathlib import Path
from typing import Any

from pypinyin import Style, lazy_pinyin


ROOT = Path(__file__).resolve().parent.parent
SUBSTANCES_PATH = ROOT / "app" / "src" / "main" / "assets" / "Substances.json"
ZH_DICT_PATH = ROOT / "app" / "src" / "main" / "assets" / "substances_zh_dict.json"
HELPER_KT_PATH = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "isaakhanimann"
    / "journal"
    / "ui"
    / "tabs"
    / "search"
    / "SubstanceLocalizationHelper.kt"
)

VALID_PK_ROLES = {
    "PARENT_DOMINANT",
    "ACTIVE_METABOLITE",
    "PRODRUG",
    "IRREVERSIBLE_PD",
    "DEPOT_FLIP_FLOP",
    "EXTENDED_RELEASE",
    "ZERO_ORDER",
}

VALID_DURATION_UNITS = {"seconds", "minutes", "hours", "days"}

# Explicitly verified to lack published peer-reviewed human plasma PK (half-life/Tmax):
# NEVER fabricate or extrapolate rodent/NHP PK values to humans for these entries.
INTENTIONALLY_OMITTED_NO_HUMAN_PK = [
    "Ethylphenidate",
    "4F-MPH",
    "Isopropylphenidate",
    "1cP-MiPLA",
    "3-Me-PCPy",
    "25N-NBOMe",
    "MXiPr",
    "RGPU-95",
    "Omberacetam",
    "Meclofenoxate",
    "Alpha-GPC",
    "Fenethylline",
    "Poppers",
    "2C-H",
    "3-Cl-PCP",
    "4-CA",
    "8-CTP",
    "Compound Licorice Tablets",
    "Psilocybin mushrooms",
    "Shrooms",
    "Psilocybe cubensis",
]


def build_pinyin_tokens(names: list[str]) -> tuple[list[str], list[str], list[str]]:
    """Generate spaced pinyin, joined pinyin, and abbr tokens matching project conventions."""
    pinyin_spaced: list[str] = []
    pinyin_raw: list[str] = []
    abbrs: list[str] = []
    for name in names:
        has_cjk = any("\u4e00" <= ch <= "\u9fff" for ch in name)
        if not has_cjk:
            continue
        syllables = [s for s in lazy_pinyin(name, style=Style.NORMAL) if s]
        if not syllables:
            continue
        spaced = "'".join(syllables)
        joined = "".join(syllables)
        abbr = "".join(s[0] for s in syllables if s)
        pinyin_spaced.append(spaced)
        pinyin_raw.append(joined)
        if abbr:
            abbrs.append(abbr)
    return (
        list(dict.fromkeys(pinyin_spaced)),
        list(dict.fromkeys(pinyin_raw)),
        list(dict.fromkeys(abbrs)),
    )


def make_common_names(zh_names: list[str], en_aliases: list[str]) -> list[str]:
    spaced, joined, abbrs = build_pinyin_tokens(zh_names)
    combined_pinyin: list[str] = []
    for sp, jn in zip(spaced, joined):
        combined_pinyin.append(sp)
        combined_pinyin.append(jn)
    return list(dict.fromkeys([*zh_names, *en_aliases, *abbrs, *combined_pinyin]))


def met(
    name: str,
    pathway: str,
    half_life: str,
    half_life_hours: float,
    t_max_hours: float | None,
    exposure_ratio: float,
    relative_exposure: str,
    role: str,
) -> dict[str, Any]:
    obj: dict[str, Any] = {
        "name": name,
        "pathway": pathway,
        "halfLife": half_life,
        "halfLifeHours": float(half_life_hours),
        "exposureRatio": float(exposure_ratio),
        "relativeExposure": relative_exposure,
        "pharmacologicalRole": role,
    }
    if t_max_hours is not None:
        obj["tMaxHours"] = float(t_max_hours)
    return obj


def target(
    name: str,
    action: str,
    tier: str,
    weight: float,
    actor: str = "母体主导",
) -> dict[str, Any]:
    return {
        "target": name,
        "action": action,
        "affinityTier": tier,
        "affinityWeight": float(weight),
        "dominantActor": actor,
    }


def pk(
    *,
    half_life: str,
    half_life_hours: float,
    t_max: str,
    t_max_hours: float,
    bioavailability: str,
    cyp_enzymes: list[str],
    clearance: str,
    pk_role: str = "PARENT_DOMINANT",
    effective_half_life_hours: float | None = None,
    washout_hours: float | None = None,
    washout_note: str,
    mechanism_summary: str,
    active_metabolites: list[dict[str, Any]] | None = None,
    receptor_targets: list[dict[str, Any]],
    brand_name: str | None = None,
    indications: list[str] | None = None,
    contraindications: list[str] | None = None,
) -> dict[str, Any]:
    eff_hl = float(effective_half_life_hours if effective_half_life_hours is not None else half_life_hours)
    wo_hours = float(washout_hours if washout_hours is not None else round(eff_hl * 5.0, 1))
    obj: dict[str, Any] = {
        "halfLife": half_life,
        "halfLifeHours": float(half_life_hours),
        "tMax": t_max,
        "tMaxHours": float(t_max_hours),
        "bioavailability": bioavailability,
        "cypEnzymes": cyp_enzymes,
        "clearance": clearance,
        "pkRole": pk_role,
        "effectiveHalfLifeHours": eff_hl,
        "washoutHours": wo_hours,
        "washoutNote": washout_note,
        "mechanismSummary": mechanism_summary,
        "activeMetabolites": active_metabolites or [],
        "receptorTargets": receptor_targets,
    }
    if brand_name:
        obj["brandName"] = brand_name
    if indications:
        obj["indications"] = indications
    if contraindications:
        obj["contraindications"] = contraindications
    return obj


# ---------------------------------------------------------------------------
# 1. Ten new clinical co-prescribed Rx medications (FDA DailyMed / DrugBank)
# ---------------------------------------------------------------------------
NEW_CLINICAL_DRUGS: list[dict[str, Any]] = [
    {
        "name": "Propranolol",
        "zh_name": "普萘洛尔",
        "zh_aliases": ["普萘洛尔", "心得安", "盐酸普萘洛尔", "Inderal", "InnoPran XL"],
        "commonNames": make_common_names(
            ["普萘洛尔", "心得安", "盐酸普萘洛尔"],
            ["Inderal", "Inderal LA", "InnoPran XL"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00571",
        "isApproved": True,
        "tolerance": {
            "full": "β受体长期阻断可致受体上调，骤停易引发严重反跳性心动过速与心绞痛",
            "half": "停药需至少 1 - 2 周逐步减量",
            "zero": "停药后约 2 - 4 天心血管反应恢复",
        },
        "crossTolerances": ["beta-blocker"],
        "addictionPotential": "无成瘾性或欣快感，但存在显著躯体停药反跳风险（严禁骤停）",
        "toxicities": [
            "非选择性 β2 阻断可诱发致命性支气管哮喘急性痉挛",
            "掩盖糖尿病患者低血糖引发的心悸与震颤预警信号",
            "过量可导致重度心动过缓、房室传导阻滞、难治性低血压及中枢癫痫发作",
        ],
        "categories": ["antihypertensive", "anxiolytic", "common"],
        "categories_zh": ["降压药", "抗焦虑药", "常见物质"],
        "summary": "经典高脂溶性非选择性 β1/β2 肾上腺素受体拮抗剂（心得安），既用于高血压、心绞痛、特发性震颤及偏头痛预防，也广泛在临床与演艺/社交场景下抑制兴奋剂或焦虑引发的心悸与手抖等躯体交感症状；口服首过经 CYP2D6 生成等强活性代谢物 4-羟普萘洛尔。",
        "interactions": {
            "dangerous": [
                "非二氢吡啶类钙通道阻滞剂 (维拉帕米/地尔硫卓，严重叠加致高度房室传导阻滞与心搏骤停)",
                "可乐定突然停药期间联用 (未拮抗 α 受体血管收缩可诱发高血压危象)",
                "可卡因等强拟交感兴奋剂理论争议交互（需警惕无阻断 α 介导冠脉痉挛与血压剧变）",
            ],
            "unsafe": [
                "CYP2D6 强抑制剂 (如氟西汀、帕罗西汀、奎尼丁，显著升高母体血药浓度致心动过缓)",
                "CYP1A2 抑制剂 (如氟伏沙明、环丙沙星，抑制侧链氧化代谢)",
                "胰岛素及磺脲类降糖药 (掩盖低血糖心动过速警示并阻碍糖原分解恢复)",
            ],
            "uncertain": [
                "右美沙芬/MDMA 等 CYP2D6 底物或机制性抑制剂",
                "高蛋白饮食 (可使口服相对生物利用度提高约 50%)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 10.0,
                    "commonMin": 40.0,
                    "strongMin": 80.0,
                    "heavyMin": 160.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 45.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.5, "max": 3.0, "units": "hours"},
                    "offset": {"min": 3.0, "max": 5.0, "units": "hours"},
                    "total": {"min": 4.0, "max": 8.0, "units": "hours"},
                },
                "bioavailability": {"min": 25.0, "max": 30.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="心得安 (Inderal / Inderal LA)",
            half_life="母体 3.0 - 6.0 小时 (典型 4.0h；缓释胶囊表观 8 - 11h)；活性代谢物 4-羟普萘洛尔约 5.0 - 8.0 小时",
            half_life_hours=4.0,
            t_max="1.0 - 3.0 小时 (常释)；约 6.0 小时 (缓释)",
            t_max_hours=1.5,
            bioavailability="口服 25% - 30% (剧烈肝脏首过效应；高蛋白餐提高系统暴露)",
            cyp_enzymes=[
                "CYP2D6 (主要代谢底物 / Substrate；羟化生成 4-羟普萘洛尔)",
                "CYP2D6 (弱至中度抑制剂 / Inhibitor)",
                "CYP1A2 (主要侧链氧化底物 / Substrate)",
                "CYP2C19 (代谢底物 / Substrate)",
            ],
            clearance="肝脏广泛氧化（芳香羟化与 N-脱异丙基）及葡萄糖醛酸化后经尿液排出（> 90%，原形 < 1%）",
            indications=[
                "高血压、心绞痛与心肌梗死后二级预防",
                "特发性震颤与甲状腺毒症交感症状控制",
                "偏头痛预防及情境性躯体焦虑（心悸、震颤）",
            ],
            contraindications=[
                "支气管哮喘或重度慢性阻塞性肺疾病（绝对禁忌）",
                "心源性休克、失代偿性心衰、窦性心动过缓及 II/III 度房室传导阻滞",
            ],
            pk_role="ACTIVE_METABOLITE",
            effective_half_life_hours=5.5,
            washout_hours=30.0,
            washout_note="母体半衰期约 4 小时，口服首过生成的等强活性代谢物 4-羟普萘洛尔终端消除约 5 - 8 小时；连续服药者切勿骤停，否则 24 - 72 小时内易发生反跳性交感亢进。",
            mechanism_summary="高脂溶性、易于血脑屏障渗透的非选择性 β1/β2 肾上腺素受体竞争性拮抗剂，外周降心率降压与中枢稳定躯体震颤并重。",
            active_metabolites=[
                met(
                    "4-Hydroxypropranolol (4-羟普萘洛尔)",
                    "母体口服经肝脏首过 CYP2D6 4-位羟化生成（静注几乎不产生）",
                    "3.0 - 8.0 小时 (终端典型 ~5.5h)",
                    5.5,
                    1.5,
                    0.4,
                    "口服给药贡献明显急性 β 阻断暴露，血浆浓度随首过效应波动",
                    "对 β 受体阻断效价与母体相当，协同贡献口服初期显著的降心率与抗震颤效应",
                )
            ],
            receptor_targets=[
                target("β1 肾上腺素受体", "强效竞争性拮抗剂 (Ki ≈ 1.2 nM，抑制心肌收缩力与窦房结自律性)", "high", 0.96, "双相共同主导"),
                target("β2 肾上腺素受体", "强效竞争性拮抗剂 (Ki ≈ 1.2 nM，缓解躯体震颤但可致支气管痉挛)", "high", 0.95, "双相共同主导"),
                target("5-HT1A 受体", "弱至中等拮抗/反向调节 (高脂溶性透脑辅助中枢抗焦虑)", "low", 0.42, "母体主导"),
            ],
        ),
    },
    {
        "name": "Metoprolol",
        "zh_name": "美托洛尔",
        "zh_aliases": ["美托洛尔", "倍他乐克", "酒石酸美托洛尔", "琥珀酸美托洛尔", "Lopressor", "Toprol-XL"],
        "commonNames": make_common_names(
            ["美托洛尔", "倍他乐克", "酒石酸美托洛尔", "琥珀酸美托洛尔"],
            ["Lopressor", "Toprol-XL", "Betaloc"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00264",
        "isApproved": True,
        "tolerance": {
            "full": "长期规律服药可致心肌 β1 受体密度代偿性上调",
            "half": "需于 1 - 2 周内逐步减停，避免骤停反跳",
            "zero": "停药后约 24 - 48 小时阻断消退",
        },
        "crossTolerances": ["beta-blocker"],
        "addictionPotential": "无精神依赖性，但骤停对冠心病患者有致命性反跳缺血风险",
        "toxicities": [
            "高血药浓度（如 CYP2D6 慢代谢或合用抑制剂）时丧失 β1 心脏选择性而阻断 β2 受体诱发喘息",
            "过量导致严重心动过缓、传导阻滞与低血压休克",
        ],
        "categories": ["antihypertensive", "anxiolytic", "common"],
        "categories_zh": ["降压药", "抗焦虑药", "常见物质"],
        "summary": "临床应用极广的心脏选择性 β1 肾上腺素受体拮抗剂（倍他乐克），包括酒石酸常释与琥珀酸微丸缓释两种主流剂型；高度依赖 CYP2D6 代谢，与帕罗西汀、氟西汀、安非他酮或右美沙芬等合用易发生血药浓度倍增与选择性丧失。",
        "interactions": {
            "dangerous": [
                "维拉帕米/地尔硫卓静脉或大剂量联用 (协同抑制窦房结与房室结致重度传导阻滞)",
                "高剂量化合抑制心脏传导的抗心律失常药 (如胺碘酮、普罗帕酮，后者亦强抑制 CYP2D6)",
            ],
            "unsafe": [
                "强效 CYP2D6 抑制剂 (氟西汀、帕罗西汀、安非他酮、奎尼丁、MDMA；可使美托洛尔 AUC 升高 2 - 5 倍并丧失 β1 选择性)",
                "酒精 (加速某些缓释制剂提前倾释或叠加低血压头晕)",
                "可乐定撤药期 (加重反跳高血压)",
            ],
            "uncertain": [
                "肼屈嗪或西咪替丁 (提高美托洛尔生物利用度与暴露量)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 25.0,
                    "commonMin": 50.0,
                    "strongMin": 100.0,
                    "heavyMin": 200.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.5, "max": 3.0, "units": "hours"},
                    "offset": {"min": 4.0, "max": 6.0, "units": "hours"},
                    "total": {"min": 6.0, "max": 12.0, "units": "hours"},
                },
                "bioavailability": {"min": 40.0, "max": 50.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="倍他乐克 (Lopressor / Toprol-XL / Betaloc)",
            half_life="正常代谢者 3.0 - 4.0 小时 (范围 3 - 7h)；CYP2D6 慢代谢者延长至 7.0 - 9.0 小时",
            half_life_hours=3.5,
            t_max="1.5 - 2.0 小时 (酒石酸常释)；琥珀酸缓释微丸呈现 20 小时级平稳血药平台",
            t_max_hours=1.8,
            bioavailability="口服常释 40% - 50% (琥珀酸缓释片相对生物利用度约 77%)",
            cyp_enzymes=[
                "CYP2D6 (核心代谢底物 / Substrate；承担约 70% - 80% 肝脏氧化清除，联用抑制剂暴露翻倍)",
            ],
            clearance="主要经肝脏 CYP2D6 O-脱甲基、α-羟化代谢（生成无显著活性的 α-羟美托洛尔等产物），约 95% 代谢物经尿液排出",
            indications=[
                "高血压、心绞痛与急性心肌梗死",
                "慢性稳定性心力衰竭（琥珀酸缓释制剂）",
                "心动过速、甲状腺功能亢进及偏头痛/躯体焦虑辅助心率控制",
            ],
            contraindications=[
                "重度心动过缓、II/III 度房室传导阻滞、病态窦房结综合征（无起搏器）",
                "失代偿性心力衰竭与心源性休克",
            ],
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=3.5,
            washout_hours=24.0,
            washout_note="CYP2D6 快/正常代谢者约 24 小时可基本清除常释美托洛尔（缓释制剂表观消除需延长）；但 CYP2D6 慢代谢者或联用帕罗西汀/氟西汀/安非他酮时清除延长 2 - 3 倍且易失选择性。",
            mechanism_summary="高选择性 β1 肾上腺素受体拮抗剂，以原形直接主导心脏变时、变力及血压控制。",
            active_metabolites=[],
            receptor_targets=[
                target("β1 肾上腺素受体", "选择性竞争性拮抗剂 (Ki ≈ 47 nM，心脏选择性约为 β2 的 20 - 35 倍)", "high", 0.94, "母体主导"),
                target("β2 肾上腺素受体", "低亲和力弱拮抗 (仅高血药浓度或 CYP2D6 抑制蓄积时阻断并致气道收缩)", "low", 0.30, "母体主导"),
            ],
        ),
    },
    {
        "name": "Lemborexant",
        "zh_name": "莱博雷生",
        "zh_aliases": ["莱博雷生", "达卫可", "Dayvigo", "E-2006"],
        "commonNames": make_common_names(
            ["莱博雷生", "达卫可"],
            ["Dayvigo", "E2006", "E-2006"],
        ),
        "url": "https://go.drugbank.com/drugs/DB14904",
        "isApproved": True,
        "tolerance": {
            "full": "临床试验连服 6 - 12 个月维持入睡与睡眠维持疗效，无明显反跳性失眠",
            "half": "停药后约 1 - 2 天受体拮抗消退",
            "zero": "完全消除需约 4 - 5 天",
        },
        "crossTolerances": ["orexin-antagonist"],
        "addictionPotential": "属 FDA Schedule IV 受控助眠药，躯体戒断风险远低于苯二氮䓬与 Z 类药物，但超剂量仍具滥用与复杂睡眠行为风险",
        "toxicities": [
            "次日残余嗜睡及驾驶反应受损（10 mg 剂量与女性/肝损人群风险上升）",
            "睡眠瘫痪、入睡前/醒前幻觉（催眠幻觉）与类似猝倒样肌无力反应",
            "梦游及夜间无记忆复杂睡眠行为",
        ],
        "categories": ["hypnotic", "sedative", "depressant"],
        "categories_zh": ["镇静催眠药", "镇静剂", "中枢抑制剂"],
        "summary": "新一代双食欲素受体拮抗剂（DORA，商品名达卫可 Dayvigo），通过高选择性可逆阻断下丘脑觉醒神经元 OX1R 与 OX2R 受体（偏好 OX2R）抑制觉醒驱动而非泛化激活 GABA-A；经 CYP3A4 代谢且有效半衰期约 17 - 19 小时。",
        "interactions": {
            "dangerous": [
                "强效或中效 CYP3A4 抑制剂 (如伊曲康唑、克拉霉素、氟康唑、红霉素、葡萄柚，FDA 明确要求避免合用以防严重过度镇静)",
                "酒精及阿片/苯二氮䓬等中枢神经系统抑制剂 (显著叠加次日精神运动损伤与呼吸/跌倒风险)",
            ],
            "unsafe": [
                "强效或中效 CYP3A4 诱导剂 (如利福平、卡马西平、圣约翰草，大幅削弱助眠暴露与疗效)",
                "弱效 CYP3A4 抑制剂 (如部分质子泵抑制剂/钙拮抗剂，日最大剂量须严格限制在 5 mg)",
            ],
            "uncertain": [
                "CYP2B6 敏感底物 (如安非他酮/美沙酮/依法韦仑，体外提示莱博雷生具轻度 CYP2B6 诱导潜力)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 2.5,
                    "commonMin": 5.0,
                    "strongMin": 10.0,
                    "heavyMin": 15.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 45.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.5, "max": 3.0, "units": "hours"},
                    "offset": {"min": 4.0, "max": 6.0, "units": "hours"},
                    "total": {"min": 7.0, "max": 9.0, "units": "hours"},
                },
                "bioavailability": {"min": 87.0, "max": 95.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="达卫可 (Dayvigo) | 卫材 (Eisai)",
            half_life="有效半衰期 17 小时 (5 mg) - 19 小时 (10 mg) (深室终端相约 55h 但主要药理消除由有效半衰期主导)",
            half_life_hours=18.0,
            t_max="1.0 - 3.0 小时 (空腹；高脂饱餐使 Tmax 推迟约 2 小时至 3 - 5h 并影响速效入睡)",
            t_max_hours=2.0,
            bioavailability="口服吸收良好，绝对生物利用度 ≥ 87%",
            cyp_enzymes=[
                "CYP3A4 (核心代谢底物 / Substrate；禁与强/中效 CYP3A 抑制剂或诱导剂联用)",
                "CYP2B6 (体外轻度诱导潜力 / Inducer)",
            ],
            clearance="主要经肝脏 CYP3A4（及少量 CYP3A5）代谢生成循环代谢物 M10（M10 受 P-gp 外排泵限制入脑，临床贡献低）；57% 经粪便、29% 经尿液排出",
            indications=[
                "成人失眠症（同时改善入睡困难及睡眠维持障碍）",
            ],
            contraindications=[
                "发作性睡病 (Narcolepsy) 患者（绝对禁忌，可加剧猝倒样症状）",
                "联用强效/中效 CYP3A4 抑制剂者",
            ],
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=18.0,
            washout_hours=90.0,
            washout_note="功能有效半衰期 17 - 19 小时确保整夜维持睡眠同时带来连续 4 - 5 日完全洗脱尾迹；循环产物 M10 虽结合 OX1R/OX2R 但因血脑屏障 P-gp 主动外排对脑内药效贡献微弱。",
            mechanism_summary="高亲和力 OX2R 偏好型双食欲素受体拮抗剂，关闭下丘脑促觉醒信号而非强行镇静皮层。",
            active_metabolites=[],
            receptor_targets=[
                target("OX2R (食欲素 2 型受体)", "超强效可逆竞争性拮抗剂 (Ki ≈ 0.48 nM，精准下调维持清醒信号)", "high", 0.98, "母体主导"),
                target("OX1R (食欲素 1 型受体)", "强效可逆竞争性拮抗剂 (Ki ≈ 8.1 nM，协同抑制觉醒转换)", "high", 0.85, "母体主导"),
            ],
        ),
    },
    {
        "name": "Suvorexant",
        "zh_name": "苏沃雷生",
        "zh_aliases": ["苏沃雷生", "Belsomra", "MK-4305"],
        "commonNames": make_common_names(
            ["苏沃雷生"],
            ["Belsomra", "MK-4305", "MK4305"],
        ),
        "url": "https://go.drugbank.com/drugs/DB09034",
        "isApproved": True,
        "tolerance": {
            "full": "长期给药不易产生类似苯二氮䓬类的耐受与躯体戒断",
            "half": "停药后约 1 - 2 天明显衰退",
            "zero": "约 2.5 - 3 天完全消除",
        },
        "crossTolerances": ["orexin-antagonist"],
        "addictionPotential": "FDA Schedule IV 受控药，依赖性低但超量有日间嗜睡、幻觉及睡眠瘫痪风险",
        "toxicities": [
            "次日晨间驾驶及警觉功能减退（20 mg 剂量与女性/肥胖高暴露人群尤须警惕）",
            "短暂睡眠瘫痪、生动的入睡/醒前幻觉及抑郁自杀意念加重的临床警惕信号",
        ],
        "categories": ["hypnotic", "sedative", "depressant"],
        "categories_zh": ["镇静催眠药", "镇静剂", "中枢抑制剂"],
        "summary": "全球首个获批临床的双食欲素受体拮抗剂（DORA，Belsomra），对 OX1R 与 OX2R 均具有亚纳摩尔级高亲和力竞争拮抗活性；主要经 CYP3A4 代谢为无药理活性的羟基产物，半衰期约 12 小时。",
        "interactions": {
            "dangerous": [
                "强效 CYP3A4 抑制剂 (如酮康唑、伊曲康唑、克拉霉素、利托那韦，FDA 明确不推荐同用，AUC 成倍飙升)",
                "酒精及苯二氮䓬/阿片/巴比妥类中枢抑制剂 (累加次日过度镇静及意外跌倒风险)",
            ],
            "unsafe": [
                "中效 CYP3A4 抑制剂 (如地尔硫卓、维拉帕米、红霉素、环丙沙星，起始剂量需降至 5 mg 且严控上限 10 mg)",
                "强效 CYP3A4 诱导剂 (如利福平、卡马西平、苯妥英，显著降低血药暴露并导致疗效丧失)",
            ],
            "uncertain": [
                "地高辛 (苏沃雷生略抑制肠道 P-gp，可能轻微升高地高辛浓度需监测)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 5.0,
                    "commonMin": 10.0,
                    "strongMin": 20.0,
                    "heavyMin": 30.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 45.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.5, "max": 3.0, "units": "hours"},
                    "offset": {"min": 4.0, "max": 6.0, "units": "hours"},
                    "total": {"min": 7.0, "max": 9.0, "units": "hours"},
                },
                "bioavailability": {"min": 80.0, "max": 85.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="Belsomra | 默沙东 (Merck)",
            half_life="约 12.0 小时 (范围 12 - 13h；女性与肥胖患者清除更慢且暴露量高于男性)",
            half_life_hours=12.0,
            t_max="2.0 小时 (空腹中位；个体范围 0.5 - 6.0h；高脂进食延迟约 1.5h)",
            t_max_hours=2.0,
            bioavailability="口服约 82% (10 mg 剂量；高剂量下吸收呈非线性递减)",
            cyp_enzymes=[
                "CYP3A4 (核心代谢底物 / Substrate；禁联强效抑制剂，中效抑制剂需减量至 5 mg)",
                "CYP2C19 (次要代谢底物 / Substrate)",
            ],
            clearance="主要经 CYP3A4 氧化生成无药理活性羟基代谢产物；约 66% 随粪便排出，23% 经尿液排出",
            indications=[
                "成人入睡困难及睡眠维持障碍型失眠症",
            ],
            contraindications=[
                "发作性睡病患者（绝对禁忌）",
                "与强效 CYP3A4 抑制剂合用",
            ],
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=12.0,
            washout_hours=60.0,
            washout_note="母体半衰期约 12 小时且无显著活性代谢物；停药后 2.5 - 3 日基本清洗完毕。肥胖女性暴露量明显偏高，需防晨间残余嗜睡。",
            mechanism_summary="均衡强效抑制 OX1R 与 OX2R 食欲素受体的初代 DORA 助眠药，母体原形主导整夜睡眠稳态维持。",
            active_metabolites=[],
            receptor_targets=[
                target("OX2R (食欲素 2 型受体)", "强效竞争性拮抗剂 (Ki ≈ 0.35 nM)", "high", 0.98, "母体主导"),
                target("OX1R (食欲素 1 型受体)", "强效竞争性拮抗剂 (Ki ≈ 0.55 nM)", "high", 0.95, "母体主导"),
            ],
        ),
    },
    {
        "name": "Ramelteon",
        "zh_name": "雷美替胺",
        "zh_aliases": ["雷美替胺", "Rozerem", "TAK-375"],
        "commonNames": make_common_names(
            ["雷美替胺"],
            ["Rozerem", "TAK-375", "TAK375"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00980",
        "isApproved": True,
        "tolerance": {
            "full": "不作用于 GABA-A 受体，长期使用无药物耐受、戒断或反跳性失眠",
            "half": "药效当天清除",
            "zero": "24 小时内完全洗脱",
        },
        "crossTolerances": ["melatonergic"],
        "addictionPotential": "极低（非受控非依赖性失眠处方药）",
        "toxicities": [
            "联用强效 CYP1A2 抑制剂氟伏沙明时系统 AUC 暴增 100 倍以上导致重度日间昏睡（绝对禁忌）",
            "罕见血管性水肿过敏反应、催乳素轻度升高与复杂睡眠行为",
        ],
        "categories": ["melatonergic", "hypnotic", "oneirogen", "sedative"],
        "categories_zh": ["褪黑素受体激动剂", "镇静催眠药", "催梦剂", "镇静剂"],
        "summary": "高选择性褪黑素 MT1/MT2 受体激动剂（Rozerem），通过调节视交叉上核昼夜节律缩短入睡潜伏期而无 GABA-A 成瘾风险；尽管口服首过极剧烈导致母体绝对生物利用度仅 1.8%，但其 CYP1A2 主导生成的活性代谢物 M-II 暴露量高达母体的 20 - 100 倍。",
        "interactions": {
            "dangerous": [
                "氟伏沙明 (Fluvoxamine，强效 CYP1A2 抑制剂，可使雷美替胺 AUC 激增逾 100 倍且 Cmax 增约 70 倍，FDA 明确列为绝对禁忌！)",
            ],
            "unsafe": [
                "其他 CYP1A2 抑制剂 (如环丙沙星、咖啡因高负荷、氟喹诺酮类) 或中效 CYP2C9/CYP3A4 抑制剂 (氟康唑/酮康唑显著提升 AUC)",
                "强效 CYP 诱导剂 (利福平使母体与 M-II 暴露骤降约 80%)",
                "酒精 (共同损害精神运动警觉)",
            ],
            "uncertain": [
                "多奈哌齐或多塞平 (轻至中度增加雷美替胺暴露量)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 4.0,
                    "commonMin": 8.0,
                    "strongMin": 16.0,
                    "heavyMin": 24.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 45.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 45.0, "units": "minutes"},
                    "peak": {"min": 45.0, "max": 90.0, "units": "minutes"},
                    "offset": {"min": 2.0, "max": 4.0, "units": "hours"},
                    "total": {"min": 4.0, "max": 6.0, "units": "hours"},
                },
                "bioavailability": {"min": 1.5, "max": 2.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="Rozerem | 武田制药 (Takeda)",
            half_life="母体仅 1.0 - 2.6 小时 (典型 1.8h)；主力活性代谢物 M-II 半衰期 2.0 - 5.0 小时 (典型 3.5h)",
            half_life_hours=1.8,
            t_max="0.5 - 1.5 小时 (典型 0.75h；高脂餐延后达峰并降低 Cmax ~22%)",
            t_max_hours=0.75,
            bioavailability="口服吸收 ≥ 84%，但因剧烈首过氧化绝对生物利用度仅约 1.8%",
            cyp_enzymes=[
                "CYP1A2 (绝对主导代谢底物 / Substrate；严禁联用氟伏沙明)",
                "CYP2C19 (次要代谢底物 / Substrate)",
                "CYP2C9 (次要代谢底物 / Substrate)",
                "CYP3A4 (次要代谢底物 / Substrate)",
            ],
            clearance="主要经肝 CYP1A2 快速羟化转化为循环主力代谢物 M-II 及其他 3 种次要产物（M-I/M-III/M-IV）；约 84% 经尿液排出",
            indications=[
                "以入睡困难为主的成人失眠症及昼夜节律睡眠障碍",
            ],
            contraindications=[
                "正在使用氟伏沙明（Fluvoxamine）的患者（绝对禁忌）",
                "有血管性水肿过敏史或重度肝功能损害者",
            ],
            pk_role="ACTIVE_METABOLITE",
            effective_half_life_hours=3.5,
            washout_hours=24.0,
            washout_note="母体半衰期不到 2 小时且绝对利用度不足 2%，真正撑起整夜持续生物暴露的是循环浓高 20 - 100 倍、半衰期约 3.5 小时的活性代谢物 M-II；常规 24 小时内完全清零。",
            mechanism_summary="高选择性 MT1/MT2 受体激动剂：母体瞬时强效激活视交叉上核受体开关，活性代谢物 M-II 以数十倍血浆稳态暴露协同维持诱导节律稳定。",
            active_metabolites=[
                met(
                    "M-II (II 型羟基活性代谢物)",
                    "母体经 CYP1A2 主导侧链羟基化生成（核心循环活性实体）",
                    "2.0 - 5.0 小时 (典型 3.5h)",
                    3.5,
                    1.0,
                    30.0,
                    "全身 AUC 暴露量高达母体的 20 - 100 倍（占循环系统药物活性主体）",
                    "对 MT1/MT2 亲和力虽仅为母体约 1/10 - 1/5（功能效价低 17 - 25 倍），但凭借超高循环暴露量显著贡献整体助眠疗效；与母体均受 CYP1A2 严密调控",
                )
            ],
            receptor_targets=[
                target("MT1 褪黑素受体", "超高亲和力完全激动剂 (Ki ≈ 0.014 nM / 14 pM，抑制视交叉上核夜间神经元觉醒放电诱导睡眠)", "high", 0.99, "双相共同主导"),
                target("MT2 褪黑素受体", "超高亲和力激动剂 (Ki ≈ 0.112 nM / 112 pM，相位调控生理昼夜节律)", "high", 0.92, "双相共同主导"),
                target("MT3 / 5-HT / GABA-A 受体", "极低或无显著结合（选择性高于 MT3 千倍以上，无宿醉及成瘾机制）", "low", 0.10, "母体主导"),
            ],
        ),
    },
    {
        "name": "Milnacipran",
        "zh_name": "米那普仑",
        "zh_aliases": ["米那普仑", "盐酸米那普仑", "思美", "Savella", "Ixel"],
        "commonNames": make_common_names(
            ["米那普仑", "盐酸米那普仑", "思美"],
            ["Savella", "Ixel", "Dalcipran", "F2207"],
        ),
        "url": "https://go.drugbank.com/drugs/DB04896",
        "isApproved": True,
        "tolerance": {
            "full": "抗纤维肌痛及抗抑郁疗效需连续服药 2 - 4 周稳定显现",
            "half": "需逐步减停避免类似 SNRI 的感觉异常/电击感撤药综合征",
            "zero": "停药后约 2 - 3 天母体洗脱，但需 14 天 MAOI 转换安全窗",
        },
        "crossTolerances": ["snri", "serotonin"],
        "addictionPotential": "无滥用成瘾性，但骤停易发生显著 SNRI 撤药不适与反跳疼痛",
        "toxicities": [
            "去甲肾上腺素能偏好性极易引发持续性血压升高、心率加快（> 100 bpm）及排尿困难",
            "与 MAOI 或强血清素能药物合用存在致命血清素综合征风险",
            "中重度肾功能不全患者若未减量极易发生严重蓄积毒性",
        ],
        "categories": ["antidepressant", "snri", "analgesic"],
        "categories_zh": ["抗抑郁药", "SNRI", "镇痛药"],
        "summary": "独特的均衡/偏去甲肾上腺素能 SNRI 药物（FDA 批准用于纤维肌痛镇痛 Savella，欧洲/日本/中国批准用于抑郁症 Ixel）：功能性 NET 抑制强度约为 SERT 的 3 倍；几乎不经 CYP450 酶代谢，55% 以原形经肾排泄，因而极少受 CYP 酶抑/诱导剂交互干扰。",
        "interactions": {
            "dangerous": [
                "单胺氧化酶抑制剂 (MAOIs / 利奈唑胺 / 亚甲蓝，绝对禁忌！严防致命性血清素综合征与高血压危象，前后需间隔 ≥ 14 天)",
                "肾上腺素 / 去甲肾上腺素拟交感静脉制剂 (阵发性恶性高血压与致命心律失常)",
            ],
            "unsafe": [
                "曲马多 / 哌替啶 / 美沙酮 / 右美沙芬 / MDMA / SSRI / SNRI / 曲坦类 (显著叠加中枢 5-HT 综合征与躁狂高血压风险)",
                "酒精 (禁与慢性高量酒精同服，潜在肝损伤与神经精神恶化风险；且可加快部分剂型释放)",
                "抗血小板药/NSAID/华法林 (SERT 阻断降低血小板 5-HT 储存，增加消化道出血风险)",
            ],
            "uncertain": [
                "地高辛 (既往报道合用或可轻微增加直立性低血压与心动过速)",
                "可乐定 (可部分削弱中枢降压效应)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 25.0,
                    "commonMin": 100.0,
                    "strongMin": 150.0,
                    "heavyMin": 200.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "comeup": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "peak": {"min": 2.0, "max": 4.0, "units": "hours"},
                    "offset": {"min": 4.0, "max": 8.0, "units": "hours"},
                    "total": {"min": 8.0, "max": 12.0, "units": "hours"},
                },
                "bioavailability": {"min": 85.0, "max": 90.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="Savella / Ixel | 艾伯维 (AbbVie) / 皮尔法伯 (Pierre Fabre)",
            half_life="6.0 - 8.0 小时 (d-对映体约 8 - 10h；l-对映体约 4 - 6h；中重度肾损明显延长)",
            half_life_hours=7.0,
            t_max="2.0 - 4.0 小时 (口服不受进食显著影响)",
            t_max_hours=2.5,
            bioavailability="口服 85% - 90% (血浆蛋白结合率极低 ~13%)",
            cyp_enzymes=[
                "极少经肝微粒体氧化酶代谢 (主要经葡萄糖醛酸化及约 55% 原形经肾排泄，几乎无 CYP 酶抑/诱作用)",
            ],
            clearance="约 55% 以原型经肾小管排泄（部分经活性阳离子分泌），约 19% 以氨基甲酰葡萄糖醛酸结合物经尿排出；半衰期受肌酐清除率高度影响",
            indications=[
                "成人纤维肌痛综合征（FDA 核心适应症：目标 100 mg/日分两次）",
                "成人重度抑郁症及神经病理性疼痛辅助镇痛（多国获批）",
            ],
            contraindications=[
                "正在或停用 MAOI 不足 14 天者（绝对禁忌）",
                "未控制的闭角型青光眼、重度肾衰竭未调量患者",
            ],
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=7.0,
            washout_hours=48.0,
            washout_note="母体消旋半衰期约 7 小时（d-米那普仑约 8 - 10h），常规约 48 小时基本从血浆清零；但出于 SNRI 单胺突触重塑安全性要求，改换不可逆 MAOI 仍必须严格留足 14 天洗脱期。",
            mechanism_summary="高生物利用度、低蛋白结合率且不依赖 CYP 清除的偏去甲肾上腺素能 SNRI：母体原形（尤以 d-对映体为主力）直接阻断 NET 与 SERT 增强下行痛觉抑制通路。",
            active_metabolites=[],
            receptor_targets=[
                target("NET (去甲肾上腺素转运体)", "强效再摄取抑制 (Ki ≈ 20 - 31 nM；细胞功能性 NE 阻断力约为 5-HT 的 3 倍，解释显著镇痛与升压倾向)", "high", 0.96, "母体主导"),
                target("SERT (5-羟色胺转运体)", "强效再摄取抑制 (Ki ≈ 8.5 - 12 nM，抑制 5-HT 回收)", "high", 0.93, "母体主导"),
                target("NMDA / M1 / H1 / α1 受体", "基本无显著亲和力（非典型轻度 NMDA 通道微弱阻断仅见于极高浓度，口干嗜睡少但尿潴留源自 NE）", "low", 0.15, "母体主导"),
            ],
        ),
    },
    {
        "name": "Cetirizine",
        "zh_name": "西替利嗪",
        "zh_aliases": ["西替利嗪", "仙特明", "盐酸西替利嗪", "Zyrtec", "Reactine"],
        "commonNames": make_common_names(
            ["西替利嗪", "仙特明", "盐酸西替利嗪"],
            ["Zyrtec", "Reactine"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00341",
        "isApproved": True,
        "tolerance": {
            "full": "抗外周 H1 过敏疗效长期稳定，部分敏感人群骤停偶现一过性反跳性皮肤瘙痒",
            "half": "停药后约 1 - 2 天明显衰退",
            "zero": "停药后约 2 天完全洗脱",
        },
        "crossTolerances": ["antihistamine"],
        "addictionPotential": "无精神成瘾性或滥用潜力",
        "toxicities": [
            "虽属第二代抗组胺药但仍具轻微血脑屏障穿透性（约 10% - 15% 人群出现轻中度嗜睡）",
            "超量合用酒精或中枢镇静剂会加重反应迟缓及困倦",
        ],
        "categories": ["antihistamine", "common"],
        "categories_zh": ["抗组胺药", "常见物质"],
        "summary": "高选择性第二代外周 H1 组胺受体反向激动剂/拮抗剂（仙特明/Zyrtec），同时本身也是第一代抗组胺药羟嗪（Vistaril）在人体内的主要活性代谢物；极少经肝脏代谢，主要以原形经肾脏排泄。",
        "interactions": {
            "dangerous": [],
            "unsafe": [
                "酒精 (乙醇) 及苯二氮䓬/阿片/第一代抗组胺药等中枢神经抑制剂 (虽然西替利嗪穿透血脑屏障较少，仍可能叠加轻中度嗜睡与警觉度下降)",
            ],
            "uncertain": [
                "茶碱 (Theophylline 400 mg/日多剂联用使西替利嗪清除率小幅降低约 16%，常规剂量临床意义有限)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 5.0,
                    "commonMin": 10.0,
                    "strongMin": 20.0,
                    "heavyMin": 40.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 60.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "offset": {"min": 8.0, "max": 16.0, "units": "hours"},
                    "total": {"min": 18.0, "max": 24.0, "units": "hours"},
                },
            }
        ],
        "pharmacokinetics": pk(
            brand_name="仙特明 (Zyrtec) | 强生 (Kenvue) / 优时比 (UCB)",
            half_life="成人平均 8.3 小时 (约 7.0 - 10.0h；肾功能不全明显延长至 19 - 21h)",
            half_life_hours=8.3,
            t_max="1.0 小时 (范围 0.5 - 1.5h；高脂餐延迟 Tmax 至 ~1.7h 但总暴露 AUC 相当)",
            t_max_hours=1.0,
            bioavailability="口服吸收迅速且充分 (FDA 说明书未标定绝对静注对比率；尿液总排泄回收约 70%，其中约 50% 为未变化原形)",
            cyp_enzymes=[
                "极少经肝脏氧化代谢 (仅极少量经氧化 O-脱烷基转化；约 50% 原形经尿排出，无已知显著 CYP 交互)",
            ],
            clearance="主要通过肾小球滤过与肾小管主动分泌排泄（约 70% 尿液、10% 粪便；血浆蛋白结合率约 93%）",
            indications=[
                "季节性及常年性过敏性鼻炎",
                "慢性自发性荨麻疹及过敏性皮肤瘙痒",
            ],
            contraindications=[
                "对西替利嗪、左西替利嗪或羟嗪严重过敏者",
                "终末期肾病（肌酐清除率 < 10 mL/min）患者未调整或禁用",
            ],
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=8.3,
            washout_hours=48.0,
            washout_note="半衰期约 8.3 小时、主要原形肾排且无活性下游代谢物，肾功能正常成人约 48 小时即几乎完全洗脱；需注意肾损患者清除大幅放缓。",
            mechanism_summary="具有极性羧酸侧链（两性离子）的第二代 H1 受体选择性反向激动剂，因难以跨过血脑屏障而大幅减轻中枢抗胆碱及谵妄副作用。",
            active_metabolites=[],
            receptor_targets=[
                target("H1 组胺受体", "高选择性反向激动剂/拮抗剂 (Ki ≈ 6 nM，稳定外周肥大细胞及组胺能过敏反应)", "high", 0.96, "母体主导"),
                target("M1 - M5 毒蕈碱胆碱受体", "几乎无抗胆碱活性（区别于羟嗪母体与苯海拉明，极少致口干谵妄）", "low", 0.05, "母体主导"),
            ],
        ),
    },
    {
        "name": "Naltrexone",
        "zh_name": "纳曲酮",
        "zh_aliases": ["纳曲酮", "盐酸纳曲酮", "ReVia", "Vivitrol", "Depade"],
        "commonNames": make_common_names(
            ["纳曲酮", "盐酸纳曲酮"],
            ["ReVia", "Vivitrol", "Depade", "LDN"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00704",
        "isApproved": True,
        "tolerance": {
            "full": "口服常规每日 50 mg 维持可连续阻断 μ-阿片受体达 24 小时以上，无药效耐受",
            "half": "末次口服停药后约 24 - 48 小时阻断消退",
            "zero": "口服停药后约 72 小时完全恢复阿片敏感度（极高过量警示！）",
        },
        "crossTolerances": ["opioid-antagonist"],
        "addictionPotential": "无成瘾性与欣快感（用于阿片与酒精戒断维持）",
        "toxicities": [
            "未充分阿片脱毒患者给药可诱发剧烈、甚至需抢救的急性促发戒断综合征 (Precipitated Withdrawal)",
            "停药或药效消退期阿片耐受已骤降，切忌用大剂量阿片冲破受体阻断——极易致死性呼吸抑制过量！",
            "高剂量下偶伴剂量相关性可逆转氨酶升高与肝脏损伤",
        ],
        "categories": ["antidote", "opioid", "common"],
        "categories_zh": ["解毒剂/拮抗剂", "阿片类", "常见物质"],
        "summary": "长效口服/长效肌注竞争性阿片受体完全拮抗剂（主要阻断 μ/κ 受体），广泛用于酒精依赖与阿片复吸预防，亦见于超低剂量（LDN 1.5 - 4.5 mg）免疫/疼痛超说明书探索。母体半衰期仅约 4 小时，但其非 CYP 还原生成的主力活性代谢物 6β-纳曲醇半衰期达 13 小时且稳态暴露量高达母体 10 - 30 倍。",
        "interactions": {
            "dangerous": [
                "一切 μ-阿片受体激动剂 (吗啡、海洛因、芬太尼、羟考酮、美沙酮、丁丙诺啡等)：体内存在阿片类药物时服用纳曲酮会立即引爆剧烈促发戒断；反之企图加大阿片剂量强行突破纳曲酮阻断则面临极端致命过量风险！",
            ],
            "unsafe": [
                "潜在肝毒性药物 (如大剂量对乙酰氨基酚、丙戊酸钠，叠加剂量相关性肝细胞损伤风险)",
                "甲硫哒嗪 (个案报告合用引起嗜睡明显加重)",
            ],
            "uncertain": [
                "优妥/阿坎酸 (临床可协同用于戒酒；阿坎酸合用时其自身 AUC 略有上升)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 4.5,
                    "commonMin": 25.0,
                    "strongMin": 50.0,
                    "heavyMin": 100.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 45.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "offset": {"min": 12.0, "max": 24.0, "units": "hours"},
                    "total": {"min": 24.0, "max": 36.0, "units": "hours"},
                },
                "bioavailability": {"min": 5.0, "max": 40.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="ReVia / Vivitrol | 艾尔健 (Allergan) / Alkermes",
            half_life="母体约 4.0 小时；主力长效活性代谢物 6β-纳曲醇达 13.0 小时 (肌注微球 Vivitrol 末端表观 5 - 10 天)",
            half_life_hours=4.0,
            t_max="≤ 1.0 小时 (口服母体与 6β-纳曲醇均在 1 小时内达峰)",
            t_max_hours=1.0,
            bioavailability="口服吸收达 96%，但经显著非 CYP 肝脏首过还原，绝对生物利用度仅 5% - 40%",
            cyp_enzymes=[
                "主要不经肝微粒体氧化代谢 (经胞浆二氢二醇脱氢酶 AKR1C 还原为活性代谢物 6β-纳曲醇并葡糖醛酸化清除)",
            ],
            clearance="约 98% 在肝脏转化为 6β-纳曲醇及葡萄糖醛酸结合物，主要经肾脏（53% - 79%）排泄",
            indications=[
                "阿片类药物依赖脱毒后预防复吸",
                "酒精使用障碍 (AUD) 降低饮酒渴求",
                "低剂量纳曲酮 (LDN) 相关研究与纳曲酮/安非他酮复方减重",
            ],
            contraindications=[
                "正在接受阿片镇痛或处于急性阿片戒断/依赖状态者（绝对禁忌，须确认至少 7 - 14 天阿片全洗脱）",
                "急性肝炎或肝功能衰竭患者",
            ],
            pk_role="ACTIVE_METABOLITE",
            effective_half_life_hours=13.0,
            washout_hours=72.0,
            washout_note="口服母体仅 4 小时半衰期，但 6β-纳曲醇半衰期达 13 小时且稳态暴露可达母体的 10 - 30 倍！口服停药后须警惕：受体完全解锁约需 48 - 72 小时（长效肌注需数周），解锁前后阿片耐受处于极低谷，复吸旧剂量极易暴毙。",
            mechanism_summary="高亲和力 μ/κ-阿片受体竞争性完全拮抗剂：母体瞬时占据受体后迅速首过生成长半衰期、高暴露的 6β-纳曲醇，共同建立 24 小时以上阿片屏障。",
            active_metabolites=[
                met(
                    "6β-Naltrexol (6β-纳曲醇)",
                    "经肝细胞浆二氢二醇脱氢酶 (AKR1C) 立体选择性还原生成",
                    "约 13.0 小时",
                    13.0,
                    1.0,
                    15.0,
                    "稳态血浆 AUC 暴露量可达母体的 10 - 30 倍",
                    "对 μ-阿片受体保有纳摩尔级强拮抗活性（Ki 约 0.5 - 1.0 nM），凭借 10 - 30 倍系统暴露与 13h 半衰期承担全天持久阻断主导作用",
                )
            ],
            receptor_targets=[
                target("μ-阿片受体 (MOR)", "超强效竞争性完全拮抗剂 (母体 Ki ≈ 0.26 nM；彻底阻断内啡肽及外源性阿片激动)", "high", 0.99, "双相共同主导"),
                target("κ-阿片受体 (KOR)", "强效竞争性拮抗剂 (Ki ≈ 5.15 nM)", "high", 0.85, "双相共同主导"),
                target("δ-阿片受体 (DOR)", "中等亲和力拮抗剂 (Ki ≈ 117 nM)", "moderate", 0.65, "双相共同主导"),
            ],
        ),
    },
    {
        "name": "Pramipexole",
        "zh_name": "普拉克索",
        "zh_aliases": ["普拉克索", "森福罗", "盐酸普拉克索", "Mirapex", "Sifrol"],
        "commonNames": make_common_names(
            ["普拉克索", "森福罗", "盐酸普拉克索"],
            ["Mirapex", "Mirapex ER", "Sifrol"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00413",
        "isApproved": True,
        "tolerance": {
            "full": "需 2 - 4 周按阶梯滴定耐受初期恶心与体位性低血压",
            "half": "需逐步减停避免高热、肌强直等恶性综合征样多巴胺撤药反应",
            "zero": "停药后约 2 天药物消除，但突触敏感度需更久恢复",
        },
        "crossTolerances": ["dopamine"],
        "addictionPotential": "无传统欣快成瘾性，但强烈 D3 激活可诱发病理性赌博、购物及性欲亢进等冲动控制障碍 (ICD)",
        "toxicities": [
            "无预警日间“睡眠发作”(Sleep Attacks)，驾驶极具危险性",
            "显著冲动控制障碍 (ICD) 与视幻觉（老年患者高发）",
            "不安腿综合征 (RLS) 长期用药可出现症状恶化与早发（Augmentation）",
            "骤停可触发抗精神病药恶性综合征样高热强直急症",
        ],
        "categories": ["antiparkinsonian", "common"],
        "categories_zh": ["抗帕金森病药", "常见物质"],
        "summary": "高选择性非麦角类 D3 偏好型 D2/D3/D4 多巴胺受体完全激动剂（森福罗/Mirapex），用于帕金森病与中重度不宁腿综合征；生物利用度 > 90%，几乎不经 CYP 代谢，> 90% 以原形经肾小管 OCT2 阳离子转运分泌排泄。",
        "interactions": {
            "dangerous": [
                "典型及非典型多巴胺 D2 拮抗型抗精神病药 / 甲氧氯普胺 (直接受体拮抗使疗效抵消或诱发症状急剧恶化)",
            ],
            "unsafe": [
                "肾小管阳离子转运 (OCT2) 竞争抑制剂 (如西咪替丁可使普拉克索 AUC 升高 50%、半衰期延长 40%；雷尼替丁/奎尼丁/地尔硫卓/维拉帕米亦抑制清除)",
                "酒精及苯二氮䓬/阿片等中枢镇静剂 (剧烈放大日间嗜睡与突发睡眠发作事故风险)",
                "降压药及左旋多巴 (协同加重体位性低血压及左旋多巴异动症)",
            ],
            "uncertain": [
                "金刚烷胺 (同经肾阳离子分泌，理论上存在相互竞争排泄的可能)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 0.125,
                    "commonMin": 0.5,
                    "strongMin": 1.5,
                    "heavyMin": 4.5,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "comeup": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "peak": {"min": 2.0, "max": 3.0, "units": "hours"},
                    "offset": {"min": 4.0, "max": 8.0, "units": "hours"},
                    "total": {"min": 8.0, "max": 12.0, "units": "hours"},
                },
                "bioavailability": {"min": 90.0, "max": 98.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="森福罗 (Mirapex / Sifrol) | 勃林格殷格翰 (Boehringer Ingelheim)",
            half_life="年轻健康成人约 8.0 小时；老年人群延长至约 12.0 小时",
            half_life_hours=8.5,
            t_max="约 2.0 小时 (空腹常释；高脂进食延迟 Tmax 约 1 小时但 AUC 不变)",
            t_max_hours=2.0,
            bioavailability="口服 > 90% (血浆蛋白结合率极低 < 20%)",
            cyp_enzymes=[
                "基本不经肝微粒体氧化代谢 (< 10% 代谢；> 90% 原形经肾小管 OCT2 阳离子通道主动分泌排泄)",
            ],
            clearance="约 90% 以原形经肾脏清除（主要依赖肾小管有机阳离子转运体 OCT2 分泌，清除率与肌酐清除率高度线性相关）",
            indications=[
                "特发性帕金森病（单药或联用左旋多巴）",
                "中重度原发性不安腿综合征 (RLS)",
            ],
            contraindications=[
                "对普拉克索严重过敏者；严重肾衰竭患者无缓释适用且常规须大幅减频减量",
            ],
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=8.5,
            washout_hours=48.0,
            washout_note="母体原形半衰期在青年约 8 小时、老年约 12 小时，无活性代谢物，常规约 48 - 60 小时清除；但帕金森病患者禁止突然停药，应历时至少 1 周逐级递减，防范多巴胺激动剂撤药综合征 (DAWS) 与高热强直危机。",
            mechanism_summary="高选择性 D3 偏好型 D2 家族完全激动剂，完全绕开肝脏 CYP 代谢由肾小管原形主动排泄，母体原形主导突触前后多巴胺调节。",
            active_metabolites=[],
            receptor_targets=[
                target("D3 多巴胺受体", "超高亲和力完全激动剂 (Ki ≈ 0.5 nM，驱动奖赏边缘通路，带来抗焦虑/抗快感缺失潜力但伴冲动控制障碍风险)", "high", 0.99, "母体主导"),
                target("D2 多巴胺受体 (D2L/D2S)", "强效完全激动剂 (Ki ≈ 2.2 - 3.9 nM，改善肌强直、少动及突发睡眠发作均与 D2/D3 自受体相关)", "high", 0.92, "母体主导"),
                target("D4 多巴胺受体", "强效完全激动剂 (Ki ≈ 5.1 nM)", "high", 0.85, "母体主导"),
                target("α2 肾上腺素能受体", "较弱激动/结合（偶发体位性低血压）", "low", 0.35, "母体主导"),
            ],
        ),
    },
    {
        "name": "Levodopa",
        "zh_name": "左旋多巴",
        "zh_aliases": ["左旋多巴", "美多芭", "息宁", "卡左双多巴", "L-DOPA", "Sinemet", "Madopar"],
        "commonNames": make_common_names(
            ["左旋多巴", "美多芭", "息宁", "卡左双多巴"],
            ["L-DOPA", "Sinemet", "Madopar", "Rytary"],
        ),
        "url": "https://go.drugbank.com/drugs/DB01235",
        "isApproved": True,
        "tolerance": {
            "full": "帕金森病长期治疗 3 - 5 年常出现“剂末现象”与“开关波动”及峰值异动症",
            "half": "单剂血浆效应在 3 - 5 小时后迅速衰落",
            "zero": "切忌突然全停，可触发帕金森高热综合征 (Parkinsonism-hyperpyrexia)",
        },
        "crossTolerances": ["dopamine"],
        "addictionPotential": "部分帕金森患者出现多巴胺失调综合征 (DDS)，强迫性超量追服诱发狂躁与刻板行为",
        "toxicities": [
            "峰浓度期舞蹈样异动症 (Dyskinesias) 及开关现象",
            "外周转化为多巴胺引发剧烈恶心呕吐、心律失常与直立性低血压（必须复方联用卡比多巴/苄丝肼阻断外周脱羧）",
            "中枢高负载诱导视幻觉、偏执妄想及冲动控制障碍",
        ],
        "categories": ["antiparkinsonian", "common"],
        "categories_zh": ["抗帕金森病药", "常见物质"],
        "summary": "帕金森病治疗金标准多巴胺直接前体氨基酸药物（临床极少单用，通常与外周脱羧酶抑制剂卡比多巴 Sinemet 或苄丝肼 Madopar 复方给药）：自身不直接激活受体，经主动跨血脑屏障后由中枢 AADC/DDC 脱羧转化为内源性神经递质多巴胺。复方联用时半衰期约 1.5 小时，受膳食蛋白氨基酸 LAT1 竞争转运显著。",
        "interactions": {
            "dangerous": [
                "非选择性不可逆单胺氧化酶抑制剂 (如苯乙肼、反苯环丙胺，绝对禁忌！阻止外周及脑内多巴胺/去甲肾上腺素降解引爆致命高血压危象，须间隔 ≥ 14 天)",
            ],
            "unsafe": [
                "高剂量维生素 B6 / 吡哆醇 (仅当使用纯左旋多巴未配卡比多巴时急剧加速外周 DDC 降解；卡比多巴复方制剂则基本不受常规 B6 破坏)",
                "铁剂 (在胃肠道螯合并显著降低左旋多巴及卡比多巴吸收)",
                "高蛋白正餐 (膳食大中性氨基酸与左旋多巴竞争肠道及血脑屏障 LAT1 转运蛋白，强烈建议空腹服药)",
                "典型/非典型抗精神病药 (D2 拮抗直接削弱抗帕金森疗效)",
            ],
            "uncertain": [
                "选择性 MAO-B 抑制剂 (司来吉兰/雷沙吉兰：临床可谨慎协同增效并延长“开期”，但易增加异动症)",
                "拟交感兴奋剂或三环类抗抑郁药 (叠加血压波动与心律失常风险)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 50.0,
                    "commonMin": 100.0,
                    "strongMin": 250.0,
                    "heavyMin": 500.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 45.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "offset": {"min": 2.0, "max": 4.0, "units": "hours"},
                    "total": {"min": 4.0, "max": 6.0, "units": "hours"},
                },
            }
        ],
        "pharmacokinetics": pk(
            brand_name="息宁 / 美多芭 (Sinemet / Madopar / Rytary)",
            half_life="联用卡比多巴 (外周 DDC 抑制剂) 时约 1.5 小时 (单用无抑制剂时仅 ~50 分钟)；缓释胶囊分段释放表观延长",
            half_life_hours=1.5,
            t_max="0.5 - 1.0 小时 (空腹常释复方片；高蛋白餐饮竞争转运延后 1 - 2h 以上)",
            t_max_hours=0.75,
            bioavailability="强烈依赖是否联用外周脱羧酶抑制剂（联用 ≥ 75 mg/日卡比多巴可减少约 75% 左旋多巴外周损失）；受小肠排空与蛋白饮食剧烈波动",
            cyp_enzymes=[
                "非细胞色素酶代谢途径 (经芳香族 L-氨基酸脱羧酶 AADC/DDC 脱羧生成活性多巴胺，及经 COMT 甲基化为 3-O-甲基多巴)",
            ],
            clearance="近端小肠 LAT1 主动吸收；外周被 COMT（与恩他卡朋等 COMT 抑制剂合用可延长暴露）及残留 DDC 快速清除，约 70% - 80% 最终以 DOPAC/HVA 代谢产物经尿排泄",
            indications=[
                "特发性帕金森病及症状性帕金森综合征（一氧化碳/锰中毒后遗等）",
                "多巴反应性肌张力障碍",
            ],
            contraindications=[
                "正在服用或停药不足 14 天的非选择性 MAO-A/B 抑制剂（绝对禁忌）",
                "狭角型青光眼、可疑恶性黑色素瘤病史（左旋多巴是黑色素合成前体）",
            ],
            pk_role="PRODRUG",
            effective_half_life_hours=1.5,
            washout_hours=12.0,
            washout_note="左旋多巴在血浆中半衰期仅约 1.5 小时（即便配伍卡比多巴），约 12 小时内已几乎完全离开循环，直接对应帕金森中晚期每日必须多次给药的痛点；注意改用非选择性 MAOI 前仍必须保留 ≥ 14 天临床禁忌期。",
            mechanism_summary="多巴胺前体氨基酸前药（自身无直接受体活性）：借 LAT1 主动跨越血脑屏障后在中枢直接脱羧补足纹状体多巴胺池。",
            active_metabolites=[
                met(
                    "Dopamine (内源性中枢多巴胺)",
                    "经大脑黑质纹状体残余神经元/胶质细胞芳香族 L-氨基酸脱羧酶 (AADC/DDC) 催化脱羧生成",
                    "突触及血浆游离极短 (外周半衰期 ~2 分钟，脑内受囊泡储存与 MAO-B/COMT 清除主导 2 - 5h 临床“开期”)",
                    0.5,
                    1.0,
                    1.0,
                    "全部中枢运动改善与异动症药效的直接终极递质",
                    "全面激动纹状体 D1 - D5 多巴胺受体逆转帕金森运动迟缓；伴随中枢囊泡缓冲能力丧失产生峰谷波动",
                )
            ],
            receptor_targets=[
                target("D1 / D2 / D3 / D4 / D5 多巴胺受体", "通过脑内转化产物多巴胺全面间接激动（母体自身不结合受体）", "high", 0.96, "代谢物主导"),
                target("LAT1 大中性氨基酸转运体", "肠道黏膜与血脑屏障主动跨膜转运底物（受高蛋白食物直接竞争抑制）", "moderate", 0.78, "母体主导"),
            ],
        ),
    },
    {
        "name": "Dexmedetomidine",
        "zh_name": "右美托咪定",
        "zh_aliases": ["右美托咪定", "盐酸右美托咪定", "艾贝宁", "Precedex", "Igalmi"],
        "commonNames": make_common_names(
            ["右美托咪定", "盐酸右美托咪定", "艾贝宁"],
            ["Precedex", "Igalmi", "Dexdor"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00633",
        "isApproved": True,
        "summary": "高选择性 α2-肾上腺素能受体激动剂（α2:α1 选择比约 1620:1），临床广泛用于 ICU 镇静、围术期辅助镇痛及舌下膜剂（Igalmi）急性激越控制；独特诱导接近自然非快速眼动睡眠的“可唤醒镇静”，呼吸抑制极轻，但可致显著心动过缓与双相血压变化（静推过快先一过性升压，继而交感抑制性低血压）。",
        "effectsSummary": "剂量依赖性镇静催眠、抗焦虑、交感神经张力抑制与阿片类节约型镇痛效应，患者在刺激下易被唤醒且保持自主呼吸。",
        "dosageRemark": "静脉给药通常需以 1 µg/kg 负荷（10 分钟以上缓慢输注，严禁快速推注）继以 0.2 - 0.7 µg/kg/h 维持；舌下膜剂（Igalmi）成人单次 120 - 180 µg（不得吞咽或咀嚼）。",
        "generalRisks": "心动过缓、窦性停搏（高迷走张力者）、低血压或初期一过性高血压、口干与恶心。",
        "longtermRisks": "ICU 连续输注超过 24 小时后骤停可偶发反跳性心动过速、高血压及激越，建议渐进减量。",
        "saferUse": [
            "静脉泵注严禁直接快速静脉推注，避免触发外周 α2B 受体介导的严重反射性心动过缓与高血压危象",
            "舌下给药（Igalmi）后至少 15 分钟内勿进食饮水，并在给药后监测卧位与立位血压至少 2 小时",
        ],
        "tolerance": {
            "full": "连续静脉输注超过 48 - 72 小时可出现部分镇静耐受与停药后交感反跳",
            "half": "24 hours",
            "zero": "48 hours",
        },
        "crossTolerances": ["clonidine", "tizanidine"],
        "addictionPotential": "无经典阿片/GABA 类欣快成瘾潜力，但超长输注骤停可诱发短暂撤药性交感亢进",
        "toxicities": [
            "过量可导致严重窦性心动过缓、房室传导阻滞甚至窦性停搏（必要时阿托品拮抗）",
            "明显低血压或外周血管收缩引发的高血压危象",
        ],
        "categories": ["sedative", "anxiolytic", "analgesic", "antihypertensive"],
        "categories_zh": ["镇静剂", "抗焦虑药", "镇痛药", "降压药"],
        "interactions": {
            "dangerous": [
                "Propofol / Midazolam / Opioids (在无气道监护环境下叠加深度中枢镇静与严重低血压、心动过缓)",
                "β-受体阻滞剂 / Digoxin / 非二氢吡啶类钙拮抗剂 (剧烈叠加迷走神经兴奋，显著升高高度房室阻滞及窦性停搏风险)",
            ],
            "unsafe": [
                "Alcohol (酒精叠加中枢抑制与体位性低血压)",
                "Clonidine / Tizanidine (同靶点 α2 激动剂严重叠加低血压与心动过缓)",
            ],
            "uncertain": [
                "Ketamine / Esketamine (临床常精准联用：右美托咪定可显著减轻氯胺酮拟交感升压与苏醒期精神症状)",
            ],
        },
        "roas": [
            {
                "name": "intravenous",
                "dose": {
                    "units": "µg",
                    "lightMin": 20.0,
                    "commonMin": 40.0,
                    "strongMin": 80.0,
                    "heavyMin": 150.0,
                },
                "duration": {
                    "onset": {"min": 5.0, "max": 10.0, "units": "minutes"},
                    "comeup": {"min": 10.0, "max": 20.0, "units": "minutes"},
                    "peak": {"min": 20.0, "max": 60.0, "units": "minutes"},
                    "offset": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "total": {"min": 2.0, "max": 4.0, "units": "hours"},
                },
                "bioavailability": {"min": 100.0, "max": 100.0},
            },
            {
                "name": "sublingual",
                "dose": {
                    "units": "µg",
                    "lightMin": 60.0,
                    "commonMin": 120.0,
                    "strongMin": 180.0,
                    "heavyMin": 240.0,
                },
                "duration": {
                    "onset": {"min": 15.0, "max": 30.0, "units": "minutes"},
                    "comeup": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "peak": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "offset": {"min": 2.0, "max": 4.0, "units": "hours"},
                    "total": {"min": 3.0, "max": 6.0, "units": "hours"},
                },
                "bioavailability": {"min": 72.0, "max": 82.0},
            },
        ],
        "pharmacokinetics": pk(
            brand_name="艾贝宁 / Precedex / Igalmi",
            half_life="静脉分布半衰期约 6 分钟；终末消除半衰期约 2.0 小时（舌下膜剂 Igalmi 表观终末半衰期约 2.8 小时）",
            half_life_hours=2.0,
            t_max="静脉输注结束即达峰 (~0.25h)；舌下含服达峰约 1.5 - 2.0 小时",
            t_max_hours=0.25,
            bioavailability="静脉 100%；舌下 72%，颊黏膜 82%；口服因极高首过效应仅约 16%",
            cyp_enzymes=[
                "CYP2A6 (主要脂肪族羟化氧化代谢底物 / Substrate；辅以直接 N-葡萄糖醛酸化)",
                "CYP1A2 (极微弱次要参与 / Minor Substrate)",
            ],
            clearance="几乎完全经肝脏 UGT 直接 N-葡萄糖醛酸化（约占 41%）及 CYP2A6 介导羟化（生成无活性代谢物），95% 经尿排泄（原形 < 1%）；重度肝损清除显著减慢",
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=2.1,
            washout_hours=12.0,
            washout_note="静脉停药后约 10 - 12 小时完成血浆清除，临床镇静效应随快速重分布于 2 - 4 小时内明显消退；舌下制剂清除略延长至约 14 小时。",
            mechanism_summary="高选择性中枢蓝斑核 α2A-肾上腺素受体完全激动剂，抑制去甲肾上腺素释放并激活内源性睡眠通路，实现“可唤醒镇静”与交感抑制。",
            indications=[
                "ICU 机械通气患者及非插管围术期/操作镇静",
                "精神分裂症或双相情感障碍相关急性激越（舌下膜剂 Igalmi）",
            ],
            contraindications=[
                "未安装起搏器的重度窦房结功能障碍、II/III 度房室传导阻滞",
                "未纠正的严重低血容量或低血压休克",
            ],
            active_metabolites=[],
            receptor_targets=[
                target("α2A 肾上腺素受体", "超高选择性完全激动剂 (Ki ≈ 1.08 nM；抑制蓝斑核释放 NE 产生可唤醒镇静与镇痛)", "high", 0.98, "母体主导"),
                target("α2B / α2C 肾上腺素受体", "高亲和力激动剂 (Ki ≈ 1.6 nM；外周 α2B 激活解释快速静推一过性高血压)", "high", 0.90, "母体主导"),
                target("咪唑啉 I1 / I2 受体", "中等亲和力结合（辅助中枢降压与交感张力调制）", "moderate", 0.58, "母体主导"),
            ],
        ),
    },
    {
        "name": "Propofol",
        "zh_name": "丙泊酚",
        "zh_aliases": ["丙泊酚", "异丙酚", "得普利麻", "Diprivan"],
        "commonNames": make_common_names(
            ["丙泊酚", "异丙酚", "得普利麻"],
            ["Diprivan", "Propofol emulsion", "2,6-Diisopropylphenol"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00818",
        "isApproved": True,
        "summary": "超短效脂溶性静脉全麻与镇静诱导药（GABAA 受体正向变构调节剂与高浓度直接激动剂），静脉推注后 30 - 40 秒内迅速诱导意识丧失并因极快组织重分布而迅速苏醒；严禁非专业监护下使用，具有极窄呼吸抑制安全窗，大剂量长时输注有致命性丙泊酚输注综合征（PRIS）风险。",
        "effectsSummary": "极速入睡、遗忘、全麻或深镇静状态，伴轻度肌肉松弛与显著外周血管扩张降压；苏醒迅速且较少恶心呕吐。",
        "dosageRemark": "仅供具备气道管理与生命支持条件的医疗环境静脉推注/靶控输注（常规成人全麻诱导 1.5 - 2.5 mg/kg，即约 100 - 180 mg；内镜镇静常 0.5 - 1.5 mg/kg）。",
        "generalRisks": "呼吸暂停（Apnea）、气道梗阻、低血压、注射部位静脉烧灼痛与肌阵挛。",
        "longtermRisks": "大剂量（> 4 mg/kg/h）连续输注 > 48 小时可引发致命性丙泊酚输注综合征（PRIS：横纹肌溶解、高钾、难治性代谢性酸中毒与心衰）。",
        "saferUse": [
            "绝对禁止无呼吸机/急救气道设备下非医疗自我给药，单次轻微超量即可在数分钟内因呼吸暂停致死",
            "配伍阿片类或苯二氮䓬类时必须大幅削减丙泊酚诱导剂量（通常下调 30% - 50%）",
        ],
        "tolerance": {
            "full": "连续长时输注（> 24 - 72 小时）可出现麻醉镇静需求递增",
            "half": "12 hours",
            "zero": "48 hours",
        },
        "crossTolerances": ["depressant", "benzodiazepine"],
        "addictionPotential": "由于极快速起效与极短时程（且非医疗场景呼吸抑制死亡率极高），存在极危险的强迫性高频复吸风险",
        "toxicities": [
            "急性呼吸停止、缺氧性脑损伤与致命性低血压虚脱",
            "丙泊酚输注综合征（PRIS：高脂血症、横纹肌溶解、高乳酸酸中毒、Brugada 样心电改变与心跳骤停）",
        ],
        "categories": ["depressant", "sedative", "hypnotic", "habit-forming"],
        "categories_zh": ["中枢抑制剂", "镇静剂", "催眠药", "易成瘾"],
        "interactions": {
            "dangerous": [
                "Opioids / Fentanyl / Remifentanil / Alfentanil (极强协同抑制呼吸驱动与动脉血压，引发呼吸骤停与严重低血压)",
                "Benzodiazepines / Midazolam / Alcohol (GABAA 受体协同显著加深麻醉深度并延长呼吸暂停期)",
                "Dexmedetomidine (剧烈叠加心动过缓与低血压)",
            ],
            "unsafe": [
                "Volatile Anesthetics / Sevoflurane (叠加心肌抑制与血管扩张)",
            ],
            "uncertain": [
                "Valproate / CYP2B6 Substrates (高脂乳剂长时输注期间竞争 UGT1A9/CYP2B6 代谢并改变游离分数)",
            ],
        },
        "roas": [
            {
                "name": "intravenous",
                "dose": {
                    "units": "mg",
                    "lightMin": 30.0,
                    "commonMin": 80.0,
                    "strongMin": 150.0,
                    "heavyMin": 250.0,
                },
                "duration": {
                    "onset": {"min": 20.0, "max": 45.0, "units": "seconds"},
                    "comeup": {"min": 15.0, "max": 30.0, "units": "seconds"},
                    "peak": {"min": 2.0, "max": 5.0, "units": "minutes"},
                    "offset": {"min": 5.0, "max": 10.0, "units": "minutes"},
                    "total": {"min": 10.0, "max": 20.0, "units": "minutes"},
                },
                "bioavailability": {"min": 100.0, "max": 100.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="得普利麻 (Diprivan)",
            half_life="快速初始分布半衰期 2 - 4 分钟；终末消除半衰期 1.5 - 3.0 小时（连续输注时量相关半衰期 < 40 分钟）",
            half_life_hours=2.2,
            t_max="静脉给药单臂-脑循环即达峰 (~0.03 - 0.05 小时 / 2 分钟内)",
            t_max_hours=0.05,
            bioavailability="静脉 100%；高脂溶性极易透过血脑屏障，几乎不作口服",
            cyp_enzymes=[
                "CYP2B6 (主要肝氧化羟化底物 / Substrate；转化后结合排出)",
                "CYP2C9 (次要羟化底物 / Minor Substrate)",
                "CYP3A4 (体外弱至中效抑制剂 / Weak Inhibitor)",
            ],
            clearance="高肝血流抽取率结合肺脏首过摄取，主要经 UGT1A9/UGT1A1 直接葡萄糖醛酸化（~50%）及 CYP2B6 羟化为无活性水溶性结合物，约 88% 经尿排泄",
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=1.5,
            washout_hours=12.0,
            washout_note="单次推注后靠极速脂肪/肌肉重分布于 5 - 15 分钟内迅速苏醒，血浆终末消除半衰期约 1.5 - 3 小时（约 11 - 15 小时基本排空）；脂肪蓄积在超长输注后才会显著拖尾。",
            mechanism_summary="高脂溶性烷基酚类全麻药，增强 GABAA 受体 Cl- 通道开放频率并在高浓度直接激动该受体，辅以抑制 NMDA 与慢钙通道。",
            indications=[
                "全身麻醉诱导与维持",
                "ICU 机械通气镇静与无痛胃肠镜/诊断操作镇静",
            ],
            contraindications=[
                "对鸡蛋、大豆卵磷脂或丙泊酚制剂过敏",
                "缺乏人工气道与呼吸监测保障的非医疗环境",
            ],
            active_metabolites=[],
            receptor_targets=[
                target("GABAA 受体 (β2/β3 亚基)", "强效正向变构调节剂并在麻醉浓度直接开放 Cl- 通道 (EC50 ≈ 2 - 5 μM)", "high", 0.97, "母体主导"),
                target("NMDA 谷氨酸受体", "轻至中等非竞争性通道抑制（辅助全麻期痛觉与记忆阻断）", "moderate", 0.56, "母体主导"),
                target("内源性大麻素 FAAH 酶", "抑制内源性花生四烯酸乙醇胺降解（部分解释苏醒后愉悦与止吐效应）", "low", 0.42, "母体主导"),
            ],
        ),
    },
    {
        "name": "Esketamine",
        "zh_name": "艾司氯胺酮",
        "zh_aliases": ["艾司氯胺酮", "盐酸艾司氯胺酮", "右氯胺酮", "凯得宁", "Spravato", "S-Ketamine"],
        "commonNames": make_common_names(
            ["艾司氯胺酮", "盐酸艾司氯胺酮", "右氯胺酮", "凯得宁"],
            ["Spravato", "S-Ketamine", "S(+)-Ketamine", "Ketanest-S"],
        ),
        "url": "https://go.drugbank.com/drugs/DB11823",
        "isApproved": True,
        "summary": "外消旋氯胺酮的高效 S(+)-对映异构体：对 NMDA 受体的阻滞亲和力约为 R(-)-异构体的 3 - 4 倍。已获 FDA 批准鼻喷雾剂（Spravato）用于难治性抑郁症与伴急性自杀意念的重度抑郁，同时临床用于静脉麻醉与围术期镇痛；具有显著解离、拟交感升压与剂量依赖性膀胱/认知毒性。",
        "effectsSummary": "急性期产生解离、时间/空间扭曲、镇痛及全麻作用；抗抑郁疗效可于给药后数小时内起效并持续数天。",
        "dosageRemark": "Spravato 鼻喷抑郁治疗标准单次 56 mg 或 84 mg（仅限认证医疗机构观察 ≥ 2 小时）；静脉镇痛/麻醉诱导剂量显著低于消旋氯胺酮（约消旋体的 50% - 65%）。",
        "generalRisks": "急性血压急剧升高（警惕主动脉瘤或未控高血压）、解离与感知障碍、镇静、眩晕、恶心呕吐。",
        "longtermRisks": "高频滥用可致严重氯胺酮相关性溃疡性膀胱炎（血尿、尿频、膀胱挛缩）、胆道扩张及工作记忆受损。",
        "saferUse": [
            "给药前需评估静息血压（若基础血压 > 140/90 mmHg 须先控压）；鼻喷后至少静卧观察 2 小时勿驾车",
            "严格限制使用频率，切忌高频娱乐化连续使用以防不可逆泌尿道损伤",
        ],
        "tolerance": {
            "full": "连续密集使用可迅速产生急性解离与镇痛耐受",
            "half": "4 - 7 天",
            "zero": "1 - 2 周",
        },
        "crossTolerances": ["dissociative", "ketamine"],
        "addictionPotential": "具中等心理依赖及强迫性复吸风险（解离逃避与快速奖赏强化效应）",
        "toxicities": [
            "血压危象、颅内压升高与心动过速",
            "高剂量致深度意识丧失、气道保护反射减弱或苏醒期幻觉谵妄",
            "慢性大剂量引发氯胺酮性膀胱炎与肝胆转氨酶异常",
        ],
        "categories": ["dissociative", "antidepressant", "analgesic", "sedative", "habit-forming"],
        "categories_zh": ["解离剂", "抗抑郁药", "镇痛药", "镇静剂", "易成瘾"],
        "interactions": {
            "dangerous": [
                "Alcohol / Opioids / Benzodiazepines / GHB (重度叠加中枢神经抑制、呕吐误吸与呼吸抑制风险)",
                "Sympathomimetics / Amphetamines / Cocaine / MAOI (剧烈叠加交感神经兴奋，诱发高血压危象与心律失常)",
            ],
            "unsafe": [
                "Theophylline / Aminophylline (降低惊厥阈值，合用诱发癫痫样发作)",
                "CYP2B6 / CYP3A4 强效抑制剂 (轻中度升高血浆暴露并延长急性解离期)",
            ],
            "uncertain": [
                "Lamotrigine (可能削弱部分谷氨酸爆发相关的急性抗抑郁与解离感知)",
                "SSRIs / SNRIs (临床常联合用于难治性抑郁，通常耐受良好但需监测血压波动)",
            ],
        },
        "roas": [
            {
                "name": "insufflated",
                "dose": {
                    "units": "mg",
                    "lightMin": 14.0,
                    "commonMin": 28.0,
                    "strongMin": 56.0,
                    "heavyMin": 84.0,
                },
                "duration": {
                    "onset": {"min": 5.0, "max": 10.0, "units": "minutes"},
                    "comeup": {"min": 10.0, "max": 20.0, "units": "minutes"},
                    "peak": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "offset": {"min": 45.0, "max": 90.0, "units": "minutes"},
                    "total": {"min": 1.5, "max": 3.0, "units": "hours"},
                },
                "bioavailability": {"min": 45.0, "max": 50.0},
            },
            {
                "name": "intravenous",
                "dose": {
                    "units": "mg",
                    "lightMin": 10.0,
                    "commonMin": 20.0,
                    "strongMin": 40.0,
                    "heavyMin": 80.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 60.0, "units": "seconds"},
                    "comeup": {"min": 1.0, "max": 3.0, "units": "minutes"},
                    "peak": {"min": 15.0, "max": 30.0, "units": "minutes"},
                    "offset": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "total": {"min": 1.0, "max": 2.0, "units": "hours"},
                },
                "bioavailability": {"min": 100.0, "max": 100.0},
            },
            {
                "name": "intramuscular",
                "dose": {
                    "units": "mg",
                    "lightMin": 15.0,
                    "commonMin": 30.0,
                    "strongMin": 60.0,
                    "heavyMin": 100.0,
                },
                "duration": {
                    "onset": {"min": 2.0, "max": 5.0, "units": "minutes"},
                    "comeup": {"min": 5.0, "max": 10.0, "units": "minutes"},
                    "peak": {"min": 20.0, "max": 45.0, "units": "minutes"},
                    "offset": {"min": 45.0, "max": 90.0, "units": "minutes"},
                    "total": {"min": 1.5, "max": 2.5, "units": "hours"},
                },
                "bioavailability": {"min": 90.0, "max": 95.0},
            },
        ],
        "pharmacokinetics": pk(
            brand_name="Spravato / 凯得宁 (Ketanest-S)",
            half_life="鼻喷呈现双相下降（前 2 - 4 小时快速分布清除，均终末半衰期 7 - 12 小时）；静脉初始分布半衰期 10 - 15 分钟；主要活性代谢物 S-去甲氯胺酮终末半衰期约 8 小时",
            half_life_hours=9.0,
            t_max="鼻喷末次喷雾后 20 - 40 分钟 (~0.5h) 达峰；静脉注射即刻达峰",
            t_max_hours=0.5,
            bioavailability="鼻喷生物利用度约 48%；静脉 100%；肌注约 93%",
            cyp_enzymes=[
                "CYP2B6 (主要 N-去甲基化代谢底物 / Substrate；生成 S-去甲氯胺酮)",
                "CYP3A4 (主要 N-去甲基化代谢底物 / Substrate)",
                "CYP2C9 (次要代谢底物 / Minor Substrate)",
                "CYP2C19 (次要代谢底物 / Minor Substrate)",
            ],
            clearance="广泛经肝微粒体 CYP2B6 与 CYP3A4 N-脱甲基生成 S-去甲氯胺酮（随后羟基化与葡萄糖醛酸化），< 2% 原形、> 78% 代谢物经尿排出",
            pk_role="ACTIVE_METABOLITE",
            effective_half_life_hours=9.0,
            washout_hours=45.0,
            washout_note="急性解离效应因前 2 - 4 小时快速脑血浆下降而消退；但母体及 S-去甲氯胺酮终末半衰期达 7 - 12 小时，完全血浆洗脱约需 36 - 48 小时。",
            mechanism_summary="高亲和力谷氨酸 NMDA 受体通道非竞争性开放阻滞剂（效价显著高于 R-氯胺酮），通过瞬时谷氨酸爆发、AMPA 激活及 mTOR/BDNF 通路发挥快速抗抑郁与镇痛作用。",
            indications=[
                "联合口服抗抑郁药治疗成人难治性抑郁症 (TRD) 及伴急性自杀意念重度抑郁",
                "全身麻醉诱导与维持、区域阻滞辅助镇静及围术期强效镇痛",
            ],
            contraindications=[
                "主动脉瘤、颅内压显著升高、脑血管畸形或动脉瘤性血管病史",
                "近期心肌梗死（6 周内）或急性未控高血压",
            ],
            active_metabolites=[
                met(
                    "S-Norketamine (S-去甲氯胺酮)",
                    "母体经肝脏 CYP2B6 与 CYP3A4 N-去甲基化生成",
                    "双相消除，均终末半衰期约 8.0 小时",
                    8.0,
                    1.5,
                    1.2,
                    "鼻喷给药后血浆 AUC 高于母体，但脑/血浆渗透比约为母体的 1/4 ~ 1/6",
                    "弱于母体 3 - 5 倍的 NMDA 拮抗活性，贡献部分持续镇痛与长时程代谢产物协同",
                )
            ],
            receptor_targets=[
                target("NMDA 谷氨酸受体 (PCP 通道位点)", "强效非竞争性电压依赖性通道开放阻滞剂 (Ki ≈ 0.3 μM，效价约为 R-异构体的 3 - 4 倍)", "high", 0.97, "母体主导"),
                target("μ-阿片受体 (MOR)", "弱部分激动/正向调制 (Ki ≈ 11 - 28 μM，辅助急性镇痛效果)", "low", 0.45, "共同贡献"),
                target("HCN1 超极化激活环核苷酸门控通道", "抑制电流（参与全麻催眠及快速抗抑郁网络重塑）", "moderate", 0.62, "母体主导"),
            ],
        ),
    },
    {
        "name": "Remifentanil",
        "zh_name": "瑞芬太尼",
        "zh_aliases": ["瑞芬太尼", "盐酸瑞芬太尼", "Ultiva"],
        "commonNames": make_common_names(
            ["瑞芬太尼", "盐酸瑞芬太尼"],
            ["Ultiva", "Remifentanil hydrochloride"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00899",
        "isApproved": True,
        "summary": "超短效强效选择性 μ-阿片受体激动剂，镇痛效价与芬太尼相当（约吗啡 100 - 200 倍）；其分子含独特甲酯键，被血液和组织非特异性酯酶极速水解，与肝肾功能无关且时量相关半衰期恒定为 3 - 4 分钟。停药后数分钟即丧失镇痛，易诱发严重痛觉过敏与即时呼吸停止。",
        "effectsSummary": "极速强效镇痛、麻醉深度抑制与即刻呼吸停止倾向，停药后 5 - 10 分钟内意识和自主呼吸迅速恢复。",
        "dosageRemark": "临床仅限持续微量静脉泵注（0.05 - 2.0 µg/kg/min）或极严密监护下气管插管单次辅助；严禁未经稀释或无通气保障下单次静推（胸壁肌肉强直可即刻锁死通气）。",
        "generalRisks": "瞬间呼吸骤停、剧烈胸壁/声门肌强直（木胸综合征）、严重心动过缓与低血压。",
        "longtermRisks": "大剂量术中输注停药后极易诱发急性阿片耐受（OIH）与剧烈术后反跳性疼痛。",
        "saferUse": [
            "仅限具备有创呼吸机、阿片拮抗剂纳洛酮及肌松药的手术室/ICU 团队控制给药",
            "停药前务必提前衔接长效非甾体或吗啡/羟考酮等桥接镇痛，严防停泵后暴发痛与痛觉过敏",
        ],
        "tolerance": {
            "full": "输注数小时即可出现急性受体脱敏与痛觉过敏 (OIH)",
            "half": "24 hours",
            "zero": "3 days",
        },
        "crossTolerances": ["opioid"],
        "addictionPotential": "极高潜在强迫复吸与即刻致死风险（起效消退如闪现，无医疗监护极易呼吸骤停死亡）",
        "toxicities": [
            "极低剂量差即可引起呼吸暂停、木胸综合征与窒息",
            "严重窦性心动过缓及低血压休克",
        ],
        "categories": ["opioid", "depressant", "analgesic", "sedative", "habit-forming"],
        "categories_zh": ["阿片类", "中枢抑制剂", "镇痛药", "镇静剂", "易成瘾"],
        "interactions": {
            "dangerous": [
                "Propofol / Midazolam / Volatile Anesthetics / Alcohol (极强协同引起呼吸暂停、低血压及心动过缓)",
                "β-受体阻滞剂 / Dexmedetomidine (协同抑制窦房结致重度心动过缓与心跳骤停)",
                "MAOI (禁与单胺氧化酶抑制剂联用或停药不足 14 天使用)",
            ],
            "unsafe": [
                "CNS Depressants / Gabapentinoids (剧烈加重中枢与呼吸抑制)",
            ],
            "uncertain": [
                "Esmolol / Anticholinergics (影响心率反射平衡；不改变瑞芬太尼酯酶水解清除)",
            ],
        },
        "roas": [
            {
                "name": "intravenous",
                "dose": {
                    "units": "µg",
                    "lightMin": 25.0,
                    "commonMin": 50.0,
                    "strongMin": 100.0,
                    "heavyMin": 200.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 60.0, "units": "seconds"},
                    "comeup": {"min": 30.0, "max": 90.0, "units": "seconds"},
                    "peak": {"min": 1.0, "max": 3.0, "units": "minutes"},
                    "offset": {"min": 3.0, "max": 10.0, "units": "minutes"},
                    "total": {"min": 5.0, "max": 15.0, "units": "minutes"},
                },
                "bioavailability": {"min": 100.0, "max": 100.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="瑞捷 / Ultiva",
            half_life="快速分布半衰期 ~1 分钟；终末消除半衰期 10 - 20 分钟；时量相关半衰期（无论输注多久）仅 3 - 4 分钟",
            half_life_hours=0.25,
            t_max="静脉给药后约 1 - 1.5 分钟达效应室峰 (~0.025h)",
            t_max_hours=0.025,
            bioavailability="静脉 100%（不通过胃肠道或肌注常规给药）",
            cyp_enzymes=[
                "非细胞色素酶代谢途径 (经血液与骨骼肌等组织非特异性酯酶极速水解脱酯，完全不依赖 CYP450 肝药酶及胆碱酯酶)",
            ],
            clearance="极其迅速的血浆/组织非特异性酯酶脱甲酯代谢（清除率约 40 mL/kg/min），生成几乎无活性的羧酸代谢物 GI90291（效价仅为母体 1/4600），完全不蓄积于肝肾功能不全者体内",
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=0.25,
            washout_hours=2.0,
            washout_note="全药典清除最快的阿片类药物之一：停泵后 3 - 10 分钟临床镇痛与呼吸抑制迅速解除，约 1.5 - 2 小时完成母体完全清除，但注意羧酸代谢物在无尿者体内可滞留。",
            mechanism_summary="高选择性 μ-阿片受体（MOR）完全激动剂，通过酯酶可水解丙酸酯支链实现毫秒~分钟级精准药效滴定与零蓄积清除。",
            indications=[
                "全麻诱导与术中连续泵注强效镇痛",
                "ICU 机械通气短时剧痛操作及镇静协同",
            ],
            contraindications=[
                "绝对禁止用于硬膜外或鞘内注射（制剂含甘氨酸赋形剂具脊髓神经毒性）",
                "缺乏呼吸辅助及急救条件的环境",
            ],
            active_metabolites=[],
            receptor_targets=[
                target("μ-阿片受体 (MOR)", "高选择性完全激动剂 (Ki ≈ 1.2 nM，镇痛效价与芬太尼相当)", "high", 0.99, "母体主导"),
                target("δ / κ 阿片受体 (DOR / KOR)", "极低亲和力（对 μ 受体选择性比超过数百至千倍）", "low", 0.25, "母体主导"),
            ],
        ),
    },
    {
        "name": "Alfentanil",
        "zh_name": "阿芬太尼",
        "zh_aliases": ["阿芬太尼", "盐酸阿芬太尼", "Rapifen", "Alfenta"],
        "commonNames": make_common_names(
            ["阿芬太尼", "盐酸阿芬太尼"],
            ["Rapifen", "Alfenta"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00802",
        "isApproved": True,
        "summary": "短效、极快起效的四唑啉基苯基哌啶类合成阿片镇痛药（pKa ≈ 6.5 使血浆中约 89% 为非解离型脂溶性分子，故 1 分钟内即极速入脑达峰，效价约为芬太尼的 1/4 ~ 1/5、吗啡的 15 - 25 倍）。强依赖肝脏 CYP3A4 清除，是临床公认的 CYP3A4 敏感探针底物。",
        "effectsSummary": "极速镇痛、短暂强效麻醉辅助镇静、明显止咳反射抑制与强效呼吸抑制。",
        "dosageRemark": "临床多用于短小日间手术或气管插管前负荷给药（常规成人静注 250 - 1500 µg，即 5 - 30 µg/kg）；联用红霉素或伏立康唑等 CYP3A4 抑制剂时清除可锐减 50% 以上。",
        "generalRisks": "突发呼吸停止、骨骼肌僵直（木胸）、心动过缓、低血压与恶心呕吐。",
        "longtermRisks": "反复大剂量输注可因肝消除饱和或 CYP3A4 抑制产生意外持久呼吸抑制及躯体依赖。",
        "saferUse": [
            "给药前核查是否正在服用大环内酯类（红霉素/克拉霉素）、唑类抗真菌药等 CYP3A4 抑制剂",
            "备妥纳洛酮急救与正压通气设备",
        ],
        "tolerance": {
            "full": "连续使用可形成经典阿片类交叉耐受",
            "half": "3 - 5 天",
            "zero": "1 - 2 周",
        },
        "crossTolerances": ["opioid"],
        "addictionPotential": "高成瘾性与极窄安全治疗窗（静脉快速入脑强化且高危呼吸骤停）",
        "toxicities": [
            "急性呼吸停止、紫绀与肺水肿",
            "胸壁肌肉强直阻碍机械通气",
        ],
        "categories": ["opioid", "depressant", "analgesic", "sedative", "habit-forming"],
        "categories_zh": ["阿片类", "中枢抑制剂", "镇痛药", "镇静剂", "易成瘾"],
        "interactions": {
            "dangerous": [
                "Propofol / Benzodiazepines / Alcohol (剧烈叠加呼吸中枢抑制与低血压风险)",
                "CYP3A4 强效抑制剂 (酮康唑、伊曲康唑、利托那韦、克拉霉素，清除骤降引发严重呼吸暂停)",
                "MAOI (合用可引发致命 5-羟色胺或血压危象)",
            ],
            "unsafe": [
                "Erythromycin / Fluconazole / Diltiazem (中效 CYP3A4 抑制剂显著延长苏醒与呼吸恢复时间)",
            ],
            "uncertain": [
                "Carbamazepine / Rifampin (强诱导 CYP3A4 可加快代谢缩短效期)",
            ],
        },
        "roas": [
            {
                "name": "intravenous",
                "dose": {
                    "units": "µg",
                    "lightMin": 250.0,
                    "commonMin": 500.0,
                    "strongMin": 1000.0,
                    "heavyMin": 2000.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 60.0, "units": "seconds"},
                    "comeup": {"min": 30.0, "max": 90.0, "units": "seconds"},
                    "peak": {"min": 2.0, "max": 5.0, "units": "minutes"},
                    "offset": {"min": 15.0, "max": 30.0, "units": "minutes"},
                    "total": {"min": 30.0, "max": 60.0, "units": "minutes"},
                },
                "bioavailability": {"min": 100.0, "max": 100.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="Rapifen / Alfenta",
            half_life="双/三室分布半衰期 1 分钟与 14 分钟；终末消除半衰期 90 - 111 分钟 (~1.65 小时)",
            half_life_hours=1.65,
            t_max="静脉注射后 1 - 1.5 分钟达脑内效应峰 (~0.025h)",
            t_max_hours=0.025,
            bioavailability="静脉 100%；分布容积显著小于芬太尼 (0.4 - 1.0 L/kg)",
            cyp_enzymes=[
                "CYP3A4 (强敏感性核心代谢底物 / Substrate；临床标准 CYP3A4 体内活性探针药，受大环内酯及唑类显著抑制)",
            ],
            clearance="几乎完全经肝脏 CYP3A4 氧化 N-脱烷基与 O-脱甲基化生成无活性诺阿芬太尼等代谢物，经尿排出（原形 < 1%）；肝硬化及合用红霉素/酮康唑者清除率锐减 > 50%",
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=1.65,
            washout_hours=8.5,
            washout_note="终末消除半衰期约 1.5 - 1.8 小时（比芬太尼更短且分布容积更小），正常肝功下约 8 - 9 小时完成血浆洗脱；但联用强效 CYP3A4 抑制剂时半衰期可延长至 4 小时以上。",
            mechanism_summary="超低脂溶分布容积结合低 pKa（~6.5）实现约 90% 血浆非解离态极速跨血脑屏障，高选择性激动 μ-阿片受体。",
            indications=[
                "短小外科操作镇痛与全身麻醉诱导期应激抑制",
                "监测下麻醉管理 (MAC) 镇痛",
            ],
            contraindications=[
                "对阿片类过敏或严重呼吸抑制无人工气道支持",
            ],
            active_metabolites=[],
            receptor_targets=[
                target("μ-阿片受体 (MOR)", "强效完全激动剂 (效价约为芬太尼 1/4、吗啡 15 - 25 倍)", "high", 0.96, "母体主导"),
            ],
        ),
    },
    {
        "name": "Sevoflurane",
        "zh_name": "七氟烷",
        "zh_aliases": ["七氟烷", "喜保福宁", "Sevorane", "Ultane"],
        "commonNames": make_common_names(
            ["七氟烷", "喜保福宁"],
            ["Sevorane", "Ultane", "Sojourn"],
        ),
        "url": "https://go.drugbank.com/drugs/DB01236",
        "isApproved": True,
        "summary": "低血/气分配系数（0.63 - 0.69）挥发性氟化吸入全麻药：气道无刺激性，可经面罩快速平稳诱导与迅速苏醒，是成人与儿童最主流的吸入麻醉维持药。95% 以上经肺泡原形呼出，仅 2% - 5% 经 CYP2E1 代谢为六氟异丙醇（HFIP）与无机氟；高危遗传易感者可诱发恶性高热。",
        "effectsSummary": "剂量依赖性吸入麻醉、镇静、健忘、轻度支气管扩张与骨骼肌松弛；停吸后数分钟内快速清醒。",
        "dosageRemark": "临床需配合专用精确挥发罐按肺泡吸入浓度（Vol %）给药：成人 MAC（最小肺泡有效浓度）约为 2.0%，常于 1.5% - 3.0% 维持。严禁液态误服或非精密挥发器直接吸入（窒息与肺损伤极高）。",
        "generalRisks": "低血压、呼吸抑制、苏醒期躁动（尤其儿童）、术后恶心呕吐及支气管分泌轻微改变。",
        "longtermRisks": "反复非医疗暴露（或含衰竭碱石灰 CO2 吸收剂接触产生复合物 A）有理论肾小管损伤与缺氧风险。",
        "saferUse": [
            "严禁任何非医用无氧供条件下自行吸入，缺氧窒息可在 1 分钟内发生且缺乏清醒反馈",
            "恶性高热家族史或已知 RYR1/CACNA1S 突变者绝对禁用（须备丹曲林）",
        ],
        "tolerance": {
            "full": None,
            "half": None,
            "zero": None,
        },
        "crossTolerances": ["depressant"],
        "addictionPotential": "无经典日常口服药物依赖模式，但存在高致死率缺氧与误吸风险的挥发剂吸入滥用危害",
        "toxicities": [
            "致命性恶性高热（高热、高碳酸血症、肌肉强直、横纹肌溶解与酸中毒）",
            "缺氧、循环衰竭与深麻醉呼吸停止",
        ],
        "categories": ["depressant", "sedative", "hypnotic"],
        "categories_zh": ["中枢抑制剂", "镇静剂", "催眠药"],
        "interactions": {
            "dangerous": [
                "Succinylcholine (合并使用显著提高恶性高热与急性高钾心脏骤停风险)",
                "Opioids / Propofol / Benzodiazepines (显著降低所需 MAC 值并深度叠加呼吸抑制与低血压)",
            ],
            "unsafe": [
                "Non-depolarizing Neuromuscular Blockers (如罗库溴铵、维库溴铵：七氟烷强烈增强并延长肌松作用)",
                "Epinephrine (可轻度增加心肌对儿茶酚胺的致心律失常敏感性，虽显著低于氟烷)",
            ],
            "uncertain": [
                "Alcohol / Isoniazid (慢性饮酒诱导 CYP2E1 可使代谢产物无机氟与 HFIP 生成略升)",
            ],
        },
        "roas": [
            {
                "name": "inhaled",
                "dose": {
                    "units": "%",
                    "lightMin": 0.5,
                    "commonMin": 1.5,
                    "strongMin": 2.5,
                    "heavyMin": 4.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 90.0, "units": "seconds"},
                    "comeup": {"min": 1.0, "max": 3.0, "units": "minutes"},
                    "peak": {"min": 3.0, "max": 10.0, "units": "minutes"},
                    "offset": {"min": 5.0, "max": 15.0, "units": "minutes"},
                    "total": {"min": 10.0, "max": 25.0, "units": "minutes"},
                },
            }
        ],
        "pharmacokinetics": pk(
            brand_name="喜保福宁 (Sevorane / Ultane)",
            half_life="血/气分配系数仅 0.63 - 0.69：停吸后肺泡与血液快速洗脱半时仅数分钟；脂肪组织深层终末洗脱与代谢氟化物消除半衰期约 20 - 23 小时（功能性中枢洗脱 < 0.5h）",
            half_life_hours=2.5,
            t_max="吸入后 2 - 5 分钟 (~0.08h) 达高灌注脑组织平衡",
            t_max_hours=0.08,
            bioavailability="经肺泡扩散进入动脉血，低血/气分配系数决定极快平衡速度",
            cyp_enzymes=[
                "CYP2E1 (少量代谢底物 / Minor Substrate；仅约 2% - 5% 吸入量经肝 CYP2E1 氧化脱氟生成六氟异丙醇与无机氟)",
            ],
            clearance="> 95% 经肺泡原形呼出；约 2% - 5% 经肝 CYP2E1 氧化成六氟异丙醇（HFIP）后迅速葡萄糖醛酸化经尿排出",
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=0.5,
            washout_hours=12.0,
            washout_note="呼吸换气洗脱极快：停吸后 5 - 15 分钟内脑内分压骤降苏醒；脂肪蓄积洗脱约在停药 2 - 12 小时内基本完成，微量尿氟代谢物完全排空需约 1 - 2 天。",
            mechanism_summary="多靶点挥发性全麻剂：正向变构调节脑内 GABAA 与甘氨酸抑制性氯通道、抑制 NMDA 受体并激活双孔钾通道 (TREK-1) 引起神经元超极化。",
            indications=[
                "成人及儿童全身麻醉诱导与维持",
            ],
            contraindications=[
                "恶性高热既往史或明确遗传易感史（绝对禁忌）",
                "对卤化吸入全麻药过敏或既往卤素麻醉不明原因肝损",
            ],
            active_metabolites=[],
            receptor_targets=[
                target("GABAA 受体与甘氨酸受体", "正向变构调节增强抑制性突触氯离子内流", "high", 0.90, "母体主导"),
                target("TREK-1 双孔钾通道", "激活背景漏钾电流致使中枢神经元超极化与意识丧失", "high", 0.85, "母体主导"),
                target("NMDA 谷氨酸受体", "中等非竞争性通道抑制", "moderate", 0.60, "母体主导"),
            ],
        ),
    },
    {
        "name": "Isoflurane",
        "zh_name": "异氟烷",
        "zh_aliases": ["异氟烷", "异氟醚", "Forane"],
        "commonNames": make_common_names(
            ["异氟烷", "异氟醚"],
            ["Forane", "Isoflurane", "Aerrane"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00753",
        "isApproved": True,
        "summary": "经典挥发性卤化醚类全身吸入麻醉药（血/气分配系数 1.40 - 1.45，MAC 约 1.15%），气道刺激性较七氟烷高（通常不用于吸入诱导而用于静注诱导后的麻醉维持）；代谢率极低（< 0.2%），99% 以上经肺原形呼出，肝肾毒性极小，但大剂量易致显著体循环血管扩张与低血压。",
        "effectsSummary": "强效吸入麻醉维持、深刻肌肉松弛（大幅增效非去极化肌松药）及可逆意识与痛觉消失；气味较刺鼻易引起咳嗽或屏气。",
        "dosageRemark": "经专用异氟烷挥发罐吸入给药（成人 1 MAC 约 1.15%，常用维持浓度 1.0% - 2.5%）。",
        "generalRisks": "强烈外周血管扩张性低血压、呼吸道刺激引起屏气/喉痉挛（诱导期）、呼吸抑制与术后寒战恶心。",
        "longtermRisks": "极罕见诱发恶性高热；长期暴露或慢性低浓度职业吸入需规范废气排空。",
        "saferUse": [
            "绝对禁止脱离麻醉呼吸监测与氧源的自我吸入行为",
            "恶性高热易感体质禁用",
        ],
        "tolerance": {
            "full": None,
            "half": None,
            "zero": None,
        },
        "crossTolerances": ["depressant"],
        "addictionPotential": "极少数文献报告过职业接触者误吸，风险在于瞬间呼吸抑制窒息而非愉悦成瘾",
        "toxicities": [
            "恶性高热（高热、全身强直、代谢危象）",
            "严重体循环血管扩张致顽固性低血压及缺氧窒息",
        ],
        "categories": ["depressant", "sedative", "hypnotic"],
        "categories_zh": ["中枢抑制剂", "镇静剂", "催眠药"],
        "interactions": {
            "dangerous": [
                "Succinylcholine (合并触发恶性高热高风险)",
                "Opioids / Propofol / Nitrous Oxide (协同降低 MAC 需求并强烈加深血压与呼吸抑制)",
            ],
            "unsafe": [
                "Non-depolarizing Muscle Relaxants (显著增强阿曲库铵、维库溴铵等肌松深度与时长)",
                "Calcium Channel Blockers (叠加体循环血管扩张引发严重低血压)",
            ],
            "uncertain": [
                "MAOI (围术期血压调节不稳风险)",
            ],
        },
        "roas": [
            {
                "name": "inhaled",
                "dose": {
                    "units": "%",
                    "lightMin": 0.4,
                    "commonMin": 1.15,
                    "strongMin": 1.8,
                    "heavyMin": 3.0,
                },
                "duration": {
                    "onset": {"min": 1.0, "max": 3.0, "units": "minutes"},
                    "comeup": {"min": 2.0, "max": 5.0, "units": "minutes"},
                    "peak": {"min": 5.0, "max": 15.0, "units": "minutes"},
                    "offset": {"min": 10.0, "max": 25.0, "units": "minutes"},
                    "total": {"min": 15.0, "max": 40.0, "units": "minutes"},
                },
            }
        ],
        "pharmacokinetics": pk(
            brand_name="Forane / Aerrane",
            half_life="血/气分配系数 1.40 - 1.45（较七氟烷溶血性高约一倍）：肺泡初段洗脱数分钟，外周与脂肪组织平衡洗脱较七氟烷慢（等效组织终末洗脱 2 - 3 小时）",
            half_life_hours=2.0,
            t_max="吸入后约 5 - 10 分钟 (~0.1h) 达脑内平衡",
            t_max_hours=0.1,
            bioavailability="经肺泡扩散吸收，极少经肝氧化失活",
            cyp_enzymes=[
                "CYP2E1 (极微弱代谢底物 / Minimal Substrate；< 0.2% 吸入量经 CYP2E1 氧化为三氟乙酸，基本不受肝酶互动影响)",
            ],
            clearance="> 99% 经肺泡呼气原形排出，仅 < 0.2% 经肝 CYP2E1 代谢为极微量三氟乙酸盐随尿排出",
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=0.75,
            washout_hours=12.0,
            washout_note="停吸后约 10 - 25 分钟恢复意识，血浆分布洗脱略慢于七氟烷，约 12 小时内完成全身残留气体洗脱。",
            mechanism_summary="脂溶性氟化醚全麻药，增强 GABAA 与甘氨酸抑制受体功能，抑制 NMDA 及烟碱型乙酰胆碱受体并激活双孔钾通道。",
            indications=[
                "成人及儿童全身麻醉维持",
            ],
            contraindications=[
                "恶性高热已知或疑似易感者",
                "卤代烷类吸入麻醉药物过敏史",
            ],
            active_metabolites=[],
            receptor_targets=[
                target("GABAA 受体与甘氨酸受体", "正向变构调节氯通道开放实现全麻与中枢性肌松", "high", 0.90, "母体主导"),
                target("TREK-1 双孔钾通道", "激活钾外流使神经元超极化", "high", 0.82, "母体主导"),
                target("NMDA / nAChR 受体", "抑制兴奋性谷氨酸与烟碱通道传导", "moderate", 0.62, "母体主导"),
            ],
        ),
    },
    {
        "name": "Lidocaine",
        "zh_name": "利多卡因",
        "zh_aliases": ["利多卡因", "盐酸利多卡因", "昔洛卡因", "Xylocaine"],
        "commonNames": make_common_names(
            ["利多卡因", "盐酸利多卡因", "昔洛卡因"],
            ["Xylocaine", "Lignocaine", "Lidoderm"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00281",
        "isApproved": True,
        "summary": "经典酰胺类局部麻醉药与 Ib 类抗心律失常药，通过可逆阻滞电压门控钠通道（NaV）阻断痛觉神经动作电位传导并缩短心肌浦肯野纤维动作电位时程。主要经肝脏 CYP1A2 与 CYP3A4 脱乙基生成保留活性和惊厥毒性的代谢物 MEGX（亦是临床肝储备功能试验标准底物）；血药浓度超标可致特征性口周麻木、耳鸣、全面性癫痫与心脏停搏。",
        "effectsSummary": "局部注射或贴敷迅速阻滞局域痛觉与触觉传导；静脉给药兼具室性心律失常抑制、气道反射钝化及轻度中枢镇静镇痛。",
        "dosageRemark": "成人单次局部浸润安全极量一般不超过 4.5 mg/kg（通常 ≤ 300 mg，配伍肾上腺素减慢吸收可增至 7 mg/kg / 500 mg）；严禁把含肾上腺素制剂推入末梢血管（手指/阴茎）或静注。",
        "generalRisks": "早发神经系统毒性信号（口周舌麻、金属味、耳鸣、视物模糊、肌颤），局部误入血管骤升可即刻抽搐。",
        "longtermRisks": "长期或大面积破损皮肤贴敷可引发高铁血红蛋白血症与蓄积中毒（尤其肝衰或合用 CYP 抑制剂）。",
        "saferUse": [
            "神经阻滞与局部浸润务必反复回抽确认无回血后再推药，严防直接注入动脉/静脉引发急性局麻药全身毒性 (LAST)",
            "一旦出现口唇麻木、耳鸣或烦躁立即停药，重度 LAST 救治首选 20% 脂肪乳静脉推注",
        ],
        "tolerance": {
            "full": None,
            "half": None,
            "zero": None,
        },
        "crossTolerances": [],
        "addictionPotential": "无精神成瘾潜力；常见非医学误用风险为局部黏膜过量吸收导致的心律失常与癫痫中毒",
        "toxicities": [
            "局麻药全身毒性 (LAST)：全面性强直阵挛癫痫发作、窦性停搏、QRS 增宽与难治性室颤",
            "高铁血红蛋白血症（高浓度外用或复方制剂时警惕发绀）",
        ],
        "categories": ["analgesic", "depressant"],
        "categories_zh": ["镇痛药", "中枢抑制剂"],
        "interactions": {
            "dangerous": [
                "Class I / III Antiarrhythmics (与胺碘酮、普罗帕酮、美西律合用严重叠加心肌传导抑制与低血压)",
                "Bupivacaine / Ropivacaine (多种酰胺类局麻药毒性完全累加计算总毫克限额)",
            ],
            "unsafe": [
                "Fluvoxamine / Ciprofloxacin (强效 CYP1A2 抑制剂显著降低利多卡因肝清除并诱发中毒)",
                "Propranolol / Metoprolol (降压 β 阻滞剂减少肝血流量，使利多卡因清除率下降约 30%)",
                "CYP3A4 强效抑制剂 (红霉素、伊曲康唑、克拉霉素等阻断 N-脱乙基)",
            ],
            "uncertain": [
                "Cimetidine (抑制肝血流量与细胞色素酶轻中度升高稳态血浓度)",
            ],
        },
        "roas": [
            {
                "name": "subcutaneous",
                "dose": {
                    "units": "mg",
                    "lightMin": 50.0,
                    "commonMin": 100.0,
                    "strongMin": 200.0,
                    "heavyMin": 300.0,
                },
                "duration": {
                    "onset": {"min": 2.0, "max": 5.0, "units": "minutes"},
                    "comeup": {"min": 3.0, "max": 10.0, "units": "minutes"},
                    "peak": {"min": 15.0, "max": 45.0, "units": "minutes"},
                    "offset": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "total": {"min": 1.0, "max": 2.0, "units": "hours"},
                },
                "bioavailability": {"min": 90.0, "max": 100.0},
            },
            {
                "name": "intravenous",
                "dose": {
                    "units": "mg",
                    "lightMin": 50.0,
                    "commonMin": 75.0,
                    "strongMin": 100.0,
                    "heavyMin": 150.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 90.0, "units": "seconds"},
                    "comeup": {"min": 1.0, "max": 2.0, "units": "minutes"},
                    "peak": {"min": 2.0, "max": 5.0, "units": "minutes"},
                    "offset": {"min": 10.0, "max": 20.0, "units": "minutes"},
                    "total": {"min": 20.0, "max": 45.0, "units": "minutes"},
                },
                "bioavailability": {"min": 100.0, "max": 100.0},
            },
            {
                "name": "transdermal",
                "dose": {
                    "units": "mg",
                    "lightMin": 175.0,
                    "commonMin": 350.0,
                    "strongMin": 700.0,
                    "heavyMin": 1050.0,
                },
                "duration": {
                    "onset": {"min": 30.0, "max": 60.0, "units": "minutes"},
                    "comeup": {"min": 1.0, "max": 4.0, "units": "hours"},
                    "peak": {"min": 6.0, "max": 11.0, "units": "hours"},
                    "offset": {"min": 4.0, "max": 8.0, "units": "hours"},
                    "total": {"min": 12.0, "max": 18.0, "units": "hours"},
                },
                "bioavailability": {"min": 3.0, "max": 5.0},
            },
        ],
        "pharmacokinetics": pk(
            brand_name="Xylocaine / Lidoderm",
            half_life="静注初始分布半衰期约 8 分钟；终末消除半衰期 1.5 - 2.0 小时 (连续输注 > 24h 或肝损心衰时可延长至 4 - 6h)；活性代谢物 MEGX 半衰期约 2 小时，GX 约 10 小时",
            half_life_hours=1.8,
            t_max="静注即刻 (~0.05h)；局部浸润/神经阻滞血浆达峰约 15 - 30 分钟 (~0.35h)；透皮贴剂达峰 9 - 11 小时",
            t_max_hours=0.25,
            bioavailability="静注 100%；口服因极强肝脏首过提取率仅约 35%；完整皮肤 5% 贴剂吸收仅约 3%（破损或黏膜吸收飙升）",
            cyp_enzymes=[
                "CYP1A2 (主要 N-脱乙基代谢底物 / Substrate；临床浓度生成活性产物 MEGX，受氟伏沙明显著抑制)",
                "CYP3A4 (主要 N-脱乙基代谢底物 / Substrate；高浓度下亦参与转化 MEGX 与 GX)",
            ],
            clearance="约 90% 经肝高抽取率血流依赖性代谢（CYP1A2/CYP3A4 生成活性 MEGX 及 GX），< 10% 原形经肾排泄",
            pk_role="ACTIVE_METABOLITE",
            effective_half_life_hours=2.2,
            washout_hours=12.0,
            washout_note="单次注射或短程静注的母体与 MEGX 在 10 - 12 小时内清除；若为连续输注 > 24 小时或肝肾功能严重受损，脱乙基次级产物 GX（半衰期 ~10h）与母体可明显蓄积。",
            mechanism_summary="可逆结合电压门控钠通道 (NaV) 胞内失活态孔道，阻断 Na+ 内流进而阻遏感觉神经动作电位传播，并缩短心肌动作电位不应期。",
            indications=[
                "局部浸润、周围神经阻滞、硬膜外麻醉及黏膜表面镇痛",
                "带状疱疹后神经痛（5% 透皮贴剂）与室性心律失常急性控制",
            ],
            contraindications=[
                "对酰胺类局部麻醉药过敏或阿-斯综合征（重度窦房/房室传导阻滞）",
                "严重肝衰竭患者禁止连续大剂量静脉输注",
            ],
            active_metabolites=[
                met(
                    "MEGX (单乙基甘氨酰二甲苯胺)",
                    "母体经肝脏 CYP1A2 与 CYP3A4 氧化 N-脱乙基生成",
                    "1.5 - 2.5 小时",
                    2.0,
                    0.5,
                    0.5,
                    "常作为肝血流/肝细胞功能的经典定量指标；连续输注时暴露显著提升",
                    "保留约 80% 抗心律失常效价及显著中枢惊厥神经毒性，协同母体致中毒效应",
                )
            ],
            receptor_targets=[
                target("电压门控钠通道 (NaV1.7 / NaV1.8 / NaV1.5)", "电压及频率依赖性孔道内阻滞剂（阻断痛觉及心肌去极化 Na+ 内流）", "high", 0.94, "共同贡献"),
                target("电压门控钾通道 / NMDA", "高浓度下微弱调节（参与中枢全身镇痛与惊厥毒性）", "low", 0.35, "母体主导"),
            ],
        ),
    },
    {
        "name": "Ropivacaine",
        "zh_name": "罗哌卡因",
        "zh_aliases": ["罗哌卡因", "盐酸罗哌卡因", "耐乐品", "Naropin"],
        "commonNames": make_common_names(
            ["罗哌卡因", "盐酸罗哌卡因", "耐乐品"],
            ["Naropin", "Ropivacaine hydrochloride"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00296",
        "isApproved": True,
        "summary": "纯 S-对映体长效酰胺类局部麻醉药：相比布比卡因具备更低的中枢神经与心肌钠通道滞留毒性，且低浓度（0.2%）即可呈现显著的“感觉与运动神经阻滞分离”，广泛用于硬膜外镇痛、分娩镇痛及周围神经阻滞。高度依赖肝脏 CYP1A2 代谢，与氟伏沙明合用清除率大降约 70%。",
        "effectsSummary": "长效区域感觉神经痛觉阻滞（维持 3 - 8 小时），低浓度下保留大部分运动肌力且系统心血管抑制较轻。",
        "dosageRemark": "仅供局部浸润、周围神经阻滞与硬膜外腔注射（成人单次区域阻滞一般 ≤ 200 - 250 mg）；绝对禁止静脉注射或静脉区域麻醉（Bier block）。",
        "generalRisks": "低血压、心动过缓、恶心、排尿困难（硬膜外）及误入血管导致口周麻木、耳鸣。",
        "longtermRisks": "大剂量硬膜外连续输注若合并肝功能受损或 CYP1A2 强抑制剂可致血浆游离蓄积。",
        "saferUse": [
            "严禁静脉注射，给药时必须分次小量回抽测试防血管内注射",
            "与氟伏沙明、环丙沙星等 CYP1A2 强抑制剂合用长程泵注时需大幅下调输注速率",
        ],
        "tolerance": {
            "full": None,
            "half": None,
            "zero": None,
        },
        "crossTolerances": [],
        "addictionPotential": "无成瘾滥用潜力；全部临床风险集中于操作失误入血或过量导致的全身毒性 (LAST)",
        "toxicities": [
            "局麻药全身毒性 (LAST)：全面癫痫抽搐、传导阻滞、心室颤动与难治性心脏骤停",
        ],
        "categories": ["analgesic", "depressant"],
        "categories_zh": ["镇痛药", "中枢抑制剂"],
        "interactions": {
            "dangerous": [
                "Lidocaine / Bupivacaine (同类酰胺局麻药毒素完全叠加计算安全上限)",
                "Class III / Ib Antiarrhythmics (乙胺碘呋酮/美西律叠加抑制心脏钠钾传导)",
            ],
            "unsafe": [
                "Fluvoxamine (强效 CYP1A2 抑制剂使罗哌卡因系统清除率暴跌 68% - 77%，极易引发持续输注毒性蓄积)",
                "Ciprofloxacin / Enoxacin (中强效 CYP1A2 抑制剂延长半衰期并升高血浆总暴露)",
            ],
            "uncertain": [
                "Ketoconazole / CYP3A4 Inhibitors (CYP3A4 为次要途径，单独阻断通常仅致轻微清除下降)",
            ],
        },
        "roas": [
            {
                "name": "subcutaneous",
                "dose": {
                    "units": "mg",
                    "lightMin": 25.0,
                    "commonMin": 75.0,
                    "strongMin": 150.0,
                    "heavyMin": 225.0,
                },
                "duration": {
                    "onset": {"min": 10.0, "max": 20.0, "units": "minutes"},
                    "comeup": {"min": 15.0, "max": 30.0, "units": "minutes"},
                    "peak": {"min": 1.0, "max": 3.0, "units": "hours"},
                    "offset": {"min": 2.0, "max": 4.0, "units": "hours"},
                    "total": {"min": 4.0, "max": 8.0, "units": "hours"},
                },
                "bioavailability": {"min": 87.0, "max": 100.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="耐乐品 (Naropin)",
            half_life="静脉测定真消除半衰期约 1.8 小时 (1.5 - 2.0h)；硬膜外与神经阻滞因局部组织缓慢吸收限制，表观终末半衰期延长至约 4.2 小时",
            half_life_hours=1.8,
            t_max="硬膜外注射后约 0.5 小时 (15 - 45 分钟) 达血浆峰",
            t_max_hours=0.5,
            bioavailability="硬膜外腔绝对生物利用度约 87% - 100%（呈快慢双相吸收入血）",
            cyp_enzymes=[
                "CYP1A2 (主要芳香羟化代谢底物 / Substrate；生成主要尿产物 3-羟罗哌卡因，对氟伏沙明高度敏感)",
                "CYP3A4 (次要 N-脱烷基代谢底物 / Minor Substrate；生成 PPX)",
            ],
            clearance="肝脏广泛代谢（86% 以上经尿排出代谢物，仅约 1% 原形排出），其中 CYP1A2 主导生成 3-羟罗哌卡因，CYP3A4 生成微量活性更弱的 PPX",
            pk_role="PARENT_DOMINANT",
            effective_half_life_hours=3.5,
            washout_hours=20.0,
            washout_note="静脉本身消除仅 ~1.8h，但硬膜外与周围神经丛局部组织缓释吸收使实际全身终末消除拖延至 ~4.2h，完整清零需约 18 - 22 小时。",
            mechanism_summary="纯 S(-)-光学异构体酰胺局麻药，选择性阻断外周神经纤维电压门控钠通道（对细无髓鞘 Aδ/C 痛觉纤维阻断优先于粗大 Aβ 运动纤维），并降低心肌毒性。",
            indications=[
                "硬膜外外科麻醉、剖宫产及分娩镇痛",
                "周围神经丛阻滞、术后持续镇痛及局部伤口浸润",
            ],
            contraindications=[
                "静脉区域阻滞（Bier 阻滞）及产科宫颈旁阻滞",
                "对酰胺类局部麻醉药过敏",
            ],
            active_metabolites=[],
            receptor_targets=[
                target("电压门控钠通道 (NaV1.7 / NaV1.8)", "长效立体选择性孔道阻滞剂（兼具高痛觉-运动分离度与较低心脏 NaV1.5 滞留率）", "high", 0.94, "母体主导"),
            ],
        ),
    },
    {
        "name": "Thiopental",
        "zh_name": "硫喷妥钠",
        "zh_aliases": ["硫喷妥钠", "戊硫代巴比妥", "Pentothal", "Sodium thiopental"],
        "commonNames": make_common_names(
            ["硫喷妥钠", "戊硫代巴比妥"],
            ["Pentothal", "Sodium thiopental", "Thiopentone"],
        ),
        "url": "https://go.drugbank.com/drugs/DB00599",
        "isApproved": True,
        "summary": "经典超短效硫代巴比妥类静脉全麻药与难治性癫痫持续状态急救药：超高脂溶性使单剂静注后 15 - 30 秒内迅速过脑诱导麻醉，仅靠 5 - 15 分钟内向骨骼肌与脂肪剧烈重分布而苏醒，但真实肝消除半衰期长达 9 - 12 小时（饱和输注后可飙升至 26 小时以上且经脱硫代谢产生超长效活性巴比妥——戊巴比妥 Pentobarbital）。",
        "effectsSummary": "20 秒内极速诱导深度意识丧失并显著降低脑代谢氧耗与颅内压；高剂量可严重抑制呼吸与心肌收缩。",
        "dosageRemark": "成人全麻诱导常为 3 - 5 mg/kg（约 200 - 400 mg）慢推；药液呈强碱性（pH ~10.5），绝不可漏出血管或误入动脉（可致剧痛性动脉痉挛及肢体坏死）。",
        "generalRisks": "即刻呼吸停止、严重低血压、喉痉挛（缺乏镇痛作用对浅刺激易呛咳喉痉挛）及血管外渗组织坏死。",
        "longtermRisks": "反复大剂量或连续泵注后脂肪库彻底饱和，转为非线性零级蓄积动力学，苏醒延迟数天且产生长效戊巴比妥蓄积。",
        "saferUse": [
            "给药前确认大静脉通路通畅避免强碱外渗或动脉注射；严禁在无人工通气设备上单独非医疗使用",
            "避免用于急性间歇性卟啉病患者（诱发致命性卟啉危象）",
        ],
        "tolerance": {
            "full": "反复给药可出现中枢 GABA 受体下调及药代肝酶自诱导交叉耐受",
            "half": "4 - 7 天",
            "zero": "2 - 3 周",
        },
        "crossTolerances": ["barbiturate", "depressant", "benzodiazepine"],
        "addictionPotential": "高急性致命过量风险与中重度巴比妥类交叉依赖潜力（安全治疗指数极窄）",
        "toxicities": [
            "急性呼吸衰竭、深昏迷、心肌抑制性休克与低体温",
            "动脉误注引发血管血栓坏死；诱发急性卟啉症危象",
        ],
        "categories": ["barbiturate", "depressant", "sedative", "hypnotic", "anticonvulsant", "habit-forming"],
        "categories_zh": ["巴比妥类", "中枢抑制剂", "镇静剂", "催眠药", "抗惊厥药", "易成瘾"],
        "interactions": {
            "dangerous": [
                "Opioids / Benzodiazepines / Alcohol / Propofol (极高致命协同：呼吸中枢麻痹、循环衰竭与深昏迷)",
            ],
            "unsafe": [
                "Anticoagulants / Oral Contraceptives / Corticosteroids (巴比妥类强诱导 CYP 肝酶加速其清除失灵)",
                "Probenecid / Sulfonamides (竞争血浆白蛋白结合，显著升高游离硫喷妥钠致毒浓度)",
            ],
            "uncertain": [
                "Antihypertensives (剧烈放大静脉扩张性低血压)",
            ],
        },
        "roas": [
            {
                "name": "intravenous",
                "dose": {
                    "units": "mg",
                    "lightMin": 100.0,
                    "commonMin": 250.0,
                    "strongMin": 400.0,
                    "heavyMin": 600.0,
                },
                "duration": {
                    "onset": {"min": 15.0, "max": 30.0, "units": "seconds"},
                    "comeup": {"min": 15.0, "max": 45.0, "units": "seconds"},
                    "peak": {"min": 1.0, "max": 5.0, "units": "minutes"},
                    "offset": {"min": 5.0, "max": 15.0, "units": "minutes"},
                    "total": {"min": 10.0, "max": 25.0, "units": "minutes"},
                },
                "bioavailability": {"min": 100.0, "max": 100.0},
            }
        ],
        "pharmacokinetics": pk(
            brand_name="Pentothal",
            half_life="单剂靠快速重分布 5 - 15 分钟苏醒，但真实肝消除半衰期 9 - 12 小时 (高剂量/连续输注脂肪饱和后达 18 - 26h)；活性代谢物戊巴比妥约 15 - 50 小时",
            half_life_hours=11.5,
            t_max="静脉推注后 15 - 45 秒 (~0.02h) 达脑内峰浓度",
            t_max_hours=0.02,
            bioavailability="静脉 100%（强碱性水溶液，不供常规口服）",
            cyp_enzymes=[
                "CYP2C19 (肝氧化侧链代谢参与 / Substrate)",
                "CYP3A4 (肝微粒体氧化及脱硫参与 / Substrate)",
                "CYP3A4 (反复使用后强效肝酶诱导剂 / Strong Inducer)",
            ],
            clearance="肝微粒体氧化生成非活性羧酸衍生物，并有小部分（约 3% - 10%）经肝脏脱硫转化为同属活性巴比妥的戊巴比妥（Pentobarbital）；高负载输注呈现米氏零级饱和消除，代谢物主要经尿排泄",
            pk_role="ACTIVE_METABOLITE",
            effective_half_life_hours=13.0,
            washout_hours=72.0,
            washout_note="单次麻醉苏醒仅需 10 - 20 分钟（全靠重分布），但母体真实半衰期约 11.5 小时且少量脱硫产生的长效活性产物戊巴比妥（半衰期 ~30h）可留存 2 - 5 天。",
            mechanism_summary="高脂溶性硫代巴比妥类 GABAA 受体正向变构调节剂与高浓度直接通道开放剂，兼抑制 AMPA/NMDA 谷氨酸兴奋传导并显着降低脑氧耗。",
            indications=[
                "全身麻醉快速诱导",
                "难治性癫痫持续状态二/三线控制及严重颅高压降脑代谢保护",
            ],
            contraindications=[
                "急性间歇性卟啉病或杂色卟啉病（绝对禁忌）",
                "严重哮喘持续状态、休克及无呼吸支持环境",
            ],
            active_metabolites=[
                met(
                    "Pentobarbital (戊巴比妥)",
                    "母体硫喷妥钠在肝脏经微粒体氧化脱硫（S→O 替换）生成",
                    "15 - 50 小时 (典型 30.0h)",
                    30.0,
                    6.0,
                    0.12,
                    "单剂生成极少，但在高剂量多次叠加或连续抗惊厥泵注时蓄积贡献显著持续镇静",
                    "与母体相同机制的 GABAA 受体巴比妥位点激活剂，解释反复给药后持久“宿醉”与苏醒延长",
                )
            ],
            receptor_targets=[
                target("GABAA 受体 (巴比妥结合位点)", "强效延长氯离子通道平均开放时间并在麻醉浓度直接开放通道", "high", 0.96, "母体主导"),
                target("AMPA / KA 谷氨酸受体", "中等非竞争性抑制兴奋性突触后电流", "moderate", 0.65, "母体主导"),
            ],
        ),
    },
]


# ---------------------------------------------------------------------------
# 2. Enrich existing Substances.json entries that possess verified human PK
# ---------------------------------------------------------------------------
EXISTING_PK_ENRICHMENTS: dict[str, dict[str, Any]] = {
    # ==================== Group 1: Everyday & Stimulants ====================
    "Caffeine": pk(
        half_life="成人典型 2.5 - 4.5 小时 (均约 4.0h；吸烟诱导 CYP1A2 可缩短至 2 - 3h，孕期或联用氟伏沙明/口服避孕药延长至 10 - 24h)",
        half_life_hours=4.0,
        t_max="0.5 - 0.75 小时 (空腹口服约 30 - 45 分钟迅速达峰)",
        t_max_hours=0.65,
        bioavailability="口服 99% (近完全迅速吸收，基本无肝脏首过损失)",
        cyp_enzymes=[
            "CYP1A2 (核心代谢底物 / Substrate；承担约 95% 肝脏 N-脱甲基生成副黄嘌呤等；咖啡因同时是经典的 CYP1A2 探测底物)",
        ],
        clearance="几乎完全经肝 CYP1A2 去甲基化为副黄嘌呤 (Paraxanthine, ~80%)、可可碱 (~12%) 与茶碱 (~4%)；仅 < 3% 以原形经肾排出",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=4.5,
        washout_hours=24.0,
        washout_note="常规代谢者单剂咖啡因约 24 小时基本清除，但主力活性产物副黄嘌呤与母体等强腺苷拮抗且半衰期相近 (3 - 4h)；合用强 CYP1A2 抑制剂氟伏沙明或环丙沙星时半衰期暴增至数十小时引发剧烈心悸失眠。",
        mechanism_summary="高生物利用度腺苷 A2A/A1 受体竞争性拮抗剂，由母体与肝 CYP1A2 生成的三甲基黄嘌呤主力代谢物副黄嘌呤共同维持觉醒与交感兴奋。",
        active_metabolites=[
            met(
                "Paraxanthine (副黄嘌呤)",
                "母体经 CYP1A2 3-N-去甲基化生成 (占总代谢 ~80%)",
                "3.1 - 4.5 小时",
                3.8,
                2.0,
                0.7,
                "稳态下血浆暴露量约达母体 60% - 80%（部分快代谢者甚至高于咖啡因本身）",
                "对腺苷 A1/A2A 受体拮抗亲和力与咖啡因相当甚至更优，贡献显著的中枢觉醒、促脂肪分解与交感兴奋效应",
            ),
            met(
                "Theophylline (茶碱)",
                "母体经 CYP1A2 7-N-去甲基化生成 (占总代谢 ~4%)",
                "6.0 - 9.0 小时",
                7.5,
                3.0,
                0.08,
                "低比例次要代谢产物 (~4% - 8%)",
                "强效支气管扩张及非选择性 PDE/腺苷拮抗产物，高量蓄积时放大心动过速风险",
            ),
        ],
        receptor_targets=[
            target("腺苷 A2A 受体", "竞争性拮抗剂 (Ki ≈ 2.4 - 23 μM，解除腺苷对纹状体 D2 受体的负向刹车并诱导觉醒)", "high", 0.90, "双相共同主导"),
            target("腺苷 A1 受体", "竞争性拮抗剂 (Ki ≈ 12 - 20 μM，促乙酰胆碱、去甲肾上腺素及谷氨酸释放)", "high", 0.85, "双相共同主导"),
            target("非选择性磷酸二酯酶 (PDE)", "高剂量微弱抑制 (cAMP 蓄积)", "low", 0.25, "母体主导"),
        ],
    ),
    "Alcohol": pk(
        half_life="零级饱和消除 (Zero-Order)：常规血乙醇 ≥ 20 mg/dL 时呈恒定速率线性清除约 7 - 10 g/h（血浓度下降约 15 - 20 mg/dL/h）；模型等效名义半衰期约 2.0 小时",
        half_life_hours=2.0,
        t_max="0.5 - 1.5 小时 (空腹口服约 30 - 45 分钟达峰；进食明显减缓胃排空并降低峰浓度)",
        t_max_hours=0.75,
        bioavailability="口服 80% - 90% (胃内首过约 10% - 15%，小肠近端极快吸收；经全身水分分布 Vd ≈ 0.6 - 0.7 L/kg)",
        cyp_enzymes=[
            "CYP2E1 (微粒体乙醇氧化系统 MEOS，慢性大量饮酒呈强诱导 / Inducer；加速对乙酰氨基酚转化为肝毒 NAPQI)",
        ],
        clearance="90% - 95% 经肝胞浆乙醇脱氢酶 (ADH，Km 仅约 0.08 - 0.10 g/L 极易饱和呈零级动力学，Vmax ≈ 7 - 10 g/h) 氧化为乙醛，继而经 ALDH2 转为乙酸；约 2% - 5% 经肺呼气及尿液原形排出",
        pk_role="ZERO_ORDER",
        effective_half_life_hours=2.0,
        washout_hours=12.0,
        washout_note="乙醇是极少数在日常饮用剂量下即彻底饱和肝脏 ADH 酶的物质（典型零级消除模型）：消除速度固定约为每小时 7 - 10 克纯乙醇（约 15 - 20 mg/dL/h），饮入 3 倍剂量几乎需要严格成正比的 3 倍清醒时间！此外与东亚常见 ALDH2*2 基因突变体合用导致毒性乙醛剧烈蓄积，与可卡因同服则在肝酯酶催化下转酯合成半衰期更长且心脏毒性更强的古柯乙烯 (Cocaethylene)。",
        mechanism_summary="高水溶小分子非特异性中枢抑制剂：ADH 低 Km 值导致日常饮酒即呈典型的零级恒速饱和清除动力学，而非传统指数衰减。",
        active_metabolites=[
            met(
                "Acetaldehyde (乙醛)",
                "ADH/CYP2E1 氧化第一中间体，正常经 ALDH2 极速清除",
                "数分钟至 0.5 小时 (ALDH2 突变携带者或双硫仑阻滞下显著蓄积)",
                0.3,
                1.0,
                0.05,
                "正常代谢者血浓度极微；ALDH2 缺陷者剧烈堆积致面部潮红与心动过速",
                "介导潮红、恶心、心悸及长期酒精性器官与 DNA 致癌毒性，无中枢镇静收益",
            )
        ],
        receptor_targets=[
            target("GABA-A 受体 (含 δ 亚基等)", "正向变构增强抑制性氯离子内流 (驱动去抑制、共济失调与呼吸抑制)", "high", 0.90, "母体主导"),
            target("NMDA 谷氨酸受体", "非竞争性阻断/抑制 (削弱兴奋性长时程增强 LTP，高剂量诱发“断片”记忆空白与镇静麻醉)", "high", 0.85, "母体主导"),
            target("甘氨酸 / nACh / 5-HT3 / GIRK 通道", "多靶点变构调控 (协同奖赏多巴胺爆发及呕吐反射)", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Nicotine": pk(
        half_life="母体 1.0 - 2.0 小时 (典型 2.0h)；主力循环代谢产物可替宁 (Cotinine) 半衰期长达 15.0 - 20.0 小时",
        half_life_hours=2.0,
        t_max="吸入仅 1 - 5 分钟极速达峰；口含烟碱袋/口腔含片 20 - 30 分钟；口服胶囊约 1.0 小时",
        t_max_hours=0.5,
        bioavailability="抽吸/雾化吸入 10% - 35%（肺泡快速入脑）；口腔黏膜 20% - 50%；口服受肝脏首过代谢仅 20% - 30%",
        cyp_enzymes=[
            "CYP2A6 (绝对主力代谢底物 / Substrate；承担约 70% - 80% 转化为可替宁与 3-羟可替宁)",
            "CYP2B6 (次要 N-脱甲基途径底物 / Substrate)",
        ],
        clearance="主要经肝脏 CYP2A6 与醛氧化酶连续转化为可替宁 (Cotinine)，另经 FMO3 氧化与 UGT2B10 葡糖醛酸化；约 5% - 10% 原形随尿排出（酸性尿促进肾清除）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.0,
        washout_hours=12.0,
        washout_note="尼古丁母体半衰期仅约 2 小时，解释为何成瘾者数小时未抽即出现明显戒断渴求；下游主要定量暴露标志物可替宁半衰期 16 - 20 小时（活性极弱，仅作生物监测）。注意：烟草烟雾中的多环芳烃会强烈诱导 CYP1A2（非尼古丁本身所致），骤然戒烟或改用纯电子烟后咖啡因/奥氮平/氯氮平/度洛西汀血浓度会明显回升！",
        mechanism_summary="高脂溶性吡啶生物碱型烟碱乙酰胆碱受体 (nAChR) 激动剂，极短的母体半衰期赋予其极高频强化成瘾周期。",
        active_metabolites=[],
        receptor_targets=[
            target("α4β2 烟碱型乙酰胆碱受体 (nAChR)", "高亲和力激动剂与快速脱敏剂 (Ki ≈ 1 - 10 nM，刺激伏隔核释放多巴胺并提高专注)", "high", 0.98, "母体主导"),
            target("α7 / α3β4 nAChR", "激动剂 (调节谷氨酸释放与自主神经交感心率血压反应)", "moderate", 0.72, "母体主导"),
        ],
    ),
    "Theanine": pk(
        half_life="约 1.08 小时 (人类临床实测约 55 - 74 分钟，血浆呈线性和快速清除)",
        half_life_hours=1.1,
        t_max="0.75 - 1.0 小时 (人类口服约 48 - 50 分钟达峰)",
        t_max_hours=0.8,
        bioavailability="口服经肠道氨基酸主动吸收迅速且近完全进入循环，可通过 LAT 转运跨越血脑屏障",
        cyp_enzymes=[
            "非细胞色素酶代谢途径 (主要经肾脏磷酸盐非依赖性谷氨酰胺酶水解为谷氨酸与乙胺)",
        ],
        clearance="主要经肾脏水解转化为谷氨酸及乙胺（约 24 小时内部分以乙胺形式随尿排出），不依赖肝脏 CYP450 系统",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=1.1,
        washout_hours=6.0,
        washout_note="人体口服消除极为迅速，半衰期仅约 1 小时左右，6 小时内完全清除无蓄积；与咖啡因联用不发生 CYP 代谢竞争，单纯在神经突触层面协同缓冲过度交感焦虑。",
        mechanism_summary="天然谷氨酰胺类似物型非蛋白氨基酸，口服快吸快清，调节抑制性/兴奋性突触张力并提升皮层 α 脑波。",
        active_metabolites=[],
        receptor_targets=[
            target("AMPA / 红藻氨酸 / NMDA 谷氨酸受体", "弱竞争性拮抗调节 (抑制过量谷氨酸兴奋性，缓解咖啡因伴随的心悸焦虑)", "moderate", 0.65, "母体主导"),
            target("神经胶质细胞谷氨酰胺转运体", "抑制谷氨酰胺回收，间接温和支持 GABA 合成池", "moderate", 0.60, "母体主导"),
        ],
    ),
    "Melatonin": pk(
        half_life="约 0.75 小时 (常释口服实测 30 - 50 分钟)",
        half_life_hours=0.75,
        t_max="0.33 - 0.83 小时 (常释口服约 20 - 50 分钟达峰)",
        t_max_hours=0.6,
        bioavailability="口服平均约 15% (个体差异极宽 9% - 33%，受显著肝脏首过清除限制)",
        cyp_enzymes=[
            "CYP1A2 (核心代谢底物 / Substrate；承担约 90% 肝脏 6-羟基化清除，氟伏沙明可使浓度暴增 10 倍以上)",
            "CYP2C19 (极次要脱甲基途径 / Substrate)",
        ],
        clearance="主要经肝 CYP1A2 快速 6-羟化并迅速硫酸/葡萄糖醛酸结合生成 6-硫酸羟基褪黑素 (aMT6s) 经肾排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=0.75,
        washout_hours=4.0,
        washout_note="外源性常释褪黑素自身半衰期不足 1 小时，一般 4 小时内即全数清除（因此仅助入睡而对后半夜早醒维持有限）；但与氟伏沙明（强效 CYP1A2 抑制剂）联用时母体暴露量激增十倍以上引发严重次日昏沉。",
        mechanism_summary="内源性松果体节律激素原形，短半衰期脉冲式触发视交叉上核 MT1/MT2 受体信号开关。",
        active_metabolites=[],
        receptor_targets=[
            target("MT1 褪黑素受体", "高亲和力内源完全激动剂 (Ki ≈ 0.08 nM，诱导入睡信号)", "high", 0.96, "母体主导"),
            target("MT2 褪黑素受体", "高亲和力内源完全激动剂 (Ki ≈ 0.38 nM，重设昼夜节律时相)", "high", 0.92, "母体主导"),
        ],
    ),
    "N-Acetylcysteine": pk(
        half_life="约 5.6 小时 (健康成人实测分布与终末范围约 2.2 - 6.2 小时)",
        half_life_hours=5.6,
        t_max="1.0 - 2.0 小时 (口服常释溶液或泡腾片迅速达峰)",
        t_max_hours=1.5,
        bioavailability="口服游离体绝对生物利用度仅 4% - 10% (剧烈肠道与肝脏首过脱乙酰为半胱氨酸并参与二硫键蛋白结合)",
        cyp_enzymes=[
            "非细胞色素酶代谢途径 (迅速脱乙酰化生成 L-半胱氨酸，进入谷胱甘肽 GSH 合成通路及硫酸盐终末排泄)",
        ],
        clearance="经肝脏氨基酸酰化酶广泛脱乙酰为半胱氨酸并补充胞内还原型谷胱甘肽；仅约 20% - 30% 经尿排出，70% 为非肾途径同化清除",
        pk_role="PRODRUG",
        effective_half_life_hours=5.6,
        washout_hours=28.0,
        washout_note="口服绝大多数 N-乙酰半胱氨酸在进入体循环前即脱乙酰为半胱氨酸供给谷胱甘肽合成（是对乙酰氨基酚中毒特效解毒剂与戒瘾辅助热选），常规半衰期约 5.6 小时，约 28 小时基本洗脱。",
        mechanism_summary="谷胱甘肽合成直接半胱氨酸前体，通过补充胞内 GSH 并激活突触前 xCT 谷氨酸逆向转运体恢复谷氨酸稳态。",
        active_metabolites=[
            met(
                "L-Cysteine / Glutathione (L-半胱氨酸及胞内谷胱甘肽池)",
                "肠壁与肝脏氨基酸酰化酶脱乙酰直接生成并补足 GSH 合成限速底物",
                "约 5.6 小时 (游离半胱氨酸快速转化为蛋白结合及胞内 GSH 储存)",
                5.6,
                2.0,
                1.0,
                "体内主要活性形式（游离原形仅占 4% - 10%）",
                "中和肝中毒 NAPQI 自由基代谢物，同时调节腹侧纹状体 xCT 系统降低药物渴求与氧化应激",
            )
        ],
        receptor_targets=[
            target("系统 xc- 胱氨酸-谷氨酸逆向转运体 (xCT)", "提供胱氨酸刺激非囊泡谷氨酸释放进而激活突触前 mGluR2/3 自受体抑制冲动复吸", "high", 0.88, "代谢物主导"),
            target("细胞内谷胱甘肽 (GSH) 抗氧化系统", "直接还原解毒供体 (抢救对乙酰氨基酚肝衰竭)", "high", 0.95, "代谢物主导"),
        ],
    ),
    "Amphetamine": pk(
        half_life="约 10.5 小时 (d-异构体 9 - 11h，l-异构体 10 - 13h；尿液 pH 极敏感：偏酸缩短至 7h，偏碱延长至 30h+)",
        half_life_hours=10.5,
        t_max="1.0 - 3.0 小时 (常释口服；混合盐缓释剂型呈现 7 小时双峰平台)",
        t_max_hours=2.0,
        bioavailability="口服 > 75% - 90% (高吸收率且首过代谢较少)",
        cyp_enzymes=[
            "CYP2D6 (重要芳香 4-羟化底物 / Substrate；慢代谢或合用强抑制剂显著升高血药暴露)",
        ],
        clearance="同时经肝脏 CYP2D6 羟化、单胺氧化酶/多巴胺 β-羟化酶氧化及大量肾脏原形排泄（正常尿 pH 下约 30% - 40% 原形排泄，碱化尿液大幅延长滞留）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=10.5,
        washout_hours=60.0,
        washout_note="苯丙胺消除极具尿液 pH 依赖性（高维生素 C 酸化尿液加速排出，抗酸剂/碳酸氢钠剧烈延缓消除至 20 - 34 小时以上）；普通人群 60 小时可清除绝大多数，但切忌 14 天内合用 MAOI 防止致命高血压危象。",
        mechanism_summary="经典苯乙胺类中枢神经兴奋剂：母体直接作为 TAAR1 激动剂与 VMAT2 逆转底物，引发胞质及突触间隙大量多巴胺与去甲肾上腺素非囊泡外排。",
        active_metabolites=[],
        receptor_targets=[
            target("TAAR1 (痕量胺相关受体 1)", "强效激动剂 (诱导 DAT/NET 磷酸化并逆向泵出多巴胺与去甲肾上腺素)", "high", 0.96, "母体主导"),
            target("DAT / NET (单胺转运体)", "强效竞争性底物型释放剂 (Vmax 逆转，多巴胺能与去甲肾上腺素能激增)", "high", 0.98, "母体主导"),
            target("VMAT2 (囊泡单胺转运体 2)", "强效抑制并倒空突触囊泡内单胺进入胞质池", "high", 0.92, "母体主导"),
        ],
    ),
    "Dextroamphetamine": pk(
        half_life="9.0 - 11.0 小时 (典型 10.0h；尿液碱性状态下明显延长)",
        half_life_hours=10.0,
        t_max="1.0 - 3.0 小时 (常释口服；前药赖右苯丙胺缓释另行延后)",
        t_max_hours=2.0,
        bioavailability="口服约 75% - 90% (吸收迅速完全)",
        cyp_enzymes=[
            "CYP2D6 (芳香环羟化主要代谢底物 / Substrate)",
        ],
        clearance="部分经 CYP2D6 4-羟化及肝脏脱氨基氧化，约 30% - 45% 经肾原形排泄（强烈受尿液酸碱度调控）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=10.0,
        washout_hours=55.0,
        washout_note="右旋苯丙胺为苯丙胺中枢活性更强、多巴胺选择性更高的右旋对映体（半衰期略短于左旋体），约 55 小时完成血浆洗脱；合用 CYP2D6 抑制剂或碱化尿液均会延缓清除并放大心血管负荷。",
        mechanism_summary="苯丙胺单一右旋对映体：对中枢多巴胺能的激活效价约为左旋体的 3 - 4 倍，外周纯交感震颤副作用略轻。",
        active_metabolites=[],
        receptor_targets=[
            target("TAAR1 / DAT (多巴胺转运体)", "高亲和力逆向释放底物 (中枢强效促觉醒与专注核心驱动)", "high", 0.98, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "强效逆向释放底物 (介导觉醒提升与心血管升压心悸)", "high", 0.95, "母体主导"),
            target("VMAT2", "强效抑制并促胞内释放", "high", 0.90, "母体主导"),
        ],
    ),
    "Methamphetamine": pk(
        half_life="约 10.5 小时 (典型 9 - 12h；尿 pH 酸性 5h 至碱性 30h 剧烈波动)",
        half_life_hours=10.5,
        t_max="口服 2.5 - 3.5 小时 (典型 3.0h)；抽吸/静注仅数分钟极速冲击中枢",
        t_max_hours=3.0,
        bioavailability="口服约 67%；鼻吸约 79%；抽吸约 67% - 90%；静注 100%",
        cyp_enzymes=[
            "CYP2D6 (主要代谢底物 / Substrate；负责 N-去甲基转化为活性苯丙胺及芳香羟化)",
        ],
        clearance="约 30% - 54% 原形经肾排出（pH 敏感）；约 10% - 20% 经 CYP2D6 脱甲基转化为活性产物苯丙胺；其余羟化后结合排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=11.0,
        washout_hours=60.0,
        washout_note="甲基苯丙胺高脂溶性极易跨过血脑屏障，半衰期约 10 - 12 小时并额外经 CYP2D6 生成 10h 级活性产物苯丙胺，整体洗脱窗口可达 60 小时以上（碱性尿进一步延长至数日）；长期连续使用引发多巴胺耗竭与重度神经毒性。",
        mechanism_summary="高脂溶性 N-甲基化苯丙胺：母体原形主导剧烈多巴胺/去甲肾上腺素及一定程度 5-HT 非囊泡倾泻，下游活性代谢产物苯丙胺接力维持尾效。",
        active_metabolites=[
            met(
                "Amphetamine (苯丙胺)",
                "母体经肝 CYP2D6 N-脱甲基化生成 (约占摄入剂量 10% - 15%)",
                "9.0 - 13.0 小时 (典型 10.5h)",
                10.5,
                6.0,
                0.20,
                "血浆浓度约为母体 15% - 25%（代谢后段占比略升）",
                "保留全部苯乙胺类促醒及交感增效活性，但中枢透脑效率略低于甲基苯丙胺母体",
            )
        ],
        receptor_targets=[
            target("TAAR1 / DAT / VMAT2", "超强效释放与再摄取倒转 (高脂溶性快速饱和中枢奖赏通路)", "high", 0.99, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "极强效释放剂 (致显著血管收缩、心律失常与高热危象)", "high", 0.96, "母体主导"),
            target("SERT (5-羟色胺转运体)", "中等释放活性 (显著强于普通苯丙胺)", "moderate", 0.68, "母体主导"),
        ],
    ),
    "MDMA": pk(
        half_life="7.0 - 8.0 小时 (典型 7.5h；随剂量非线性延长——自我机制性抑制 CYP2D6 导致补服时浓度骤升！)",
        half_life_hours=7.5,
        t_max="1.5 - 3.0 小时 (典型 2.0h)",
        t_max_hours=2.0,
        bioavailability="口服约 88% - 90% (高吸收率；高剂量因 CYP2D6 自抑制呈现剧烈非线性暴露陡升)",
        cyp_enzymes=[
            "CYP2D6 (核心代谢底物 / Substrate；负责 O-去亚甲基化为 HHMA)",
            "CYP2D6 (机制性不可逆强抑制剂 / Strong Inhibitor；单剂 100 mg 后数小时内即可使 CYP2D6 活性锐减 80% - 90% 并持续数日！)",
            "CYP3A4 / CYP1A2 / CYP2B6 (次要 N-脱甲基生成 MDA 底物 / Substrate)",
        ],
        clearance="主要经 CYP2D6 O-去亚甲基化（随后经 COMT 甲基化为 HMMA），少部分经 CYP3A4/CYP1A2 N-脱甲基转化为活性 MDA；MDMA 与 MDA 均呈非线性蓄积",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=11.5,
        washout_hours=72.0,
        washout_note="MDMA 自身是极强效且持久的机制性（自杀性）CYP2D6 抑制剂：首次服药后 CYP2D6 酶需要约 5 - 10 天重新合成恢复！不仅导致补服（Redose）呈现危险的非线性浓度飙升，也会在之后数天内显著抬高其他 CYP2D6 底物药物（如右美沙芬、可待因受阻、美托洛尔、氟西汀等）中毒风险，且活性产物 MDA 半衰期长达 11 - 12 小时。",
        mechanism_summary="非线性自抑制型共情致幻苯丙胺：母体强力逆转 SERT/NET/DAT 释放大量血清素与催产素，并同时不可逆灭活 CYP2D6，下游产生更偏幻觉且长效的活性代谢物 MDA。",
        active_metabolites=[
            met(
                "MDA (3,4-亚甲二氧基苯丙胺)",
                "母体经 CYP3A4 / CYP1A2 / CYP2B6 N-脱甲基化生成 (约占循环暴露 10% - 15%)",
                "10.5 - 12.5 小时 (典型 11.5h)",
                11.5,
                4.0,
                0.15,
                "约为母体血浆暴露量的 10% - 20%（CYP2D6 自抑制后生成比例明显升高）",
                "对 5-HT2A 受体激动活性明显强于 MDMA 母体，驱动体验后半段致幻视效与持续神经过度兴奋及神经毒性",
            )
        ],
        receptor_targets=[
            target("SERT (5-羟色胺转运体)", "极强效底物型血清素倾泻剂 (极高危忌与 MAOI/SSRI/曲马多联用诱发致命 5-HT 综合征)", "high", 0.99, "双相共同主导"),
            target("NET / DAT (单胺转运体)", "强效去甲肾上腺素及中等程度多巴胺释放 (高血压与心搏过速风险)", "high", 0.92, "双相共同主导"),
            target("VMAT2 / 5-HT2B / 5-HT2A", "中等激动与囊泡释放 (其中 MDA 增强 5-HT2A 致幻效应)", "moderate", 0.72, "代谢物增益"),
        ],
    ),
    "MDA": pk(
        half_life="10.5 - 12.5 小时 (典型 11.5h)",
        half_life_hours=11.5,
        t_max="1.5 - 3.0 小时 (典型 2.0h)",
        t_max_hours=2.0,
        bioavailability="口服吸收良好 (临床定量数据参考受控受试者研究，显著高出多数低级代用品)",
        cyp_enzymes=[
            "CYP2D6 (主要 O-去亚甲基化清除底物 / Substrate)",
            "CYP3A4 (次要 N-羟化/氧化底物 / Substrate)",
        ],
        clearance="经肝脏 CYP2D6（以及部分氧化途径）清除并随后经 COMT 甲基化及葡萄糖醛酸/硫酸盐结合，部分原形随尿排泄",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=11.5,
        washout_hours=60.0,
        washout_note="MDA 半衰期（约 11.5 小时）显著长于 MDMA，约为 60 小时清除，具备更高的单胺氧化应激神经毒性及显著的 5-HT2A 致幻与高体温协同风险。",
        mechanism_summary="MDMA 的去甲基活性母体同系物：兼具剧烈血清素/去甲肾上腺素释放与更直接的 5-HT2A 激动幻觉活性。",
        active_metabolites=[],
        receptor_targets=[
            target("SERT / NET / DAT", "强效单胺释放剂 (倾泻 5-HT/NE/DA，严防 5-HT 综合征与高热危象)", "high", 0.98, "母体主导"),
            target("5-HT2A / 5-HT2B 受体", "较 MDMA 更强效的直接激动剂 (带来鲜明几何视幻觉与高神经毒性压力)", "high", 0.85, "母体主导"),
        ],
    ),
    "Cocaine": pk(
        half_life="约 1.0 小时 (范围 0.75 - 1.5h；静注与抽吸亦维持约 1 小时级血浆清除，但活性产物古柯乙烯半衰期翻倍以上)",
        half_life_hours=1.0,
        t_max="鼻吸约 30 - 60 分钟 (0.6h)；静脉注射与烟吸裂解可卡因仅 1 - 5 分钟极速达峰",
        t_max_hours=0.6,
        bioavailability="鼻吸 20% - 30%（局部强血管收缩自限吸收）；抽吸 60% - 70%；口服约 30% - 33%；静注 100%",
        cyp_enzymes=[
            "血浆丁酰胆碱酯酶 BChE 与肝羧酸酯酶 hCE-1/hCE-2 (主途径，水解为无中枢活性的苯甲酰爱康宁)",
            "CYP3A4 (次要 N-去甲基途径底物 / Substrate；转化为高心脏毒性产物去甲可卡因)",
        ],
        clearance="主要经血浆及肝脏酯酶极其迅速水解为无显著兴奋活性的苯甲酰爱康宁 (BE) 与爱康宁甲酯 (EME)；仅 1% - 9% 原形经肾排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=1.0,
        washout_hours=8.0,
        washout_note="可卡因母体血浆半衰期仅约 1 小时（解释其极高频强迫补服特征，约 6 - 8 小时母体清零，但尿检苯甲酰爱康宁可留存 2 - 4 天）。特别警示：一旦与乙醇（酒精）同服，肝羧酸酯酶 hCE-1 会发生转酯反应合成“古柯乙烯 (Cocaethylene)”——半衰期延长 2 - 3 倍且突发性猝死/冠脉痉挛风险飙升 10 倍以上！",
        mechanism_summary="高脂溶性短半衰期三通道单胺再摄取阻滞剂兼电压门控钠通道阻断剂：瞬时阻塞 DAT/SERT/NET 带来爆发奖赏但伴剧烈心脏传导及血管痉挛风险。",
        active_metabolites=[
            met(
                "Norcocaine / Cocaethylene (去甲可卡因；仅与酒精联用时剧增古柯乙烯)",
                "正常经 CYP3A4 生成微量去甲可卡因；联用乙醇时经 hCE-1 转酯合成古柯乙烯",
                "去甲可卡因约 1.5h；古柯乙烯延长至 2.5 - 4.0h",
                2.5,
                1.5,
                0.05,
                "常规微量 (< 5%)；但联用乙醇时古柯乙烯循环暴露大幅飙升",
                "高心脏电生理毒性及更长半衰期的多巴胺阻滞协同危险产物",
            )
        ],
        receptor_targets=[
            target("DAT (多巴胺转运体)", "强效再摄取抑制剂 (Ki ≈ 200 - 400 nM，快上快下强强化机制核心)", "high", 0.98, "母体主导"),
            target("SERT / NET (5-HT 与 NE 转运体)", "均衡强效再摄取抑制剂 (强血管收缩、血压骤升及冠脉梗死诱因)", "high", 0.95, "母体主导"),
            target("心脏及神经电压门控钠通道 (Nav)", "局部麻醉性阻滞 (诱发 QRS 增宽、室性心律失常与局麻抽搐)", "moderate", 0.78, "母体主导"),
        ],
    ),
    "Ephedrine": pk(
        half_life="3.0 - 6.0 小时 (典型 4.5h；尿液碱性可明显延长至 7h+)",
        half_life_hours=4.5,
        t_max="1.0 - 2.5 小时 (典型 1.8h)",
        t_max_hours=1.8,
        bioavailability="口服 85% - 100% (迅速且近完全吸收)",
        cyp_enzymes=[
            "极少经 CYP450 代谢 (< 10% 经氧化脱氨基或去甲基，少量转为去甲麻黄碱)",
        ],
        clearance="70% - 80% 以上以未变化原形直接经肾脏随尿排出（清除率受尿液酸碱度高度影响，酸化加速排泄）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=4.5,
        washout_hours=24.0,
        washout_note="麻黄碱口服利用度极高且以原形肾排出为主，常规约 24 小时洗脱完毕；切忌与 MAOI、可卡因、大剂量咖啡因或其他交感兴奋剂叠加使用，易诱发严重高血压与室上性心动过速。",
        mechanism_summary="混合型直接与间接拟交感胺（既促进突触去甲肾上腺素释放，亦直接微弱激动 α/β 肾上腺素受体），母体原形主导作用。",
        active_metabolites=[],
        receptor_targets=[
            target("NET (去甲肾上腺素转运体 / 囊泡释放)", "强效间接促进内源性去甲肾上腺素外排", "high", 0.92, "母体主导"),
            target("β1 / β2 / α1 肾上腺素受体", "直接轻至中度非选择性激动 (支气管扩张、升压、发汗与心率加快)", "high", 0.88, "母体主导"),
        ],
    ),
    "Adrafinil": pk(
        half_life="母体仅约 1.0 小时；经肝脏转化的唯一主导活性药物莫达非尼 (Modafinil) 半衰期长达 12.0 - 15.0 小时",
        half_life_hours=1.0,
        t_max="母体约 1.0 小时；体内活性莫达非尼约 2.0 - 4.0 小时达峰 (典型 3.0h)",
        t_max_hours=1.0,
        bioavailability="口服吸收较佳，但须经肝脏首过脱羟胺氧化（整体转化率低于等摩尔直服莫达非尼，且增加肝脏负荷）",
        cyp_enzymes=[
            "肝酰胺酶 / 未知氧化代谢转化前药为莫达非尼；下游莫达非尼经 CYP3A4 清除且具 CYP2C19 抑制 / CYP3A4 诱导潜力 (Inducer / Inhibitor)",
        ],
        clearance="母体在 1 - 2 小时内几近全部代谢生成莫达非尼（以及无活性的莫达非尼酸），最终经肝肾缓慢清除",
        pk_role="PRODRUG",
        effective_half_life_hours=13.5,
        washout_hours=72.0,
        washout_note="阿屈非尼是典型的无直接受体活性前药：母体 1 小时内消失，真正在随后 24 - 72 小时维持强力促醒以及与激素避孕药（莫达非尼诱导 CYP3A4！）、CYP2C19 底物交互的全部来自活性产物莫达非尼。长期服用需监测肝酶（ALT/AST）。",
        mechanism_summary="莫达非尼的羟胺前药（自身无中枢受体活性）：全部药理醒脑效果及长半衰期均通过体内转化为莫达非尼呈现。",
        active_metabolites=[
            met(
                "Modafinil (莫达非尼)",
                "肝脏首过氧化脱去 N-羟胺基团生成（绝对唯一活性产物）",
                "12.0 - 15.0 小时 (典型 13.5h)",
                13.5,
                3.0,
                1.0,
                "完全承担阿屈非尼在体内的全部有效血药与中枢暴露",
                "弱而选择性抑制多巴胺转运体 DAT 并增强下丘脑食欲素与组胺促醒系统；长效引发 CYP3A4 诱导（降低避孕药药效）与 CYP2C19 抑制",
            )
        ],
        receptor_targets=[
            target("DAT (多巴胺转运体)", "通过转化后的莫达非尼发挥中等选择性再摄取抑制 (Ki ≈ 2 - 4 μM，显著促觉醒)", "high", 0.90, "代谢物主导"),
            target("下丘脑食欲素 / 结节乳头体组胺通路", "间接增强促进觉醒信号传导", "moderate", 0.72, "代谢物主导"),
        ],
    ),
    "pseudo": pk(
        half_life="5.0 - 8.0 小时 (尿液 pH 5.5 - 6.5 时仅约 3.0 - 6.0 小时；偏碱尿液大幅延长至 9 - 16 小时)",
        half_life_hours=5.5,
        t_max="1.5 - 2.0 小时 (常释口服；缓释片约 4 - 6 小时)",
        t_max_hours=1.8,
        bioavailability="口服近 100% (吸收迅速且不受首过明显损失)",
        cyp_enzymes=[
            "极少经肝脏氧化代谢 (< 1% 脱甲基为去甲伪麻黄碱)",
        ],
        clearance="70% - 90% 以未变化原形直接经肾随尿排出，极度受尿液酸碱度波动调控",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=5.5,
        washout_hours=30.0,
        washout_note="伪麻黄碱在酸碱平衡正常成人中约 24 - 36 小时清除，但碱性尿（碳酸氢钠、素食碱化负荷）可使半衰期成倍延长并加重失眠、心率加快与血压攀升；绝禁联用 MAOI！",
        mechanism_summary="麻黄碱的立体非对映异构体：较麻黄碱更偏向外周 α1 血管收缩缓解鼻塞，中枢兴奋穿透相对更弱，母体原形主导排泄。",
        active_metabolites=[],
        receptor_targets=[
            target("NET (间接去甲肾上腺素释放)", "促突触去甲肾上腺素外排，收缩鼻黏膜血管", "high", 0.90, "母体主导"),
            target("α1 / β 肾上腺素能受体", "轻中度直接激动（收缩外周血管并引起轻度心动过速与警觉）", "moderate", 0.72, "母体主导"),
        ],
    ),
    "Theacrine": pk(
        half_life="16.0 - 26.0 小时 (人类临床试验典型约 20.0 小时，显著长于咖啡因)",
        half_life_hours=20.0,
        t_max="1.0 - 2.0 小时 (典型 1.5h；合用咖啡因略后移且口服暴露 AUC 显着提高)",
        t_max_hours=1.5,
        bioavailability="口服吸收良好；人类对照研究显示合用咖啡因可使茶碱酸/苦茶碱的血浆 Cmax 与生物利用度明显升高而不影响其半衰期",
        cyp_enzymes=[
            "主要经黄嘌呤/嘌呤氧化酶及肝氧化通路缓慢脱甲基清除（不改变咖啡因自身清除动力学）",
        ],
        clearance="人类清除极缓慢，半衰期长达 20 小时级，代谢后随尿排泄",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=20.0,
        washout_hours=100.0,
        washout_note="苦茶碱（Theacrine）的最大临床动力学特点是长达 ~20 小时的长半衰期（约为咖啡因的 5 倍），连续每日服用数日即呈蓄积并须约 4 天完全洗脱；虽主观交感震颤低于咖啡因，午后服用仍极易破坏深睡眠。",
        mechanism_summary="长半衰期天然三甲基尿酸生物碱，温和竞争性拮抗腺苷受体兼多巴胺能调节，受母体长期稳态支配。",
        active_metabolites=[],
        receptor_targets=[
            target("腺苷 A2A / A1 受体", "温和竞争性拮抗调节 (较咖啡因耐受发展更缓和但作用时间更长)", "moderate", 0.75, "母体主导"),
        ],
    ),
    "Mephedrone": pk(
        half_life="约 2.2 小时 (人类受控临床研究实测范围 2.0 - 2.5 小时)",
        half_life_hours=2.2,
        t_max="1.0 - 1.25 小时 (人类口服 150 - 200 mg 受控临床试测)",
        t_max_hours=1.2,
        bioavailability="口服迅速吸收（临床受控试验证实较 MDMA 爬坡更快、峰值更高且迅速回落；首过代谢较显着，鼻吸吸收更快更强烈）",
        cyp_enzymes=[
            "CYP2D6 (核心代谢底物 / Substrate；承担 N-脱甲基化及苄基甲基羟化转化为去甲甲氧麻黄酮等)",
        ],
        clearance="主要经 CYP2D6 脱甲基转化为去甲甲氧麻黄酮 (Normephedrone) 与 4-羧基甲氧麻黄酮等随尿液排泄（仅极少原形排出）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.2,
        washout_hours=12.0,
        washout_note="甲氧麻黄酮 (4-MMC) 仅约 2.2 小时的超短血浆半衰期使其药效极速冲峰又断崖回落，导致现实中极强烈的强迫高频连续补服 (Compulsive Redosing) 与心血管极度过载风险！切忌合用 MAOI 或其他血清素药物。",
        mechanism_summary="短半衰期强效共情兴奋性合成卡西酮：同时强烈逆转 DAT/SERT/NET 产生短暂合并可卡因样冲动与 MDMA 样亲和峰值。",
        active_metabolites=[],
        receptor_targets=[
            target("DAT / NET (单胺转运体)", "极强效非囊泡多巴胺及去甲肾上腺素释放 (诱发严重血管收缩、心动过速与强迫追服)", "high", 0.98, "母体主导"),
            target("SERT (5-羟色胺转运体)", "强效血清素释放剂 (合用 5-HT 能药物致致命血清素综合征)", "high", 0.94, "母体主导"),
        ],
    ),
    "Methylone": pk(
        half_life="5.8 - 6.9 小时 (人类受控临床试验实测均值约 6.3 小时)",
        half_life_hours=6.3,
        t_max="1.5 - 2.0 小时 (人类受控口服试验达峰时间)",
        t_max_hours=1.8,
        bioavailability="口服吸收良好并展现接近 MDMA 的人类临床暴露增长曲线（代谢与消除明显快于 MDA）",
        cyp_enzymes=[
            "CYP2D6 (核心 O-去亚甲基化底物 / Substrate；部分可受机制性抑制或遗传多态性影响)",
            "CYP1A2 / CYP2B6 / CYP2C19 (次要 N-脱甲基化生成去甲亚甲酮 MDC 底物 / Substrate)",
        ],
        clearance="经肝 CYP2D6 转化为羟基甲氧基甲卡西酮 (HMMC，主要经葡萄糖醛酸/硫酸盐结合尿排) 及经 N-脱甲基生成 MDC",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=6.3,
        washout_hours=32.0,
        washout_note="亚甲酮半衰期约 6.3 小时（明显长于甲氧麻黄酮而略短于 MDMA），约 32 小时清零；同样严禁与 MAOI 或 SSRI/SNRI/曲马多合用引发急性高热及血清素综合征。",
        mechanism_summary="MDMA 的 β-酮基卡西酮类似物（bk-MDMA）：母体原形主导强力 SERT/NET/DAT 释放，神经毒性压力与体温冲击仍不容忽视。",
        active_metabolites=[],
        receptor_targets=[
            target("SERT (5-羟色胺转运体)", "强效血清素释放剂 (驱动共情亲和情绪，严防 5-HT 综合征)", "high", 0.94, "母体主导"),
            target("NET / DAT (单胺转运体)", "强效去甲肾上腺素及多巴胺释放 (高血压与心搏过速风险)", "high", 0.92, "母体主导"),
        ],
    ),
    "4-FA": pk(
        half_life="约 8.0 - 9.0 小时 (人类受控试验整体均值 ~8.5 - 9h，呈手性消除差异：R-对映体 ~12.9h，S-对映体 ~6.0h)",
        half_life_hours=9.0,
        t_max="约 2.0 小时 (人类 100/150 mg 口服受控研究中位 1.5 - 3.0h)",
        t_max_hours=2.0,
        bioavailability="口服吸收良好，临床剂量 100 - 150 mg 呈近似线性暴露增长",
        cyp_enzymes=[
            "对位氟原子封锁了经典芳香 4-羟化失活位点，致使代谢显著放缓并大部分依赖原形肾排泄",
        ],
        clearance="因苯环对位氟取代阻断肝脏对位羟化，大量以未变化原形缓慢经肾排出，伴少量脱氨基代谢产物",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=9.0,
        washout_hours=48.0,
        washout_note="4-氟苯丙胺半衰期长达约 9 小时（R-异构体近 13h），约 48 小时完成主要洗脱；临床案例高度警示高剂量极易出现极度升压效应及脑出血/可逆性脑血管收缩综合征风险，严禁联用 MAOI 或其他升压兴奋剂。",
        mechanism_summary="对位氟取代共情苯丙胺：前 3 小时体现明显 5-HT 共情放缓与兴奋，后 6 - 12 小时转为纯苯丙胺样残余失眠与血管加压。",
        active_metabolites=[],
        receptor_targets=[
            target("DAT / NET (单胺转运体)", "强效逆向释放底物 (极强升压作用与长时间兴奋尾效)", "high", 0.95, "母体主导"),
            target("SERT (5-羟色胺转运体)", "中等至强效释放 (早期共情效应核心，须防 5-HT 综合征)", "moderate", 0.78, "母体主导"),
        ],
    ),
    "MCPP": pk(
        half_life="典型 4.0 - 6.8 小时 (人类临床口服或静注研究个体差异较宽约 2.6 - 14.0 小时)",
        half_life_hours=5.0,
        t_max="1.0 - 2.0 小时 (直接口服胶囊/溶液；若为曲唑酮经 CYP3A4 生成则滞后至数小时)",
        t_max_hours=1.5,
        bioavailability="口服约 14% - 77% (个体间经 CYP2D6 首过清除差异极大，慢代谢者暴露显著偏高)",
        cyp_enzymes=[
            "CYP2D6 (核心羟化失活代谢底物 / Substrate；慢代谢或合用氟西汀/帕罗西汀致血药浓度暴增)",
        ],
        clearance="主要经肝脏 CYP2D6 对苯环羟化转化为对羟基 mCPP 并迅速结合排泄（亦是曲唑酮、奈法唑酮经 CYP3A4 裂解生成的知名致焦虑副产物）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=5.0,
        washout_hours=30.0,
        washout_note="mCPP 直接口服半衰期约 5 小时（约 30 小时洗脱）；因为依赖 CYP2D6 清除，慢代谢者或联用 CYP2D6 抑制剂时暴露翻倍且强烈诱发头痛、恶心、震颤、恐慌发作与类血清素反应。",
        mechanism_summary="非选择性血清素受体激动剂及弱血清素释放剂（经典 5-HT2C 强激动诱发焦虑与头痛的模型分子）。",
        active_metabolites=[],
        receptor_targets=[
            target("5-HT2C 受体", "强效激动剂 (Ki ≈ 10 - 20 nM，主导恶心、焦虑、食欲抑制与 ACTH/皮质醇升高的核心靶点)", "high", 0.96, "母体主导"),
            target("5-HT1B / 5-HT2B / 5-HT2A 受体", "部分激动/激动调节 (伴发血管性偏头痛样症状)", "moderate", 0.75, "母体主导"),
            target("SERT", "弱至中等 5-HT 释放及再摄取抑制", "moderate", 0.65, "母体主导"),
        ],
    ),

    # ==================== Group 2: Opioids & Antagonists ====================
    "Morphine": pk(
        half_life="母体 2.0 - 3.0 小时 (典型 2.5h)；活性代谢物 M6G 约 3.0 - 5.0 小时 (典型 4.0h；肾功能不全时极度蓄积致迟发性呼吸抑制！)",
        half_life_hours=2.5,
        t_max="0.5 - 1.0 小时 (口服常释；缓释片约 3.0 - 4.0h；静注极速达峰 5 - 15 分钟)",
        t_max_hours=0.75,
        bioavailability="口服 20% - 40% (典型 ~30%，极强肝脏及肠壁首过葡萄糖醛酸化)；肌注/皮下 ~100%；静注 100%",
        cyp_enzymes=[
            "不依赖 CYP450 氧化途径 (主要经肝脏 UGT2B7 葡糖醛酸化生成约 60% 无镇痛/致神经毒性 M3G 与约 10% 强效活性 M6G)",
        ],
        clearance="肝/肠 UGT2B7 代谢产生吗啡-3-葡萄糖醛酸苷 (M3G, 无 μ 镇痛活性且致神经兴奋/痛觉过敏) 及吗啡-6-葡萄糖醛酸苷 (M6G, 强效 μ 镇痛)，两者完全经肾排出",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=4.0,
        washout_hours=24.0,
        washout_note="正常肾功能患者约 24 小时可基本清除母体及 M6G；极其致命的临床隐患在于：肾功能不全（哪怕轻中度 GFR 下降）患者绝对禁用常规重复剂量吗啡，因为极性水溶的 M6G 无法随尿排出而在脑室缓慢蓄积，引发给药停用后仍致命的迟发性深度呼吸抑制！",
        mechanism_summary="经典天然阿片类全激动剂金标准：自身迅速入脑并经 UGT2B7 生成透脑慢但持续更久的超强活性镇痛代谢物 M6G（与致肌阵挛惊厥的 M3G）。",
        active_metabolites=[
            met(
                "Morphine-6-glucuronide (M6G / 吗啡-6-葡萄糖醛酸苷)",
                "经肝 UGT2B7 催化吗啡 6-位羟基葡萄糖醛酸化生成 (~10%)",
                "3.0 - 5.0 小时 (肾衰患者暴增至 24 - 50h+)",
                4.0,
                2.5,
                2.0,
                "口服时血浆 AUC 常明显高于母体（且随反复给药稳态累计）",
                "对 μ-阿片受体亲和力与镇痛效价甚至高于吗啡原形；血脑屏障穿透较慢但滞留时间长，主要负责慢性口服镇痛及肾损时的致命迟发昏迷呼吸抑制",
            ),
            met(
                "Morphine-3-glucuronide (M3G / 吗啡-3-葡萄糖醛酸苷)",
                "经肝 UGT2B7 催化 3-位酚羟基结合生成 (占总代谢 ~55% - 60%)",
                "3.0 - 5.0 小时 (肾衰同样剧烈堆积)",
                4.0,
                2.5,
                6.0,
                "血浆浓度可达母体数倍甚至十倍",
                "对 μ-阿片受体无激动镇痛效力；反而拮抗镇痛作用并直接刺激神经兴奋引起异常性疼痛、肌阵挛及癫痫阈值降低",
            ),
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "经典完全激动剂 (Ki ≈ 1.8 nM，强效镇痛、呼吸抑制、欣快感与缩瞳)", "high", 0.99, "双相共同主导"),
            target("κ- / δ-阿片受体", "弱至中等亲和力激动 (辅助脊髓镇痛与内脏平滑肌痉挛)", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Oxycodone": pk(
        half_life="3.5 - 4.0 小时 (常释典型 3.8h；缓释片表观延长至 4.5 - 8h)",
        half_life_hours=3.8,
        t_max="1.0 - 1.5 小时 (常释口服；缓释片 3.0 - 5.0h)",
        t_max_hours=1.25,
        bioavailability="口服 60% - 87% (显著高于吗啡，因 3-甲氧基阻断了首过 UGT2B7 结合)",
        cyp_enzymes=[
            "CYP3A4 (主代谢途径底物 / Substrate；占 ~45% N-去甲基转化为基本无活性的去甲羟考酮)",
            "CYP2D6 (次要途径底物 / Substrate；约 10% O-脱甲基转化为极强活性代谢物羟吗啡酮)",
        ],
        clearance="主要经肝 CYP3A4 生成去甲羟考酮（仅极微活性）及 CYP2D6 生成强效羟吗啡酮；经肾脏排泄",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=4.0,
        washout_hours=24.0,
        washout_note="虽经 CYP2D6 生成强效羟吗啡酮 (Oxymorphone)，但人体常规暴露中羟吗啡酮血浆浓度通常仅约母体 10% 以下，大部分权威人类研究证实羟考酮镇痛与呼吸抑制主要由原形主导；然而联用 CYP3A4 强效抑制剂（克拉霉素、酮康唑、利托那韦）不仅大幅抬高母体 AUC，还会把代谢分流向 CYP2D6 加剧危险呼吸抑制！",
        mechanism_summary="高口服利用度半合成强阿片完全激动剂：自身主导强力 μ/κ 镇痛，受 CYP3A4 与 CYP2D6 双酶敏感交互影响。",
        active_metabolites=[
            met(
                "Oxymorphone (羟吗啡酮)",
                "母体经肝 CYP2D6 3-O-脱甲基化生成 (约占总代谢 ~10%)",
                "7.0 - 9.0 小时",
                7.5,
                2.0,
                0.08,
                "常规血浆游离暴露仅母体 ~5% - 10%（经迅速进一步 UGT 结合清除）",
                "μ-受体亲和力远强于母体约 40 倍（镇痛极强），但在多数成人中游离入脑量偏低，仅辅助母体药效；CYP3A4 阻断时生成比例升高",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "完全激动剂 (Ki ≈ 18 nM；口服透脑效率极高故人体实测镇痛效价高达吗啡 1.5 倍)", "high", 0.96, "母体主导"),
            target("κ-阿片受体 (KOR)", "中等亲和力部分协同参与镇痛效应", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Oxymorphone": pk(
        half_life="常释约 7.0 - 9.0 小时 (典型 7.5h；缓释片可达 9 - 11h)",
        half_life_hours=7.5,
        t_max="0.5 - 1.0 小时 (常释口服；高脂饮食使口服 Cmax 骤升约 38% - 50%！缓释片约 2 - 4h)",
        t_max_hours=0.75,
        bioavailability="口服仅约 10% (剧烈肝脏首过 UGT2B7 葡糖醛酸化；注意静注/鼻吸生物利用度陡升 4 - 10 倍，切换途径极易致命过量！)",
        cyp_enzymes=[
            "不经肝微粒体 CYP450 代谢 (主要经肝脏 UGT2B7 葡糖醛酸化与酮基还原消除，基本无 CYP 药物交互)",
        ],
        clearance="主要经 UGT2B7 结合为无显著活性的羟吗啡酮-3-葡萄糖醛酸苷，及少量 6-酮还原为 6-羟羟吗啡酮经尿排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=7.5,
        washout_hours=40.0,
        washout_note="口服绝对利用度仅约 10%（故口服 mg 量常是静注 10 倍，一旦经鼻吸 ~43% 或静注 100% 给药，等价剂量急剧猛增 4 - 10 倍极易骤停呼吸）；且绝对严禁随乙醇饮用（酒精可诱发缓释剂型极速倾释 Dose Dumping）。常规口服约 40 小时完全清洗。",
        mechanism_summary="超强效半合成 μ-阿片完全激动剂（非羟考酮依托，而是原形直接起效，静注效价约达吗啡 10 倍），几乎不走 CYP 通路。",
        active_metabolites=[],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "超强效完全激动剂 (Ki ≈ 0.36 - 0.8 nM，镇痛强、致吐致晕及极高呼吸抑制风险)", "high", 0.99, "母体主导"),
        ],
    ),
    "Hydromorphone": pk(
        half_life="2.0 - 3.0 小时 (常释典型 2.5h；OROS 渗透泵缓释片表观延长至 8 - 11h)",
        half_life_hours=2.5,
        t_max="0.5 - 1.5 小时 (常释口服约 1.0h；静注仅数分钟)",
        t_max_hours=1.0,
        bioavailability="口服约 20% - 35% (剧烈首过葡糖醛酸化)；静注 100%；鼻吸约 50% - 60%",
        cyp_enzymes=[
            "不经 CYP450 氧化系统清除 (主要经 UGT2B7 生成无显著中枢镇痛收益且仅在高蓄积时致神经兴奋的 H3G，免除 CYP 酶抑/诱风险)",
        ],
        clearance="肝 UGT2B7 结合生成氢吗啡酮-3-葡萄糖醛酸苷 (H3G, 占绝大部分) 经肾排出；极少原形排泄",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.5,
        washout_hours=16.0,
        washout_note="氢吗啡酮无类似 M6G 的活性 6-葡萄糖醛酸产物（因 6 位为酮基），母体 2.5h 极速消除；但肾功能衰竭时 3-葡萄糖醛酸苷 (H3G) 蓄积仍可诱发烦躁、痛觉超敏与肌阵挛，切忌任意以“静注比例”套算口服或鼻吸剂量。",
        mechanism_summary="高脂溶性速效强阿片 μ 激动剂（静注效价约为吗啡 5 - 7 倍），由母体直接迅速入脑且绕开 CYP 酶系。",
        active_metabolites=[],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "超强效完全激动剂 (Ki ≈ 0.4 - 0.6 nM，快起效伴随剧烈正性冲击与高呼吸抑制致死风险)", "high", 0.99, "母体主导"),
        ],
    ),
    "Hydrocodone": pk(
        half_life="3.3 - 4.4 小时 (典型 3.8h)",
        half_life_hours=3.8,
        t_max="1.0 - 1.3 小时 (常释复方片口服达峰)",
        t_max_hours=1.2,
        bioavailability="口服吸收良好 (约 70%，首过代谢较氢吗啡酮明显缓和)",
        cyp_enzymes=[
            "CYP3A4 (主要代谢途径底物 / Substrate；生成非活性去甲氢可酮 Norhydrocodone)",
            "CYP2D6 (次要生物激活途径底物 / Substrate；约 10% - 12% 转化为超强活性代谢物氢吗啡酮 Hydromorphone)",
        ],
        clearance="同时经肝 CYP3A4 生成去甲氢可酮、CYP2D6 生成氢吗啡酮以及 6-酮还原反应；代谢物大部分经肾排泄",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=3.8,
        washout_hours=24.0,
        washout_note="氢可酮自身 μ 亲和力显著弱于氢吗啡酮：CYP2D6 超快代谢者可产生危险过量氢吗啡酮；反之强效 CYP3A4 抑制剂（如酮康唑、红霉素、葡萄柚、利托那韦）阻塞主清除通道导致母体骤增并向 CYP2D6 分流，严重放大呼吸抑制风险！若市售复方含对乙酰氨基酚则更须严控日总量防范暴发性肝衰竭。",
        mechanism_summary="半合成弱至中强效 μ-阿片前体协同型镇痛药：母体直接微弱结合 MOR 并依赖 CYP2D6 转化为强效 10 倍级的氢吗啡酮协同镇痛。",
        active_metabolites=[
            met(
                "Hydromorphone (氢吗啡酮)",
                "母体经肝 CYP2D6 3-O-去甲基化生成 (约占清除 ~10%)",
                "2.0 - 3.0 小时",
                2.5,
                1.5,
                0.10,
                "正常代谢者 AUC 约为母体 ~5% - 12%；CYP2D6 超快代谢或 CYP3A4 阻断时大幅升高",
                "对 MOR 亲和力高出母体逾 30 - 100 倍，显著贡献氢可酮在中枢的实际峰值镇痛与欣快/呼吸抑制作用",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "激动剂 (母体亲和力弱-中 Ki ≈ 20 - 40 nM；代谢产物氢吗啡酮达亚纳摩尔强效激动)", "high", 0.90, "双相共同主导"),
        ],
    ),
    "Dihydrocodeine": pk(
        half_life="3.3 - 4.5 小时 (典型 4.0h)",
        half_life_hours=4.0,
        t_max="1.6 - 1.8 小时 (常释口服；控释片延至 3 - 4h)",
        t_max_hours=1.7,
        bioavailability="口服平均约 21% (范围 12% - 34%，受显著肝首过葡萄糖醛酸化及氧化清除影响)",
        cyp_enzymes=[
            "CYP2D6 (重要活性转化底物 / Substrate；生成强效代谢物二氢吗啡 Dihydromorphine)",
            "CYP3A4 (次要 N-脱甲基生成去甲二氢可待因 / Substrate)",
        ],
        clearance="主要经 UGT2B7 葡糖醛酸化（生成 DHC-6-G）及经 CYP3A4 脱甲基、CYP2D6 O-脱甲基后随尿排泄",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=4.0,
        washout_hours=24.0,
        washout_note="与可待因不同（可待因几乎纯属前药依赖 CYP2D6），临床研究证实即便合用奎尼丁阻断 CYP2D6 后，二氢可待因母体及 DHC-6-葡萄糖醛酸苷自身仍保留相当部分镇痛与镇咳疗效，但其峰值与欣快感部分依赖 CYP2D6 生成的二氢吗啡。",
        mechanism_summary="氢化可待因同系镇痛止咳药：母体自身直接贡献温和镇痛与外周/脑干止咳，并经 CYP2D6 转化生成强效活性体二氢吗啡。",
        active_metabolites=[
            met(
                "Dihydromorphine (二氢吗啡)",
                "母体经 CYP2D6 3-O-去甲基化生成 (仅占代谢少数)",
                "3.0 - 4.5 小时",
                3.8,
                2.0,
                0.08,
                "常规血浆暴露量约占母体 5% - 10% 左右",
                "超强效 μ-阿片受体完全激动剂（亲和力与吗啡相当，高于母体数十倍）",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "母体温和激动 (Ki ≈ 200 nM) 协同代谢物二氢吗啡强激动 (Ki ≈ 1 - 2 nM)", "high", 0.88, "双相共同主导"),
        ],
    ),
    "Fentanyl": pk(
        half_life="静注终端半衰期 3.0 - 7.0 小时 (典型 4.5h；单剂快推呈现仅 15 - 30 分钟快速再分布，多剂连用深层脂肪组织饱和蓄积骤延！透皮贴剂揭除后皮下贮库半衰期长达 20.0 - 27.0 小时！)",
        half_life_hours=5.0,
        t_max="静注 < 3 分钟；鼻喷 10 - 20 分钟 (0.25h)；口颊/舌下 20 - 45 分钟 (0.5h)；透皮贴剂需 12 - 24 小时才达坪值",
        t_max_hours=0.5,
        bioavailability="口服吞服受首过严重清除仅约 32% - 35%；口颊/舌下含片 50% - 76%；鼻喷雾约 89%；透皮贴剂 90% - 92%；静注 100%",
        cyp_enzymes=[
            "CYP3A4 (绝对主力 N-脱烷基代谢底物 / Substrate；联用强效 CYP3A4 抑制剂如利托那韦、克拉霉素、酮康唑可致命延迟清除致呼吸骤停！)",
        ],
        clearance="极度亲脂、快速向脂肪与肌肉深室再分布；90% 以上经肝 CYP3A4 氧化 N-脱烷基为完全无活性的去甲芬太尼 (Norfentanyl) 随尿排泄",
        pk_role="DEPOT_FLIP_FLOP",
        effective_half_life_hours=20.0,
        washout_hours=100.0,
        washout_note="极度危险的双相非线性与途径依赖药代特性：单次静注因极快入脑与脂室再分布看似 30 分钟退效，但一旦高脂库饱和或使用透皮贴剂（皮肤角质层成为缓释贮库 Flip-Flop 动力学），停药或揭贴后仍会以 20 - 27 小时的等效半衰期持续释放入血，致死性延迟呼吸抑制风险高达 72 小时以上！且与强 CYP3A4 抑制剂联用属于黑框级致死禁忌。若遇过量，常需重复甚至大剂量纳洛酮抢救。",
        mechanism_summary="超强效（约为吗啡 80 - 100 倍）、极高脂溶性的选择性合成 μ-阿片完全激动剂：无活性代谢物，完全由母体闪电穿脑及脂室/皮下滞留主导生死线。",
        active_metabolites=[],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "超高效价完全激动剂 (Ki ≈ 0.39 - 1.35 nM，极窄安全窗，数秒至数分钟即可诱发胸壁木僵与致死性呼吸中枢麻痹)", "high", 1.0, "母体主导"),
        ],
    ),
    "Sufentanil": pk(
        half_life="静脉约 2.5 - 3.0 小时 (典型 2.7h；舌下系统平均表观半衰期约 2.5 - 6h；反复给药脂蓄积延长)",
        half_life_hours=2.7,
        t_max="静注瞬时 (< 3 分钟)；舌下高浓微片 (Dsuvia) 约 1.0 小时",
        t_max_hours=1.0,
        bioavailability="舌下含服约 52% - 59%；口服吞服首过极高远低于 10%；静脉 100%",
        cyp_enzymes=[
            "CYP3A4 (核心 N-脱烷基化及 O-脱甲基代谢底物 / Substrate)",
        ],
        clearance="几乎完全经肝与小肠 CYP3A4 迅速代谢为无显著临床活性的去甲舒芬太尼与脱甲基产物（总清除极快）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.7,
        washout_hours=18.0,
        washout_note="舒芬太尼是临床效价最强的苯胺基哌啶阿片镇痛药之一（约为芬太尼 5 - 10 倍，μg 级微克单位！）：虽因脂溶分布快及肝脏高抽提清除使其单次有效半衰期短于持续静注芬太尼，但微毫克误差即导致即刻致死性呼吸骤停与重度木僵；联用 CYP3A4 强抑制剂延长清除。",
        mechanism_summary="超微克级最高亲和力选择性 μ-阿片激动剂，极强脂溶性瞬时穿过血脑屏障并依赖 CYP3A4 高效清除。",
        active_metabolites=[],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "极高亲和力超级完全激动剂 (Ki ≈ 0.14 nM，效价约为吗啡 500 - 1000 倍)", "high", 1.0, "母体主导"),
        ],
    ),
    "Heroin": pk(
        half_life="母体仅 2 - 6 分钟 (0.05h) 闪电酯解；第一级主力速穿代谢物 6-单乙酰吗啡 (6-MAM) 约 6 - 25 分钟 (0.25h)；终极持效产物吗啡约 2.0 - 3.0 小时",
        half_life_hours=0.05,
        t_max="静注/烟吸 1 - 3 分钟极速达峰（鼻吸 10 - 15 分钟）",
        t_max_hours=0.05,
        bioavailability="静注 100%；抽吸/追龙约 53% (范围 38% - 62%)；鼻吸 ~45%；口服彻底转化成吗啡后实际等效口服利用度仅约 20% - 35%",
        cyp_enzymes=[
            "主要不经 CYP450 启动 (血浆丁酰胆碱酯酶 BChE 与红细胞酯酶极其迅速水解除去 3-乙酰基，脑及肝酯酶续水解为吗啡，终段走 UGT2B7)",
        ],
        clearance="两步极速酶促脱乙酰：Heroin → 6-MAM → Morphine → M6G/M3G（经 UGT2B7 结合由肾排泄）",
        pk_role="PRODRUG",
        effective_half_life_hours=2.5,
        washout_hours=24.0,
        washout_note="海洛因（二乙酰吗啡）自身对 μ-阿片受体亲和力极低，纯粹是一个极高脂溶性、能够在数秒内击穿血脑屏障的“特洛伊木马前药”！进入大脑和血浆后数分钟内即被撕掉两个乙酰基：先变身超高亲和力“冲击波”产物 6-单乙酰吗啡 (6-MAM，主导最初 15 - 30 分钟极强烈 Rush 致呼吸骤停)，随后彻底转变为持效 2.5 小时以上的吗啡及 M6G 支撑全周期。",
        mechanism_summary="经典极速三级串联前药体系：凭借乙酰化脂溶性实现闪电入脑，由 6-MAM 触发急性剧烈峰值、再由吗啡 + M6G 接力持效。",
        active_metabolites=[
            met(
                "6-Monoacetylmorphine (6-MAM / 6-单乙酰吗啡)",
                "血浆及脑内酯酶极其迅速水解海洛因 3-位酚乙酰基生成",
                "6 - 25 分钟 (典型 ~0.25h)",
                0.25,
                0.08,
                0.85,
                "静注/吸入初期脑内峰值甚至超越吗啡，也是法医确证海洛因摄入的特异性标识",
                "超高脂溶入脑且具备高于吗啡本身的 μ-受体高效激动活性，直接制造海洛因最致命的急性“Rush”期呼吸中枢麻痹",
            ),
            met(
                "Morphine (吗啡) & M6G",
                "6-MAM 进一步被脑与肝酯酶脱去 6-位乙酰基生成吗啡，再经 UGT2B7 生成 M6G",
                "2.0 - 4.0 小时 (典型 2.5h；肾损 M6G 延长)",
                2.5,
                0.75,
                1.0,
                "完全承载给药 30 分钟之后全身与大脑维持期全部阿片暴露",
                "维持后续数小时深度镇静、镇痛与持续呼吸抑制风险",
            ),
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "由 6-MAM 与吗啡/M6G 两级激活发挥超高强度完全激动 (联用苯二氮䓬或乙醇致死率倍增)", "high", 1.0, "代谢物主导"),
        ],
    ),
    "Pethidine": pk(
        half_life="母体 3.0 - 6.0 小时 (典型 4.0h)；高神经毒性代谢物去甲哌替啶 (Norpethidine) 半衰期长达 15.0 - 20.0 小时 (肾损时暴增至 30 - 40h+)",
        half_life_hours=4.0,
        t_max="1.0 - 2.0 小时 (口服常释)；肌注约 0.5 - 1.0 小时",
        t_max_hours=1.5,
        bioavailability="口服约 50% - 60% (首过肝酯酶水解约 40% - 50%；肝硬化患者升高至 80% - 90%)",
        cyp_enzymes=[
            "肝羧酸酯酶 (主要水解生成无活性的哌替啶酸)",
            "CYP2B6 / CYP3A4 / CYP2C19 (N-去甲基化代谢底物 / Substrate；生成高神经毒性致癫痫代谢物去甲哌替啶 Norpethidine)",
        ],
        clearance="肝酯酶水解与 CYP 氧化 N-脱甲基共同代谢；去甲哌替啶严重依赖肾小球排出，反复大剂量或肾衰极易蓄积中毒",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=17.5,
        washout_hours=85.0,
        washout_note="严重临床双重大忌警告：① 哌替啶母体具有极强的 SERT 血清素再摄取阻断能力，联用单胺氧化酶抑制剂 (MAOI)、SSRI 或 MDMA 可直接引爆致命性血清素综合征与恶热（传统止痛药中最致命禁忌！）；② 连续多剂注射会疯狂堆积半衰期长达 15 - 20 小时的“去甲哌替啶 (Norpethidine)”，引起无止境肌阵挛、精神错乱乃至纳洛酮无法逆转甚至反倒加重的致命癫痫持续状态！",
        mechanism_summary="高毒性第一代合成苯基哌啶阿片药：母体兼有 μ 激动、抗胆碱及危险的 SERT 抑制活性，代谢产物去甲哌替啶以 17h 长半衰期诱发严重惊厥蓄积。",
        active_metabolites=[
            met(
                "Norpethidine (去甲哌替啶 / Normeperidine)",
                "母体经 CYP2B6、CYP3A4 及 CYP2C19 N-脱甲基生成",
                "15.0 - 20.0 小时 (典型 17.5h；肾损 > 35h)",
                17.5,
                6.0,
                1.2,
                "连续给药稳态血浆与脑内浓度极易反超母体 1 - 2 倍以上",
                "几乎无有效 μ 镇痛作用，却具备强烈致中枢狂躁、震颤、幻觉与癫痫强直阵挛发作的神经毒性（无法被纳洛酮拮抗且严禁肾衰连用）",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "中等完全激动剂 (效价仅为吗啡 ~1/8 - 1/10)", "high", 0.85, "母体主导"),
            target("SERT (5-羟色胺转运体)", "显著再摄取抑制 (剧毒级 MAOI 致命交互根源！)", "high", 0.82, "母体主导"),
            target("毒蕈碱及钠离子通道", "轻度阿托品样抗胆碱与局部麻醉效应 (导致口干心动过速及去甲代谢物致痫)", "moderate", 0.55, "代谢物增益"),
        ],
    ),
    "Tapentadol": pk(
        half_life="4.0 - 6.0 小时 (常释典型 4.5h；缓释片约 5 - 6h)",
        half_life_hours=4.5,
        t_max="1.25 - 1.5 小时 (常释口服；缓释片约 3 - 6h)",
        t_max_hours=1.3,
        bioavailability="口服约 32% (剧烈肝脏首过 UGT 直接葡萄糖醛酸化；不受高脂饮食有害削弱)",
        cyp_enzymes=[
            "主要不经肝微粒体 CYP 代谢 (约 70% 直接经 UGT1A9 与 UGT2B7 结合，无有活性代谢物)",
            "CYP2C9 / CYP2C19 / CYP2D6 (仅占不足 15% 的次要氧化辅助，不受酶抑制剂明显波动)",
        ],
        clearance="约 99% 经尿液排出（主要为无活性葡萄糖醛酸及硫酸结合物，约 3% 原形，彻底不同于需 CYP2D6 激活的曲马多）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=4.5,
        washout_hours=24.0,
        washout_note="他喷他多彻底摒弃了曲马多依靠 CYP2D6 激活及强烈抑制 SERT 的设计缺点，直接由母体独立承担 μ-激动与去甲肾上腺素再摄取抑制，因此极少受 CYP 多态性制约、约 24 小时干净清除；但仍然禁用 MAOI（保留强劲 NET 抑制导致高血压危象，与强 5-HT 能药物亦建议审慎保持安全距离）且禁与酒精混服（酒精破坏缓释片倾释）。",
        mechanism_summary="单一分子直接双机制镇痛药（MOR 激动 + NET 抑制，不依赖代谢前药激活且对 SERT 影响极弱），母体原形主导全部效力。",
        active_metabolites=[],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "中等亲和力完全激动剂 (虽体外亲和力弱于吗啡 18 倍，借双通道协同体内实测镇痛约等效吗啡 1/3)", "high", 0.88, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "强效再摄取抑制 (显著增强脊髓下行痛觉抑制通路，辅助神经痛镇痛并引发 MAOI 高血压禁忌)", "high", 0.85, "母体主导"),
            target("SERT (5-羟色胺转运体)", "极微弱结合（血清素综合征风险大幅低于曲马多和哌替啶，但仍不宜与高量血清素药混叠）", "low", 0.25, "母体主导"),
        ],
    ),
    "Tilidin": pk(
        half_life="母体仅 2.0 - 3.0 小时；经肝脏激活生成的唯一核心镇痛体去甲替利定 (Nortilidine) 半衰期约 3.0 - 5.0 小时",
        half_life_hours=2.5,
        t_max="母体 < 1.0 小时 (约 0.5 - 0.8h)；去甲替利定约 1.0 - 2.0 小时达峰",
        t_max_hours=0.8,
        bioavailability="口服替利定原形仅约 7.6%（几乎全数于肝脏首过转化为活性体去甲替利定，去甲替利定实际生物利用度高近 90%！）",
        cyp_enzymes=[
            "CYP3A4 (核心激活酶底物 / Substrate；负责 N-脱甲基转化为强效镇痛活性物去甲替利定)",
            "CYP2C19 (主要激活转化底物 / Substrate；催化形成去甲替利定)",
        ],
        clearance="经 CYP3A4 与 CYP2C19 两步 N-脱甲基先后产生去甲替利定 (Nortilidine) 及双去甲替利定 (Bisnortilidine)，最终约 90% 代谢物经肾排泄",
        pk_role="PRODRUG",
        effective_half_life_hours=4.0,
        washout_hours=24.0,
        washout_note="替利定原形几乎全数作为前药，经过肝脏首过 CYP3A4/CYP2C19 转化成比母体强效 100 倍以上的活性镇痛分子去甲替利定 (Nortilidine)！静注替利定反倒不如口服起效快且易失效（临床常规常添入少量纳洛酮形成防静注滥用复方 Valoron N）。联用利托那韦或强 CYP3A4+2C19 抑制剂会双倍阻断激活转化。",
        mechanism_summary="纯阿片镇痛前药（口服强效而静注几乎无活性）：全靠肝 CYP3A4/CYP2C19 首过脱甲基生成去甲替利定激活 μ-阿片受体。",
        active_metabolites=[
            met(
                "Nortilidine (去甲替利定)",
                "母体口服经过 CYP3A4 与 CYP2C19 首过脱甲基产生",
                "3.0 - 5.0 小时 (典型 4.0h；缓释制剂表观延长)",
                4.0,
                1.5,
                12.0,
                "系统血浆暴露量 AUC 高出母体原形 10 倍以上（唯一真正起效的循环实体）",
                "强效激动 μ-阿片受体（亲和力与镇痛效价约为替利定原形 100 倍以上，大致与吗啡的 1/5 - 1/4 相当）",
            ),
            met(
                "Bisnortilidine (双去甲替利定)",
                "去甲替利定继续脱去第二个甲基生成",
                "5.0 - 8.0 小时 (典型 6.5h)",
                6.5,
                3.0,
                1.5,
                "暴露量与去甲替利定相当或略低",
                "保有部分微弱辅助镇痛尾效但强度明显逊于去甲替利定",
            ),
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "经由去甲替利定发挥核心完全/强效激动作用 (母体极弱几乎无效)", "high", 0.90, "代谢物主导"),
        ],
    ),
    "Naloxone": pk(
        half_life="静脉/肌注 1.0 - 1.5 小时 (典型 1.2h)；鼻喷剂约 1.8 - 2.1 小时",
        half_life_hours=1.3,
        t_max="静注 < 2 分钟；肌注/皮下约 15 - 23 分钟；高浓鼻喷雾 (Narcan) 约 15 - 30 分钟 (0.35h)",
        t_max_hours=0.35,
        bioavailability="静注 100%；鼻腔喷雾约 43% - 54%（相对肌注参考）；口服因极剧烈肝首过 UGT 结合仅 < 2%（故常添入丁丙诺啡/替利定防静脉滥用而不影响正常含服/口服）",
        cyp_enzymes=[
            "不经肝细胞色素 CYP450 清除 (几乎全数经肝 UGT 葡萄糖醛酸化为无活性纳洛酮-3-葡糖苷酸迅速肾排)",
        ],
        clearance="肝脏超高速葡萄糖醛酸化与酮基还原，清除极迅速",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=1.3,
        washout_hours=8.0,
        washout_note="重大生命救援警告（Re-narcotization 复发昏迷风险！）：纳洛酮消除半衰期仅 1 - 2 小时，远远短于海洛因代谢产物吗啡 (2.5 - 4h)、羟考酮 (4h) 尤其是美沙酮 (24 - 60h) 及缓释阿片！用纳洛酮将过量患者救醒 30 - 90 分钟后，纳洛酮率先消退，残留的高负荷阿片会再度占领受体引发致命的二次呼吸停止！抢救后必须送医监护至少 4 - 6 小时并随时追加纳洛酮！",
        mechanism_summary="高亲和力快效短半衰期竞争性阿片受体完全拮抗剂（急诊唯一抢救阿片呼吸抑制解毒药），极低口服利用度亦使其广泛用作防注射复方阻断剂。",
        active_metabolites=[],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "超高亲和力纯粹完全拮抗剂 (Ki ≈ 1.0 nM，数分钟内剥离阿片逆转呼吸抑制并可引爆剧烈急性戒断)", "high", 0.99, "母体主导"),
            target("κ- / δ-阿片受体", "强效竞争性拮抗剂 (Ki ≈ 6 - 30 nM)", "high", 0.85, "母体主导"),
        ],
    ),
    "Kratom": pk(
        half_life="核心生物碱帽柱木碱 (Mitragynine) 人类临床实测终末半衰期约 23.0 - 43.0 小时 (初始相 3 - 7h，连续服用因脂溶积累呈现长达 ~24h+ 终端慢相)；7-羟基帽柱木碱 4.7h (单剂) 至 24.7h (稳态)",
        half_life_hours=24.0,
        t_max="口服约 1.0 - 1.7 小时 (典型 1.3h)",
        t_max_hours=1.3,
        bioavailability="口服在人类吸收受植物粗品基质影响约 20% - 30%（高脂溶大分布容积 Vd ≈ 38 - 90 L/kg；伴随首过 CYP3A4 生成活性 7-OH 产物）",
        cyp_enzymes=[
            "CYP3A4 (核心代谢底物 / Substrate；负责氧化生成效价极强的活性产物 7-羟基帽柱木碱 7-OH-MG)",
            "CYP2D6 (显著机制/竞争性抑制潜力 / Moderate-Strong Inhibitor；高血浆暴露浓度常明显抑制其他 CYP2D6 药物清除！)",
            "CYP3A4 / CYP1A2 (微弱至中度体外抑制潜力 / Inhibitor)",
        ],
        clearance="极高亲脂性大分布容积导致缓慢脂肪释放，主要经肝 CYP3A4 氧化产生 7-羟基帽柱木碱及去甲基产物，兼经尿苷二磷酸葡糖醛酸转移酶结合排泄",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=24.0,
        washout_hours=120.0,
        washout_note="人体最新临床试验 (Trakulsrichai / Tanna 等) 颠覆了“卡痛叶只有 3 小时”的旧印象：核心生物碱帽柱木碱具有极高的分布容积与长达 23 - 43 小时的终端消除半衰期！同时卡痛叶生物碱会显著抑制 CYP2D6 与 CYP3A4（联用抗抑郁药/阿片/镇静剂易骤增毒性），且活性产物 7-羟基帽柱木碱（以及次级代谢物帽柱木伪吲哚）μ-受体镇痛效价高出母体 10 倍以上！",
        mechanism_summary="多生物碱协同的 G 蛋白偏向型非典型阿片+肾上腺素能植物制剂：母体主控多受体稳态与长时间蓄积，CYP3A4 转化的 7-羟基帽柱木碱贡献最强 μ-受体激活。",
        active_metabolites=[
            met(
                "7-Hydroxymitragynine (7-羟基帽柱木碱)",
                "原生植物微量含有 + 口服后主要由帽柱木碱经人肝 CYP3A4 羟基化在体生成",
                "单剂约 4.7 小时 (多次连续稳态延长至 ~24.7 小时)",
                5.0,
                1.5,
                0.25,
                "人体实测口服后循环暴露 AUC 比母体帽柱木碱高可达 0.20 - 0.31 倍（稳态蓄积更高）",
                "对 MOR 亲和力约为帽柱木碱母体的 10 - 13 倍（部分激动活性极强），实际驱动人体卡痛叶摄入后主要的阿片样镇痛、欣快感与身体依赖性",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "G 蛋白偏向型部分激动剂 (7-OH-MG Ki ≈ 13 - 47 nM，无明显 β-arrestin 募集但长期连续仍致耐受与强躯体戒断)", "high", 0.92, "双相共同主导"),
            target("α2 肾上腺素能受体 / 5-HT2A", "中等激动与拮抗调节 (低剂量产生兴奋醒神作用，解释卡痛“低剂量提神、高剂量镇痛”的双向曲线)", "moderate", 0.70, "母体主导"),
        ],
    ),
    "O-Desmethyltramadol": pk(
        half_life="6.0 - 9.0 小时 (人体药代临床研究典型均值约 7.5 小时，显著长于曲马多母体自身 5 - 6h)",
        half_life_hours=7.5,
        t_max="直接服用时依据常规常释估算，临床衍生研究峰时约 2.0 - 3.0 小时 (典型 2.5h)",
        t_max_hours=2.5,
        bioavailability="高口服及舌下利用度 (避开了曲马多对 CYP2D6 代谢激活的前药瓶颈，无遗传慢代谢无效率)",
        cyp_enzymes=[
            "自身即为终极活性体，不依赖前药氧化转化激活 (主要经肝脏 UGT1A/UGT2B 葡糖醛酸化与硫酸结合后随尿排出)",
        ],
        clearance="主要在肝脏结合成 O-去甲曲马多葡萄糖醛酸苷与硫酸酯及少量原形经肾排泄（消除速率不受 CYP2D6 遗传多态性阻断）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=7.5,
        washout_hours=40.0,
        washout_note="O-去甲基曲马多 (O-DSMT) 正是曲马多在体内真正发挥强镇痛效用的终极 CYP2D6 活性产物：绕开了曲马多诱导癫痫、重度 5-HT 综合征及慢代谢失效的问题，但作为真正强力的纯 μ-阿片完全激动剂（且保有去甲肾上腺素再摄取抑制作用），过量直接致死性呼吸抑制风险大幅高于曲马多，且禁用 MAOI；常规约 40 小时清零。",
        mechanism_summary="曲马多活性产物自身：去掉了强致惊厥的 SERT 偏向母体，直接以强效 μ-阿片激动 + 适度 NET 抑制发挥更长半衰期持久镇痛。",
        active_metabolites=[],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "高效价完全/强效激动剂 (右旋 (+)-O-DSMT 亲和力约为曲马多母体的 300 - 700 倍！存在明确阿片耐受与致死呼吸抑制危险)", "high", 0.96, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "保留显着去甲肾上腺素再摄取抑制活性 (禁联 MAOI；辅助下行神经痛抑制)", "moderate", 0.72, "母体主导"),
        ],
    ),
    "Dextropropoxyphene": pk(
        half_life="母体 6.0 - 12.0 小时 (典型 8.0h)；极高心脏毒性和惊厥性主代谢物去甲丙氧芬长达 30.0 - 36.0 小时",
        half_life_hours=8.0,
        t_max="1.0 - 2.0 小时 (口服达峰时间)",
        t_max_hours=1.5,
        bioavailability="口服吸收迅速但经肝脏首过广泛脱甲基，生物利用度约 30% - 70%",
        cyp_enzymes=[
            "CYP3A4 (主要 N-去甲基代谢底物 / Substrate；生成长效毒性去甲丙氧芬)",
            "CYP3A4 / CYP2D6 (自身与去甲代谢物具备时间依赖性抑制潜力 / Inhibitor)",
        ],
        clearance="肝 CYP3A4 脱甲基转化为半衰期高达 30 - 36 小时的去甲丙氧芬并缓慢经肾排出（老年及多次连服极度蓄积）",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=33.0,
        washout_hours=165.0,
        washout_note="因致命心脏毒性而在欧美全面退市的极危险老旧阿片药：母体半衰期 6 - 12h 之外，其 CYP3A4 产物去甲丙氧芬半衰期长达 30 - 36 小时且具强力局麻样心肌钠通道阻滞效应，导致慢性连用或过量时极其容易突发纳洛酮无法急救的宽 QRS 室颤心脏骤停与顽固性癫痫！完整洗脱需 1 周以上。",
        mechanism_summary="极窄治疗窗合成二苯甲基阿片药：弱 μ-阿片镇痛之下隐藏着长达 33 小时半衰期的心肌毒及致痫产物去甲丙氧芬。",
        active_metabolites=[
            met(
                "Norpropoxyphene (去甲丙氧芬)",
                "母体经肝 CYP3A4 N-脱甲基化生成",
                "30.0 - 36.0 小时 (老年人群更长)",
                33.0,
                6.0,
                2.5,
                "连续给药稳态血浆暴露可高出母体 2 - 4 倍以上（极强蓄积体）",
                "几乎无镇痛益处，却具有极强心肌钠通道阻塞活性（引起严重 PR/QRS 延长与心源性猝死）及致癫痫发作毒性",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "弱效激动剂 (镇痛仅相当于可待因弱档)", "moderate", 0.72, "母体主导"),
            target("心脏快速电压门控钠通道 (Nav1.5)", "去甲丙氧芬主导强效局部麻醉样阻滞 (致命 QTc/QRS 延展心律失常！)", "high", 0.95, "代谢物主导"),
        ],
    ),
    "Ethylmorphine": pk(
        half_life="约 2.0 小时 (健康人类受控研究实测母体终末半衰期 ~1.9 - 2.3h)",
        half_life_hours=2.0,
        t_max="约 0.75 小时 (人体中位口服约 45 分钟迅速达峰)",
        t_max_hours=0.75,
        bioavailability="口服迅速吸收并经历广泛首过肝代谢 (经 CYP2D6 生成微量至中等吗啡及经 CYP3A4 生成去甲乙基吗啡)",
        cyp_enzymes=[
            "CYP2D6 (O-脱乙基激活底物 / Substrate；转化生成活性代谢物吗啡 Morphine，慢代谢者大幅弱化)",
            "CYP3A4 (主要 N-脱甲基底物 / Substrate；转化为去甲乙基吗啡)",
        ],
        clearance="主要经肝脏 UGT 葡糖醛酸化及经 CYP3A4/CYP2D6 氧化脱烷基后随尿排泄",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=2.5,
        washout_hours=15.0,
        washout_note="乙基吗啡机制极其类似可待因（以乙基替代甲基）：自身半衰期约 2 小时，同时通过 CYP2D6 脱乙基释放活性吗啡及 M6G 带来镇痛和止咳核心作用（15 小时内基本清除），因此同样面临 CYP2D6 慢代谢失效与超快代谢儿童/母乳喂养窒息危象。",
        mechanism_summary="经典止咳镇痛前药型阿片同系物：借 CYP2D6 O-去乙基反应缓慢释放吗啡及 M6G 产生中枢疗效。",
        active_metabolites=[
            met(
                "Morphine (吗啡) & M6G",
                "母体经肝 CYP2D6 O-脱乙基化生成",
                "2.0 - 4.0 小时",
                2.5,
                1.2,
                0.12,
                "随 CYP2D6 代谢表型波动显著（超快代谢者暴露锐增，慢代谢者几乎无生成）",
                "承担乙基吗啡大部分中枢 μ-受体镇痛及呼吸中枢抑制负荷",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "母体极弱结合，主要由激活生成的吗啡与 M6G 发挥强效激动", "high", 0.85, "代谢物主导"),
        ],
    ),

    # ==================== Group 3: Dissociatives, Psychedelics & Cannabinoids ====================
    "Ketamine": pk(
        half_life="母体 2.0 - 3.0 小时 (典型 2.5h；静注单剂初始分布仅 10 - 15 分钟快速出脑醒转，总消除 ~2.5h)；主力活性产物去甲氯胺酮 (Norketamine) 约 4.0 - 8.0 小时",
        half_life_hours=2.5,
        t_max="鼻吸约 0.25 - 0.5h (15 - 30m)；口服约 0.5h (20 - 45m)；肌注 15 - 20m；静注 < 5 分钟",
        t_max_hours=0.45,
        bioavailability="静脉 100%；肌注 93%；鼻吸 45% - 50%；舌下 24% - 30%；口服因极高肝脏首过转化为去甲氯胺酮仅剩 16% - 20%",
        cyp_enzymes=[
            "CYP3A4 (核心代谢底物 / Substrate；主导 N-去甲基转化为活性产物去甲氯胺酮)",
            "CYP2B6 (重要代谢底物 / Substrate；高效参与手性去甲基化)",
        ],
        clearance="几乎全数经肝脏 CYP3A4/CYP2B6 转化为活性去甲氯胺酮 (Norketamine)，继而羟化转化为 (2R,6R;2S,6S)-羟基去甲氯胺酮 (HNK) 并经 UGT 结合后 90% 随尿排出",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=5.0,
        washout_hours=28.0,
        washout_note="氯胺酮给药途径深刻改变药代归属：静注/肌注/鼻吸初期由母体强势击穿血脑屏障爆发解离（1 - 2h 内快速退潮）；但口服生物利用度仅 16% - 20%，绝大部分在首过肝脏即转化为半衰期长达 4 - 8 小时的去甲氯胺酮 (Norketamine)——这解释了为什么口服氯胺酮解离持续明显更长但更偏昏沉迟钝！完全洗脱约需 28 小时。",
        mechanism_summary="非竞争性 NMDA 通道开放阻滞剂兼快效抗抑郁前沿标杆：母体驱动速效急性解离与 AMPA 突触重塑，长效产物去甲氯胺酮接力后续镇痛与拖尾效应。",
        active_metabolites=[
            met(
                "Norketamine (去甲氯胺酮)",
                "母体经肝 CYP3A4 与 CYP2B6 脱甲基化生成（口服首过生成比极大）",
                "4.0 - 8.0 小时 (典型 5.0h)",
                5.0,
                1.0,
                1.5,
                "静注/鼻吸暴露约为母体 0.5 - 1 倍；口服给药时稳态 AUC 可高出母体 2 - 4 倍以上！",
                "保留母体约 1/3 效价的 NMDA 拮抗镇痛及解离尾效，并进一步代谢生成参与快速抗抑郁机制的羟基去甲氯胺酮 (HNK)",
            )
        ],
        receptor_targets=[
            target("NMDA 谷氨酸受体 (PCP 通道位点)", "非竞争性开放通道阻滞剂 (S-氯胺酮 Ki ≈ 0.3 μM；急性解离麻醉、幻觉及快效抗抑郁触发器)", "high", 0.98, "双相共同主导"),
            target("AMPA 突触重塑 / HCN1 通道", "间接触发 BDNF 释放及 mTOR 突触新生（快速持久抗抑郁核心假说）", "high", 0.85, "双相共同主导"),
            target("σ1 / μ-阿片 (极弱辅助)", "微弱协同调控（高用量需考虑纳洛酮/降压关联但本身无强阿片呼吸抑制）", "low", 0.30, "母体主导"),
        ],
    ),
    "Dextromethorphan": pk(
        half_life="CYP2D6 正常快代谢者 (EM) 2.0 - 4.0 小时 (典型 3.0h)；CYP2D6 慢代谢者 (PM，约占白人 5% - 10%/亚洲约 1% - 2% 或联用抑制剂时) 延长至 15.0 - 24.0 小时+！活性代谢物右啡烷 (DXO) 3.4 - 5.6 小时",
        half_life_hours=3.0,
        t_max="2.0 - 2.6 小时 (典型 2.2h；糖浆/软胶囊达峰迅速)",
        t_max_hours=2.2,
        bioavailability="正常代谢者仅约 11% (剧烈 CYP2D6 首过转化为高强度解离产物右啡烷)；但慢代谢或联用安非他酮/氟西汀/奎尼丁后母体利用度暴升至 60% - 80%+！(临床重磅药物 Auvelity 即利用安非他酮刻意封锁 CYP2D6)",
        cyp_enzymes=[
            "CYP2D6 (绝对决定性首过激活转化底物 / Substrate；将其 O-脱甲基为高强效解离 NMDA 拮抗产物右啡烷 DXO)",
            "CYP3A4 (次要 N-脱甲基底物 / Substrate；慢代谢及抑制时承担主要代偿清除，生成 3-甲氧基吗啡烷)",
        ],
        clearance="正常人绝大部分经 CYP2D6 快速转化成右啡烷 (Dextrorphan, DXO) 后由 UGT 结合尿排；约 15% 走 CYP3A4 途径",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=4.5,
        washout_hours=30.0,
        washout_note="右美沙芬拥有药理学上最经典的双相解离切换与致命禁忌逻辑：① 正常人吞服后 89% 首过经 CYP2D6 变为强力 NMDA 解离剂右啡烷 (DXO, t1/2 ≈ 4.5h)；② 但若患者为 CYP2D6 慢代谢基因型，或合用了安非他酮/氟西汀/帕罗西汀/奎尼丁/MDMA 抑制 CYP2D6，则母体自身滞留暴增至 18 - 24h+（使体验偏向 SERT 血清素与 σ1 兴奋昏迷）！③ 高致命警告：DXM 原形具有强劲的 SERT 5-HT 再摄取抑制力，绝对严禁与 MAOI、SSRI、SNRI 或 MDMA 合用引发高致命性血清素综合征！",
        mechanism_summary="双分子接力式非典型解离剂：母体原形主导 σ1 激动与强效 SERT 抑制（诱发致死级血清素交互及止咳），经 CYP2D6 首过生成的右啡烷则主导强效 NMDA 受体解离。",
        active_metabolites=[
            met(
                "Dextrorphan (DXO / 右啡烷)",
                "母体经肝 CYP2D6 3-O-脱甲基化生成（占快代谢者转化 > 80%）",
                "3.4 - 5.6 小时 (典型 4.5h)",
                4.5,
                2.5,
                2.5,
                "常规快代谢者游离与结合 DXO 远超母体原形（游离 AUC 可达母体 2 - 4 倍；阻断 CYP2D6 时反转）",
                "对 NMDA 受体亲和力强于母体约 10 倍 (Ki ≈ 0.4 - 0.9 μM)，真正负责 DXM 的空间解离、共济失调与迷幻“Plateau”梯度反应",
            )
        ],
        receptor_targets=[
            target("NMDA 谷氨酸受体", "非竞争性开放通道阻滞剂 (主要由活性产物 DXO 主导强解离效力)", "high", 0.95, "代谢物主导"),
            target("SERT (5-羟色胺转运体)", "母体强效再摄取抑制 (Ki ≈ 23 nM；绝禁合用 MAOI / SSRI / MDMA 致高热血清素综合征！)", "high", 0.94, "母体主导"),
            target("σ1 受体 (Sigma-1)", "母体强效激动 (Ki ≈ 140 - 200 nM；止咳镇静与抗抑郁机理关键，联合右美沙芬/安非他酮治疗 MDD)", "high", 0.88, "母体主导"),
            target("NET / nAChR α3β4", "中等抑制调节", "moderate", 0.60, "母体主导"),
        ],
    ),
    "Memantine": pk(
        half_life="极长终末半衰期 60.0 - 80.0 小时 (典型 70.0h)",
        half_life_hours=70.0,
        t_max="3.0 - 7.0 小时 (常释口服典型 5.0h；缓释胶囊约 9 - 12h)",
        t_max_hours=5.0,
        bioavailability="口服约 100% (绝对利用度极高且基本不受食物干扰)",
        cyp_enzymes=[
            "不经肝细胞色素 CYP450 代谢 (极少受常规肝脏药物相互作用干扰)",
        ],
        clearance="约 48% - 50% 以未变化原形直接经肾排出（高受尿液 pH 调控：碱性尿使肾小管重吸收大幅上升，清除率骤降约 80%！），其余经极少数结合与羟基化排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=70.0,
        washout_hours=350.0,
        washout_note="美金刚拥有极其惊人的 60 - 80 小时（约 3 天）血浆半衰期：仅单次大剂量娱乐或重度误服即可令强烈解离、复视及认知失调连续拖延 3 - 5 天，彻底清零需长达 2 周以上 (350h)！碳酸氢钠、碳酸酐酶抑制剂（乙酰唑胺）及高度碱性尿可进一步将清除阻滞至数周。",
        mechanism_summary="长半衰期金刚烷胺类低亲和力非竞争性 NMDA 电压依赖快速通道阻滞剂，完全由母体原形持续数日主导慢步稳态。",
        active_metabolites=[],
        receptor_targets=[
            target("NMDA 谷氨酸受体", "快脱结合低亲和力开放通道阻滞剂 (Ki ≈ 0.5 - 1.0 μM，治疗窗防止神经兴奋毒性而娱乐高量呈数日持久解离)", "high", 0.95, "母体主导"),
            target("5-HT3 / nAChR α7", "温和拮抗调节", "moderate", 0.60, "母体主导"),
            target("D2 多巴胺受体 (D2High)", "极弱部分激动（仅部分大鼠/高剂量表现微小精神运动协同）", "low", 0.35, "母体主导"),
        ],
    ),
    "PCP": pk(
        half_life="约 17.0 - 21.0 小时 (典型 18.0h；极端过量或碱性尿液及脂肪深库释放时个体跨度达 7 - 46 小时+)",
        half_life_hours=18.0,
        t_max="抽吸/鼻吸约 15 - 30 分钟 (0.4h)；口服约 2.0 小时",
        t_max_hours=0.4,
        bioavailability="口服约 72% (吸收入血迅速，且显著分布积聚至脂肪组织与酸性胃液离子捕获再吸收)",
        cyp_enzymes=[
            "CYP3A4 (主要肝羟化代谢底物 / Substrate；生成各种无显著活性的单羟基、二羟基哌啶环代谢产物)",
        ],
        clearance="主要经肝脏 CYP3A4 氧化羟化后葡糖醛酸化经尿液排泄（仅 ~10% 原形排出，受尿液酸性度显著调控；酸化尿加速清除）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=18.0,
        washout_hours=96.0,
        washout_note="苯环利定 (PCP) 兼具高亲脂性大分布容积（大量蓄积脂肪并产生胃酸胃肠再循环）及高达 ~18 小时的母体半衰期，急性反复追吸极为危险，容易跨越“解离狂躁/无痛感”并诱发持续 2 - 4 天以上的精神分裂样妄想、暴力激越及横纹肌溶解高热。",
        mechanism_summary="经典初代高亲和力高脂溶 NMDA 通道阻滞剂，兼具多巴胺 D2/DAT 间接多巴胺激动特征与严重中枢精神兴奋毒性。",
        active_metabolites=[],
        receptor_targets=[
            target("NMDA 谷氨酸受体 (PCP 位点)", "超强效非竞争性慢解离通道阻滞剂 (Ki ≈ 59 nM，驱动深重解离与感知痛觉丧失)", "high", 0.99, "母体主导"),
            target("DAT / D2 受体", "抑制多巴胺回收或促释放协同（制造典型狂躁与超人力量幻觉及横纹肌溶解风险）", "moderate", 0.72, "母体主导"),
            target("σ1 受体", "显著激动结合", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Nitrous": pk(
        half_life="血浆分布与呼气极速消除初始半衰期约 2 - 3 分钟 (0.05h)，组织极微量残余终末清除约 30 分钟",
        half_life_hours=0.05,
        t_max="肺泡吸入仅 1 - 3 分钟 (0.05h) 达动脉与大脑平衡峰值",
        t_max_hours=0.05,
        bioavailability="肺吸入平衡利用度近 100%（血气分配系数极低 0.47，进出大脑极速），不经任何首过途径",
        cyp_enzymes=[
            "不经任何肝细胞色素酶代谢 (99% 以上直接以未变化原形随呼气排出体外)",
        ],
        clearance="停止吸入数分钟内即由肺泡逆向呼吸几乎完全清除；仅约 0.004% 极微量在肠道微生物厌氧还原时不可逆氧化灭活维生素 B12 的钴核心！",
        pk_role="IRREVERSIBLE_PD",
        effective_half_life_hours=0.05,
        washout_hours=72.0,
        washout_note="虽然一氧化二氮本身呼吸动力学极快（5 - 10 分钟内从血液呼干净，血浆半衰期仅 0.05h），但它会以化学共价氧化方式**不可逆灭活**体内甲硫氨酸合酶 (Methionine Synthase) 中的维生素 B12 (钴胺素 Co(I) → Co(III))！单次高频或连续数天吸入即可令骨髓 DNA 合成与神经髓鞘保护酶系统停摆数日乃至数周（半恢复需 3 - 5 天新酶再生），引发极具致残风险的亚急性脊髓联合变性与双下肢麻木瘫痪！（注意：即使补吃 B12，体内残留笑气灭活前新合成通道依然受阻，唯绝对停用数日并大剂量甲钴胺治疗方可逆转）",
        mechanism_summary="极短血液清除 + 极长生物酶不可逆毒性代表：3 分钟呼出原形，但不可逆氧化灭活体内维生素 B12 依赖型甲硫氨酸合酶造成跨日/跨周神经脱髓鞘损害。",
        active_metabolites=[],
        receptor_targets=[
            target("NMDA 谷氨酸受体", "非竞争性阻滞 (触发 1 - 3 分钟剧烈解离、幻听及镇痛效应)", "high", 0.94, "母体主导"),
            target("TREK-1 K2P 通道 / GABA-A", "双向钾通道开放与轻度抑制增强 (辅助全身镇痛)", "moderate", 0.68, "母体主导"),
            target("甲硫氨酸合酶 (B12 依赖酶)", "不可逆氧化钴离子失活（连日吸食致致命周围神经病变与脊髓亚急性变性核心毒理！）", "high", 0.95, "药效动力学不可逆主导"),
        ],
    ),
    "LSD": pk(
        half_life="初始经典消除相 2.6 - 3.6 小时 (Dolder/Liechti 人类受控试测典型 3.6h；晚期极微残余终末相约 8.9h)",
        half_life_hours=3.6,
        t_max="口服 1.0 - 1.5 小时 (典型 1.4h；主观效应常持续 8 - 12h 远超血浆半衰期！)",
        t_max_hours=1.4,
        bioavailability="口服约 71% (高吸收入血并呈典型剂量比例线性增长)",
        cyp_enzymes=[
            "CYP2D6 (重要代谢底物 / Substrate；CYP2D6 慢代谢基因型或抑制剂可使人体 LSD AUC 显着上升约 75% 并延长致幻时长！)",
            "CYP1A2 / CYP3A4 / CYP2E1 (共同参与 2-氧-3-羟基-LSD 及 N-去甲基氧化代谢 / Substrate)",
        ],
        clearance="几乎完全经肝氧化代谢为无明显致幻活性的 2-氧-3-羟基-LSD (O-H-LSD，尿中主要检出物) 与去甲 LSD；仅约 1% 原形尿排",
        pk_role="IRREVERSIBLE_PD",
        effective_half_life_hours=3.6,
        washout_hours=24.0,
        washout_note="LSD 是结构药理学上经典的“血浆快清、受体慢脱”典范：人体血浆半衰期仅约 3.6 小时，但麦角二乙胺结构会令 5-HT2A 受体第 2 胞外环 (EL2) 如“盖子 (Lid)”般将 LSD 分子紧锁在结合口袋内，大幅放缓受体解离速率（驻留长达数小时），从而解释何以血药早已下降半截而主观致幻旅程仍平稳拉长至 8 - 12 小时！CYP2D6 慢代谢或抑制剂会明显加剧及延长旅程强度。同时快速触发 5-HT2A 受体下调内化，引起极强 5 - 7 天跨药物急性交叉耐受。",
        mechanism_summary="高亲和力“锁盖式”慢解离 5-HT2A 部分激动剂：血浆半衰期约 3.6h，受体 EL2 构象滞留将致幻期稳定撑起 8 - 12 小时，受 CYP2D6 酶显著调控。",
        active_metabolites=[],
        receptor_targets=[
            target("5-HT2A 受体", "慢解离高效部分激动剂 (Ki ≈ 2.9 nM，胞外环盖合滞留机制，触发深层致幻与前额叶整合重排)", "high", 1.0, "母体主导"),
            target("5-HT1A / 5-HT2B / 5-HT2C 受体", "高亲和力多亚型激动剂 (Ki ≈ 1 - 10 nM)", "high", 0.90, "母体主导"),
            target("D2 / D1 / TAAR1 受体", "中等部分激动 (解释 LSD 后半段相对其他色胺类更明显的兴奋感与思维加速)", "moderate", 0.72, "母体主导"),
        ],
    ),
    "1P-LSD": pk(
        half_life="母体在血液中闪电水解（半衰期仅数分钟，血浆几乎不可长期测得），体内全部暴露转化成活性体 LSD，后者消除相 3.6 - 6.4 小时 (典型 5.7h 临床报告均值)",
        half_life_hours=0.25,
        t_max="水解生成的游离 LSD 约在口服后 1.0 - 2.0 小时达峰 (典型 1.5h)",
        t_max_hours=0.5,
        bioavailability="人类临床受控研究实测（Halberstadt/Grumann/Brandt 2020）：口服经全身酯酶迅速水解脱丙酰基生成 LSD，最终生成功效等效于接近 100% 摩尔等量的口服 LSD 生物利用度！",
        cyp_enzymes=[
            "血浆及肝脏非特异性酯酶极速水解脱去 N1-丙酰基转化为 LSD；随后生成物 LSD 经 CYP2D6 / CYP1A2 / CYP3A4 (代谢底物 / Substrate) 清除",
        ],
        clearance="母体极快（数分钟级）水解消失变为 LSD，此后沿用完全相同的 LSD 代谢与尿排路径",
        pk_role="PRODRUG",
        effective_half_life_hours=5.7,
        washout_hours=30.0,
        washout_note="已由严格受控的人类静脉与口服药代试验（Brandt/Halberstadt/Liechti 团队）实证：1P-LSD 原形几乎不对 5-HT2A 起效，而是完美充当 LSD 的 1-丙酰前药（几乎 100% 转化释放 LSD，血药浓度曲线与等摩尔口服 LSD 高度吻合）。后续药效时长、耐受与 CYP2D6 增效风险与 LSD 完全一致。",
        mechanism_summary="经人类试验严密证实的 LSD 专一性前药：自身对 5-HT2A 极弱，口服迅速去丙酰转化为完全等效游离 LSD。",
        active_metabolites=[
            met(
                "LSD (麦角酸二乙酰胺)",
                "母体在血清与肝脏内经羧酸酯酶迅速水解除去 N1-丙酰基释放",
                "3.6 - 6.4 小时 (临床研究末端均值 ~5.7h)",
                5.7,
                1.5,
                1.0,
                "完全等效于口服释放约 100% 摩尔比例游离 LSD 暴露",
                "绝对承担 1P-LSD 的全部 5-HT2A 慢解离致幻活性、8 - 12h 时长与跨色胺/麦角胺急性交叉耐受",
            )
        ],
        receptor_targets=[
            target("5-HT2A 受体", "通过释放产物 LSD 发挥极强效部分激动作用（母体原形体外亲和力极微弱）", "high", 0.99, "代谢物主导"),
        ],
    ),
    "Psilocin": pk(
        half_life="1.4 - 3.0 小时 (Holze/Liechti 人类受控研究均值约 2.2h)",
        half_life_hours=2.2,
        t_max="口服 1.5 - 2.5 小时 (典型 2.0h；直接作为游离酚口服较前药裸盖菇素起效略提前约 15 - 20 分钟)",
        t_max_hours=2.0,
        bioavailability="约 50% - 55% (源自裸盖菇素定量折算参考；经肝与肠壁 UGT1A9/UGT1A10 显著首过葡萄糖醛酸化及 MAO-A 氧化)",
        cyp_enzymes=[
            "主要不经肝细胞色素氧化代谢 (依赖小肠 UGT1A10 与肝脏 UGT1A9 葡萄糖醛酸化生成赛洛辛-O-葡糖苷酸，及 MAO-A / ALDH 氧化降解为 4-HIAA)",
        ],
        clearance="经 UGT1A9/1A10 转化为赛洛辛-O-葡萄糖醛酸苷（占尿排 > 80%）及经 MAO-A 氧化去氨基，绝大部分于 24 小时内经肾脏彻底排空",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.2,
        washout_hours=14.0,
        washout_note="脱磷酸裸盖菇素 (Psilocin) 本身即是一切致幻蘑菇真正作用于受体的最终活性实体，半衰期仅 2.2 小时且约 14 小时即完全清零；但与麦角类相仿，急性单次给药即触发剧烈短期 5-HT2A 内化耐受（通常需要 7 - 14 天复原）。注意 MAO-A 参与其氧化降解——联用强效 MAOI（如骆驼蓬碱/卡皮木或处方苯乙肼）会剧烈延长并猛增至极度不可控的迷幻冲击（Psilohuasca）。",
        mechanism_summary="经典天然 4-取代色胺类最终活性本体：短半衰期高效激动 5-HT2A 与 5-HT1A，主导 4 - 6 小时精准温润旅程。",
        active_metabolites=[],
        receptor_targets=[
            target("5-HT2A 受体", "高亲和力部分激动剂 (Ki ≈ 6 - 15 nM，驱动深层视听知觉改变与自我边界消融)", "high", 0.98, "母体主导"),
            target("5-HT1A / 5-HT2C / 5-HT2B 受体", "高亲和力激动/部分激动 (Ki ≈ 49 - 190 nM；无直接多巴胺 D2 亲和力，较少 LSD 的过度交感振奋)", "high", 0.88, "母体主导"),
        ],
    ),
    "4-PO-DMT": pk(
        half_life="前药裸盖菇素 (Psilocybin) 于小肠刷状缘及血浆碱性磷酸酶闪电脱磷酸化完全转化，人体血浆几乎测不到母体原形；释放之活性主体 Psilocin 半衰期约 1.8 - 2.6 小时 (典型 2.2h)",
        half_life_hours=0.1,
        t_max="转换释放的活性赛洛辛口服后约 1.5 - 2.5 小时达峰 (典型 1.8h)",
        t_max_hours=1.8,
        bioavailability="口服转化释放为 Psilocin 的系统绝对利用度约 50% - 55% (平均 52.7%，经肠壁及肝脏部分首过 UGT 结合)",
        cyp_enzymes=[
            "不经肝细胞色素酶激活 (由肠黏膜及血浆碱性磷酸酶 ALP 脱磷酸变成 Psilocin，随后经 UGT1A9/UGT1A10 与 MAO-A 清除)",
        ],
        clearance="母体极快全量脱磷酸化消失；衍生出的 Psilocin 经 UGT 葡萄糖醛酸化与 MAO-A 降解后 24 小时内迅速经尿消除",
        pk_role="PRODRUG",
        effective_half_life_hours=2.2,
        washout_hours=14.0,
        washout_note="裸盖菇素 (Psilocybin / 4-PO-DMT) 本身只是带磷酸根以抗空气氧化的稳定“前药保护壳”！经肠壁碱性磷酸酶全速去磷酸化转化成真正的脑内致幻实体 Psilocin (t1/2 ≈ 2.2h) 起效，受热胃肠空腹约 20 - 45 分钟即快速转化涌现；联用 MAOI 会阻碍下游代谢急剧放大体验强度。",
        mechanism_summary="高稳定磷酸酯前药体系：自身不入脑亦不激活受体，在肠壁极快转化为最终致幻主体 Psilocin。",
        active_metabolites=[
            met(
                "Psilocin (脱磷酸裸盖菇素 / 4-HO-DMT)",
                "肠黏膜刷状缘及血浆碱性磷酸酶 (ALP) 迅速水解除去磷酸基团生成",
                "1.8 - 2.6 小时 (人类临床均值 2.2h)",
                2.2,
                1.8,
                1.0,
                "完全承担裸盖菇素在体内的 100% 游离及结合系统暴露（原药几不可检出）",
                "全面激活中枢 5-HT2A/5-HT1A/5-HT2C 受体驱动典型 5 - 6 小时蘑菇致幻旅程",
            )
        ],
        receptor_targets=[
            target("5-HT2A 受体", "经酶解产物 Psilocin 强效部分激动（裸盖菇素母体自身对 5-HT2A 几乎无亲和力）", "high", 0.98, "代谢物主导"),
        ],
    ),
    "DMT": pk(
        half_life="静注初期快速分布与脱氨基半衰期仅 5 - 7 分钟 (0.1h)；终末血浆清除约 9 - 20 分钟 (典型 0.25h)",
        half_life_hours=0.2,
        t_max="静脉注射或雾化抽吸仅 1 - 2 分钟 (0.03h) 即达全脑巅峰！单独口服彻底被胃肠 MAO-A 首过毁灭几乎 0% 吸收",
        t_max_hours=0.03,
        bioavailability="口服单用 ~0% (除非联用可逆 MAO-A 抑制剂，如死藤水)；静脉 100%；吸入/抽吸估计约 20% - 40%+ 快速全脑穿入",
        cyp_enzymes=[
            "不依赖肝脏 CYP450 启动清除 (绝对依赖线粒体外膜单胺氧化酶 A / MAO-A 在全身及中枢闪电氧化脱氨基失活为 IAA)",
        ],
        clearance="被极高通量的外周与脑内 MAO-A 在 5 - 20 分钟内瞬间代谢为完全无活性的吲哚-3-乙酸 (IAA)，极少原形排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=0.2,
        washout_hours=1.5,
        washout_note="纯 N,N-DMT（无 MAOI 配伍时）具备已知经典致幻剂中最极端、最短促的消除动力学：血浆半衰期仅约 9 - 20 分钟，抽吸后 15 分钟即回落清朗、1 小时完全彻底清洗！且神奇地极少呈现类似 LSD/蘑菇的多日急性耐受现象。然而一旦同服 MAO-A 抑制剂（如骆驼蓬碱/处方抗抑郁 MAOI），肠道与全身酶降解被彻底阻断，不仅转为强效口服，半衰期更暴增至 2 - 4 小时以上！",
        mechanism_summary="超高通量 MAO-A 底物型经典短效内源性色胺致幻剂：无 MAOI 保护时口服完全失效，静注或抽吸制造持续仅 10 - 20 分钟的爆发级感知突破。",
        active_metabolites=[],
        receptor_targets=[
            target("5-HT2A 受体", "高效部分/完全激动剂 (Ki ≈ 75 - 230 nM，快进快出瞬间颠覆全脑默认模式网络)", "high", 0.96, "母体主导"),
            target("5-HT1A / 5-HT2C 受体", "显著激动作用", "high", 0.85, "母体主导"),
            target("σ1 / TAAR 受体", "内源性微摩尔配体结合调节", "low", 0.45, "母体主导"),
        ],
    ),
    "Ayahuasca": pk(
        half_life="复方内 MAO-A 抑制剂阻断后：DMT 终末半衰期暴增至 2.0 - 4.0 小时 (Riba 人类临床试测均值 ~3.5h)；核心伴生 β-咔啉骆驼蓬碱 (Harmine) 1.5 - 2.0h，四氢骆驼蓬碱 (THH) 长达 5.0 - 9.0h",
        half_life_hours=3.5,
        t_max="口服死藤水后 DMT 达峰 1.5 - 2.0 小时 (典型 1.8h)；Harmine 1.0 - 1.7h；Harmaline 2.0 - 2.4h；THH 2.5 - 3.0h",
        t_max_hours=1.8,
        bioavailability="依托死藤 (Banisteriopsis caapi) 中可逆 MAO-A 抑制生物碱封闭肠道与肝脏首过破坏，使原本口服利用度 0% 的 DMT 得以大比例吸收入血入脑",
        cyp_enzymes=[
            "CYP2D6 (Harmine 与 Harmaline 均经 CYP2D6 主导 O-脱甲基转化为 Harmol / Harmalol；且 Harmine 为 CYP2D6 强竞争性底物并具 MAO-A 强可逆抑制 / Strong Inhibitor)",
            "MAO-A (死藤水内β-咔啉高选择性可逆抑制 MAO-A，是激活口服 DMT 兼诱发所有危险 5-HT 血清素交互的总开关！)",
        ],
        clearance="Harmine/Harmaline 主要经肝 CYP2D6 O-脱甲基后结合尿排；THH 慢清除主要原形兼氧化排泄；受保护的 DMT 除部分随 Harmine 衰减恢复 MAO-A 清除外亦走 N-氧化路径",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=5.0,
        washout_hours=36.0,
        washout_note="超级致命交互警报：死藤水绝非单纯的“口服 DMT”，它本质是强可逆性 MAO-A 抑制剂复方汤剂！由于含高量抑制外周及中枢 MAO-A 的骆驼蓬碱、骆驼蓬灵及持久约 5 - 9 小时的四氢骆驼蓬碱 (THH)，吞饮前/后若体内尚存 SSRI、SNRI、MDMA、曲马多、右美沙芬、哌替啶或拟交感兴奋剂，将直接爆发极高致死率的重度血清素综合征与高血压危象！停用 SSRI 后须视药物半衰期至少洗脱 2 - 5 周才安全。CYP2D6 慢代谢者因无法及时清除 Harmine 也会承受极剧烈的长期呕吐眩晕及延长的迷幻冲击。",
        mechanism_summary="经典天然药代协同复方体系：β-咔啉可逆阻断肠肝 MAO-A “打开”了 DMT 口服之门，并通过长达数小时的动态酶抑制维持 4 - 6 小时深度蜕变致幻旅程。",
        active_metabolites=[
            met(
                "Harmine / Harmaline / Tetrahydroharmine (β-咔啉 MAO-A 抑制群)",
                "死藤水内源伴生药代增效成分 (THH 半衰期显著长达 5 - 9h)",
                "Harmine 1.5 - 2.0h；THH 5.0 - 9.0h",
                5.0,
                2.0,
                1.0,
                "死藤水生物碱组中主导护航与 MAO-A 阻滞时程的关键配体",
                "可逆抑制肠壁/脑内 MAO-A (Harmine/Harmaline) 并附带轻度 5-HT 再摄取抑制 (THH)，决定口服 DMT 能否顺利起效以及整个高危药物交互窗口期",
            )
        ],
        receptor_targets=[
            target("5-HT2A / 5-HT1A 受体", "经保护长效吸收的 DMT 强势部分激动核心致幻体", "high", 0.98, "母体主导"),
            target("单胺氧化酶 A (MAO-A)", "β-咔啉生物碱可逆性强效抑制（所有致死性 SSRI/MDMA/拟交感交互暴雷中心！）", "high", 0.96, "辅助增效组主导"),
        ],
    ),
    "Mescaline": pk(
        half_life="约 3.5 小时 (Liechti 团队最新人类 100 - 800 mg 临床试验实测均值约 3.5h，过去非精确推算 6h 属于主观药效延迟高估)",
        half_life_hours=3.5,
        t_max="口服 1.5 - 2.5 小时 (人体实测均值 ~2.0h；主观强烈恶心往往在吸收爬坡期达峰，总历程长达 10 - 14 小时)",
        t_max_hours=2.0,
        bioavailability="口服生物利用度 ≥ 53% (吸收完全但大剂量伴随消化道刺激与植物多糖延迟吸收)",
        cyp_enzymes=[
            "主要不经肝微粒体 CYP450 代谢 (极少参与常见 CYP 相互作用；主体经单胺氧化酶/半卡巴肼敏感胺氧化酶 SSAO 氧化脱氨基为无活性 3,4,5-三甲氧基苯乙酸 TMPAA)",
        ],
        clearance="人类实测绝大部分以未变化原形（约 50% - 60%）及非活性氧化产物 TMPAA（约 25% - 30%）经肾随尿排出，少量经 N-乙酰化",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=3.5,
        washout_hours=24.0,
        washout_note="瑞士巴塞尔大学 Liechti 团队（2023 年人类直接临床受控试测）精确纠正了传统“6 小时”模糊老数据：麦司卡林母体真实人类血浆半衰期仅为约 3.5 小时，但因其显著分布渗透脑组织、极高有效剂量（数百毫克）伴随长时间受体饱和下调，使其主观致幻状态（10 - 14 小时）与跨致幻剂耐受远长于血浆半衰期！24 小时内基本清洗出体循环。",
        mechanism_summary="经典天然苯乙胺类原型致幻剂：绕开主要 CYP 通路，母体直接激动 5-HT2A/2C 及微弱调节 α 肾上腺素受体的同时主导 10 - 14h 长程视觉体验。",
        active_metabolites=[],
        receptor_targets=[
            target("5-HT2A / 5-HT2C 受体", "强效高药量部分激动剂 (亲和力远低于 LSD，需数百毫克达到等量中枢占领，诱导极稳定生动的几何色彩与共情感受)", "high", 0.95, "母体主导"),
            target("5-HT2B / α2A / TAAR1 受体", "弱至中等激动协同（引发初期显著瞳孔散大、血压轻升高及胃肠道痉挛呕吐反应）", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Ibogaine": pk(
        half_life="母体受 CYP2D6 多态性控制极宽 1.0 - 6.0 小时 (快代谢者典型 ~3.5h，慢代谢骤延)；主力超长效活性产物去甲伊博加因 (Noribogaine) 半衰期长达 24.0 - 49.0 小时 (典型 36.0h)！",
        half_life_hours=3.5,
        t_max="母体 1.0 - 3.0 小时 (典型 2.0h)；去甲伊博加因约 3.0 - 4.0 小时达峰并维持平台",
        t_max_hours=2.0,
        bioavailability="口服在人类迅速吸收进入高脂溶组织及肝脏，经历极强且高度依赖 CYP2D6 的首过 O-脱甲基转化",
        cyp_enzymes=[
            "CYP2D6 (绝对决定性首过激活转化底物 / Substrate；将其迅速转化为超长效主要抗渴求实体去甲伊博加因 Noribogaine；慢代谢者母体剧增心脏毒性！)",
        ],
        clearance="母体经 CYP2D6 迅速代谢且自身高度蓄积脂肪组织，而高极性的活性代谢物去甲伊博加因随后缓慢通过 UGT 结合与肾/胆汁长达数日渐进清除",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=36.0,
        washout_hours=180.0,
        washout_note="伊博加因拥有严重甚至致命的心脏与长程动力学双重风险：① 其超长半衰期产物去甲伊博加因 (t1/2 ≈ 36h) 会连续滞留体内长达 7 - 10 天以上（既提供阿片防复吸减渴求窗口，也同时显著拉长药效后遗失眠期）；② 更关键的是：伊博加因自身及高暴露母体（尤以 CYP2D6 慢代谢或合用氟西汀/帕罗西汀等抑制剂者）具有极强的 **hERG 钾通道阻滞**毒性，会剧烈延长心电图 QTc 间期并引发致命尖端扭转型室速 (TdP) 猝死！绝对禁用电解质紊乱或联合其他延 QTc 药物（如美沙酮、氟哌啶醇、昂丹司琼）！",
        mechanism_summary="多靶点致幻与成瘾逆转生物碱：母体以快效强烈的致幻“梦境回溯期”与极危险的 hERG 心脏毒性示警，经 CYP2D6 生成的去甲伊博加因则主导长达数日的 κ-激动、NMDA 阻断及 SERT 稳定期。",
        active_metabolites=[
            met(
                "Noribogaine (去甲伊博加因 / 12-羟基伊博加胺)",
                "母体经肝 CYP2D6 O-去甲基化转化生成 (CYP2D6 EM 人群转化极快且彻底)",
                "24.0 - 49.0 小时 (人类临床均值 36.0h)",
                36.0,
                3.5,
                4.0,
                "正常代谢者稳态 AUC 暴露高出母体 4 - 10 倍以上！",
                "对 κ-阿片受体与 μ-阿片部分调节及 SERT 抑制表现出更专一、更安全的亲和力（是减少阿片戒断反应及长达一周“无渴求空窗期”的真正活性核心）",
            )
        ],
        receptor_targets=[
            target("hERG 心脏钾离子通道", "强效致命级阻滞剂 (IC50 ≈ 1 - 4 μM，严重延长 QTc 诱发尖端扭转型室速猝死核心警戒！)", "high", 0.96, "母体主导"),
            target("κ-阿片受体 (KOR) & SERT", "去甲伊博加因主导中重度激活及血清素再摄取阻断 (严禁近期联用阿片或 SSRI/MAOI！)", "high", 0.92, "双相共同主导"),
            target("α3β4 nAChR / NMDA / σ2 受体", "非竞争性拮抗调节 (参与多通路成瘾记忆脱敏与剧烈共济失调)", "high", 0.86, "母体主导"),
        ],
    ),
    "Cannabidiol": pk(
        half_life="单剂口服 18 - 32 小时；临床连续稳态口服 (Epidiolex) 因高度亲脂积聚终端半衰期高达 56.0 - 61.0 小时",
        half_life_hours=58.0,
        t_max="口服约 2.5 - 5.0 小时 (典型 3.5h)",
        t_max_hours=3.5,
        bioavailability="空腹口服极低仅约 6%！随高脂餐饮食同服时 AUC 暴露量暴增 4 - 5 倍以上！(极端饮食敏感性)",
        cyp_enzymes=[
            "CYP2C19 (主要羟化底物 / Substrate，转化为活性产物 7-羟大麻二酚 7-OH-CBD)",
            "CYP2C19 (FDA 说明书明确标定为中效抑制剂 / Moderate Inhibitor！大幅提升氯吡格雷前药阻滞与地西泮/西酞普兰/去甲文拉法辛等底物浓度！)",
            "CYP3A4 (主要氧化代谢底物 / Substrate；同时在体外呈中弱抑制)",
            "CYP2C9 / UGT1A9 / UGT2B7 (参与代谢清除并抑制 UGT 结合，如大幅抬高氯巴占与丙戊酸钠肝毒反应)",
        ],
        clearance="主要经 CYP2C19 与 CYP3A4 生成活性 7-OH-CBD 并继续氧化生成非活性 7-COOH-CBD（循环浓度极高），经粪便（主）及尿液排出",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=58.0,
        washout_hours=280.0,
        washout_note="重大相互作用被严重低估的天然大麻素：绝非“温和草本补剂”！Epidiolex FDA 说明书严正警示：① 高脂餐可使口服吸收暴增 4 - 5 倍；② 反复连用半衰期长达 56 - 61 小时，完全清洗需长达 10 - 12 天；③ CBD 是明确的临床级 **CYP2C19 中效抑制剂**，并显著抑制 UGT！与抗癫痫药氯巴占（使去甲氯巴占浓度暴涨 3 倍致昏睡）、丙戊酸钠（剧增转氨酶暴发性升高达 3 - 5 倍！）、地西泮、艾司西酞普兰、奥美拉唑及华法林合用时必须严密调整减量！",
        mechanism_summary="长半衰期多靶点非致幻大麻素：母体兼具极强的 CYP2C19/UGT 代谢酶抑制风险，与活性羟化产物 7-OH-CBD 协同调控神经元过度兴奋。",
        active_metabolites=[
            met(
                "7-Hydroxycannabidiol (7-OH-CBD / 7-羟大麻二酚)",
                "母体经肝 CYP2C19 主导 7-位羟基化生成",
                "约 50 - 60 小时 (稳态受母体清除率限速)",
                55.0,
                4.0,
                0.38,
                "人体循环血浆 AUC 暴露量约为母体的 35% - 40%（7-COOH-CBD 更达 40 倍但无抗惊厥活性）",
                "临床研究证实 7-OH-CBD 抗癫痫效价与母体 CBD 相当，直接共同主导连续治疗期的神经稳定性",
            )
        ],
        receptor_targets=[
            target("TRPV1 / GPR55 / ENT1 腺苷平衡转运体", "激动 TRPV1 脱敏、拮抗 GPR55 兴奋性及抑制 ENT1 提高胞外腺苷 (抗惊厥核心多靶机制)", "high", 0.90, "双相共同主导"),
            target("CB1 / CB2 受体", "负向变构调节剂 (NAM，极低直接结合，微弱缓冲 THC 引起的焦虑与偏执狂)", "moderate", 0.65, "母体主导"),
            target("5-HT1A 受体", "微摩尔级部分激动调节", "low", 0.48, "母体主导"),
        ],
    ),
    "Cannabis": pk(
        half_life="核心活性大麻素 Δ9-THC 在体循环初相分布约 2 - 4 小时，但高度亲脂蓄积于脂肪后持续缓慢回流的终端消除半衰期在偶用者达 24 - 72 小时 (典型 48h)，重度每日吸食者可拖延至 5 - 13 天！",
        half_life_hours=48.0,
        t_max="雾化/吸食 3 - 10 分钟 (0.15h) 极速入脑；口服大麻食品 (Edibles) 延至 1.0 - 3.0 小时达峰 (效应剧增 3 倍以上)",
        t_max_hours=0.2,
        bioavailability="肺吸入 10% - 35% (典型 ~25% - 30%，迅速规避肝首过)；口服受胃酸不稳及强烈肝首过转化为活性 11-OH-THC 仅约 4% - 20% (平均 6% - 12%)",
        cyp_enzymes=[
            "CYP2C9 (核心代谢底物 / Substrate；负责将 THC 羟化为更易入脑且效价更高的活性产物 11-OH-THC)",
            "CYP3A4 (重要氧化代谢底物 / Substrate；催化多种羟基产物生成与 11-OH-THC 清除)",
            "CYP2C19 / CYP2C9 (高负荷抽吸及含较高 CBD 品种呈一定抑制潜力 / Inhibitor)",
        ],
        clearance="极度亲脂、快速隐匿入脑及全身体脂库并缓慢释出；经 CYP2C9 转化活性 11-OH-THC 后再氧化为非活性 11-COOH-THC 经 UGT 结合主要由粪便 (~65%) 及尿液 (~20%) 排出",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=48.0,
        washout_hours=240.0,
        washout_note="大麻呈现药代动力学上最受途径操纵的双面性：① 肺部抽吸时母体 Δ9-THC 在 5 - 10 分钟瞬时冲击 CB1，随后 2 - 4 小时急性醉感退去，但其脂溶终端半衰期在偶用者长达 1 - 3 天、高频使用者脂肪库甚至需要 2 - 4 周才能让尿检非活性羧酸产物 11-COOH-THC 转阴；② 口服（如大麻布朗尼/软糖）虽仅 10% 吸收，但肝脏 CYP2C9 会将其大规模首过转化成更亲脂更强效的 **11-OH-THC**，带来极容易过量惊恐且滞留长达 6 - 10 小时的猛烈冲击！",
        mechanism_summary="多组分大麻素体系（Δ9-THC 核心主导 + CBD 缓冲）：吸入以母体瞬间触发、口服依托首过活性代谢物 11-OH-THC 大幅加倍延长效应。",
        active_metabolites=[
            met(
                "11-Hydroxy-Δ9-THC (11-OH-THC / 11-羟基四氢大麻酚)",
                "母体经肝脏 CYP2C9 首过氧化（尤其口服途径时生成比例激增 3 - 5 倍）",
                "12.0 - 36.0 小时 (脂库释出限速跟随终端消除，初相 ~4 - 6h)",
                24.0,
                2.0,
                0.8,
                "吸入时约占母体 10% - 20%；口服 Edibles 时循环暴露常追平甚至反超母体 THC！",
                "通过血脑屏障速度比 THC 母体更快且对 CB1 受体激动效价相当或更强，是口服大麻产品突如其来“过度致幻与极度惊恐”的关键推手",
            )
        ],
        receptor_targets=[
            target("CB1 大麻素受体", "突触前部分激动剂 (Ki ≈ 5 - 40 nM，抑制 GABA/谷氨酸囊泡释放，驱动放松欣快、时间扭曲及偏执惊恐)", "high", 0.96, "双相共同主导"),
            target("CB2 大麻素受体", "外周免疫细胞及胶质细胞部分激动 (Ki ≈ 36 - 75 nM，抗炎与免疫调节)", "moderate", 0.72, "双相共同主导"),
        ],
    ),
    "2C-B": pk(
        half_life="约 1.3 - 1.5 小时 (Papaseit 等人类受控口服临床试验实测均值约 1.4 小时)",
        half_life_hours=1.4,
        t_max="口服约 1.5 - 2.0 小时 (人类受控临床试验实测达峰时间)",
        t_max_hours=1.8,
        bioavailability="口服约呈中等以上吸收（极易受 MAO 氧化首过及鼻黏膜强灼痛吸收差异影响；鼻吸起效瞬间放大约 2 倍）",
        cyp_enzymes=[
            "主要不经肝细胞色素 CYP450 主导清除 (主要通过单胺氧化酶 MAO-A / MAO-B 氧化脱氨基及醛脱氢酶还原生成 2,5-二甲氧基-4-溴苯乙酸)",
        ],
        clearance="极高比例经肝 MAO-A/MAO-B 氧化脱去氨基并随后 O-去甲基化结合排出，仅微量原形见于尿液",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=1.4,
        washout_hours=8.0,
        washout_note="根据最新人类受控临床药代动力学研究（Papaseit/Mallaroni 2024），2C-B 在人体内消除相当迅速，母体血浆半衰期仅约 1.4 小时（约 8 小时基本清零），因此主观致幻体验集中维持在 4 - 6 小时（明显短于 LSD/麦司卡林且次日宿醉轻微）。但因为依赖 MAO-A/B 失活，联用 MAOI 会大幅放大与拉长体验并伴随强烈血管收缩升高血压风险。",
        mechanism_summary="短半衰期经典 2C 家族二甲氧基苯乙胺致幻剂：低剂量偏共情触觉放大，中高剂量转入清亮几何视觉而不易陷于认知混乱。",
        active_metabolites=[],
        receptor_targets=[
            target("5-HT2A / 5-HT2C 受体", "高效部分激动剂 (低量轻柔感官增强，剂量陡峭曲线下高量呈鲜明色彩几何图案)", "high", 0.94, "母体主导"),
            target("5-HT2B / α1 肾上腺素受体", "中等结合激活 (引起躯体负荷感、肌肉轻颤与血压心率轻度上升)", "moderate", 0.60, "母体主导"),
        ],
    ),
    "5-MeO-DMT": pk(
        half_life="鼻腔喷雾人类临床研究实测终末半衰期 12 - 25 分钟 (0.3h，绝大部分受试者在 2 小时内低于检测下限)",
        half_life_hours=0.3,
        t_max="雾化抽吸仅 1 - 2 分钟极速冲顶；鼻腔喷雾临床实测 8 - 10 分钟 (0.15h) 达峰",
        t_max_hours=0.15,
        bioavailability="口服单用极低因肠胃 MAO-A 摧毁；静脉/雾化极快穿透血脑屏障入脑；鼻喷吸收稳定高利用度",
        cyp_enzymes=[
            "MAO-A (绝对主力氧化脱氨基失活途径；严禁联用 MAOI 如死藤水或抗抑郁药以免发生高热及致命呼吸循环衰竭！)",
            "CYP2D6 (次要 O-脱甲基底物 / Substrate；仅转化 < 1% - 3% 极微量蟾毒色胺 Bufotenin，临床微不足道)",
        ],
        clearance="几乎全数在 15 - 30 分钟内经全身 MAO-A 氧化降解为 5-甲氧基吲哚乙酸后随尿排泄",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=0.3,
        washout_hours=2.0,
        washout_note="5-MeO-DMT 拥有极高强度且极度短暂的人体动力学曲线（血浆半衰期不到 25 分钟，抽吸仅持续 15 - 30 分钟，约 2 小时完全清零）。极具生命危险的致命黑框：绝对严禁在服用任何 MAOI（骆驼蓬碱、苯乙肼、吗氯贝胺）或 SSRI/锂盐期间联用 5-MeO-DMT！历史已记录多例因混合死藤水/MAOI 与 5-MeO-DMT 而瞬间诱发严重血清素综合征、高热抽搐及心跳呼吸骤停死亡案例！",
        mechanism_summary="超短效极端强力 5-HT1A/5-HT2A 全意识消融型色胺：抽吸数秒击穿“自我溶解”，极度依赖 MAO-A 安全灭活。",
        active_metabolites=[],
        receptor_targets=[
            target("5-HT1A 受体", "超高亲和力强效完全/部分激动剂 (Ki ≈ 1.9 - 3.0 nM，偏好性甚至高于 5-HT2A，核心诱导“白色虚空/全非二元自我消融”)", "high", 0.99, "母体主导"),
            target("5-HT2A / 5-HT2C / 5-HT7 受体", "强效激动作用 (伴随极重心血管压力与极端躯体震撼感)", "high", 0.92, "母体主导"),
            target("SERT / NET", "微弱抑制（合用 MAOI 时与 5-HT 激动协同致死）", "moderate", 0.55, "母体主导"),
        ],
    ),
    "Salvinorin A": pk(
        half_life="人类雾化吸入高剂量受控研究实测血浆消除半衰期约 49 - 50 分钟 (0.82h；极迅速进入大脑并于 8 分钟内从脑组织重新分布逃逸！)",
        half_life_hours=0.82,
        t_max="雾化抽吸后血浆及主观心理效应在 2 分钟内 (0.03h) 瞬间登顶",
        t_max_hours=0.03,
        bioavailability="口服在胃酸及首过酯酶下几乎完全水解失活 (0%)；颊/舌下利用度极微；高压高温雾化抽吸极其迅速透脑起效",
        cyp_enzymes=[
            "不经肝细胞色素 CYP450 代谢启动 (主要经血浆与组织羧酸酯酶极其迅速水解 2-乙酰氧基为彻底无活性的 Salvinorin B)",
        ],
        clearance="血浆酯酶水解成失活产物 Salvinorin B，辅以快速向非脑组织分布使脑内浓度在短短 5 - 10 分钟即暴跌归零",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=0.82,
        washout_hours=4.0,
        washout_note="萨尔维诺林 A（Salvinorin A）展现令人惊叹的脑浆分离与酯酶水解消退特征（Johnson et al., 2016 人类药代及 PET 研究）：抽吸仅 40 - 120 秒直达癫痫样意识彻底翻转高峰，尽管外周血浆残留半衰期测量约 49 分钟，但由于大脑组织外排与分布极其迅猛，绝大多数极端致幻与解离主效应会在 10 - 20 分钟内瞬间撤离、约 4 小时体内清零。",
        mechanism_summary="已知自然界效价最高且唯一完全不含氮原子的非生物碱型高选择性 κ-阿片受体 (KOR) 完全激动剂。",
        active_metabolites=[],
        receptor_targets=[
            target("κ-阿片受体 (KOR)", "超高选择性完全激动剂 (Ki ≈ 1.3 - 4.0 nM；对 MOR/5-HT2A/NMDA 零结合，彻底通过伏隔核多巴胺抑制及屏状核打断引发强烈解离烦躁感与维度撕裂幻觉)", "high", 1.0, "母体主导"),
        ],
    ),

    # ==================== Group 4: Sedatives, Antihistamines, Barbiturates, BZDs, GABAb ====================
    "Diphenhydramine": pk(
        half_life="7.0 - 9.0 小时 (成人典型 8.0h；老年人群可达 13 - 17h，儿童约 5h)",
        half_life_hours=8.0,
        t_max="1.5 - 2.0 小时 (口服典型 1.8h)",
        t_max_hours=1.8,
        bioavailability="口服约 72% (经肝脏一定首过 N-去甲基清除)",
        cyp_enzymes=[
            "CYP2D6 (主要代谢底物 / Substrate；催化部分清除)",
            "CYP2D6 (明确的中度至强效竞争性抑制剂 / Moderate Inhibitor！可明显阻滞可待因/曲马多激活并升高美托洛尔、氟西汀暴露！)",
            "CYP1A2 / CYP2C9 / CYP2C19 (次要脱甲基代谢底物 / Substrate)",
        ],
        clearance="主要经肝脏连续两次 N-去甲基化及氧化脱氨基生成二苯甲氧基乙酸，最终随尿排出（原形仅 ~2% - 4%）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=8.0,
        washout_hours=42.0,
        washout_note="苯海拉明虽以非处方常见助眠抗过敏药流通，却存在两大致命隐患：① 自身既是明确的中效 CYP2D6 抑制剂（合用美托洛尔、右美沙芬或老式 TCA 时显著叠加蓄积与心脏 QTc 延长），高剂量又强烈阻塞 H1 与中枢 M1 胆碱受体引发极端恐怖谵妄幻觉、心律失常与癫痫；② 约 8 小时半衰期造成长达 40 小时的次日认知与反应受损窗。",
        mechanism_summary="初代高脂溶性乙醇胺类 H1 反向激动剂兼强力中枢 M1 - M5 抗胆碱谵妄剂，母体主导显著镇静与 CYP2D6 抑制。",
        active_metabolites=[],
        receptor_targets=[
            target("H1 组胺受体", "强效高透脑反向激动剂 (Ki ≈ 10 nM，主导剧烈嗜睡镇静)", "high", 0.96, "母体主导"),
            target("M1 - M5 毒蕈碱乙酰胆碱受体", "强效非选择性竞争性拮抗剂 (Ki ≈ 80 - 200 nM，高量触发真实感恐怖谵妄、散瞳、尿潴留与高热干皮)", "high", 0.90, "母体主导"),
            target("心脏 hERG 钾通道 / Nav 钠通道", "超治疗量显著阻滞引发宽 QRS 及室性心律失常", "moderate", 0.62, "母体主导"),
        ],
    ),
    "Vistaril": pk(
        half_life="母体羟嗪 (Hydroxyzine) 约 20.0 小时 (范围 14 - 25h)；肝脏氧化转化的主要活性长效代谢物西替利嗪 (Cetirizine) 约 8.3 小时",
        half_life_hours=20.0,
        t_max="口服约 2.0 小时 (达峰后约 45% - 60% 已进入转化为西替利嗪轨道)",
        t_max_hours=2.0,
        bioavailability="口服迅速吸收完全并伴随显着肝脏醇脱氢酶首过代谢生成高浓度西替利嗪",
        cyp_enzymes=[
            "主要经乙醇脱氢酶 (ADH) 及醛脱氢酶 (ALDH) 氧化醇侧链为羧酸产物西替利嗪 (Cetirizine)",
            "CYP3A4 / CYP3A5 (辅助脱烃代谢途径 / Substrate)",
            "CYP2D6 (轻微抑制作用 / Inhibitor)",
        ],
        clearance="肝 ADH 转化为占代谢 ~45% 的强外周活性体西替利嗪后经尿排泄；高极性西替利嗪几乎不再代谢",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=20.0,
        washout_hours=100.0,
        washout_note="羟嗪（Vistaril / 安泰乐）母体具有长达 20 小时的血浆半衰期，洗脱需达 4 - 5 天！此外其不仅是西替利嗪的前体母药，且母体本身在欧洲/EMA 说明书被严重警示具备高剂量延展心电图 QTc 间期（禁合用胺碘酮、氟哌啶醇、阿奇霉素、美沙酮或左氧氟沙星诱发 TdP 室速）的风险！",
        mechanism_summary="初代高入脑哌嗪类抗焦虑抗组胺药：母体提供强劲中枢 H1 镇静与潜在 QTc 延长警报，代谢转化成的西替利嗪继续持久控制外周组胺过敏。",
        active_metabolites=[
            met(
                "Cetirizine (西替利嗪)",
                "母体羟乙氧基链经肝脏 ADH/ALDH 氧化为羧酸直接生成 (~45%)",
                "7.0 - 10.0 小时 (典型 8.3h)",
                8.3,
                4.0,
                1.5,
                "口服羟嗪后血浆内循环西替利嗪 AUC 常高于或平齐母体",
                "高选择性外周 H1 受体拮抗，几乎无中枢抗胆碱作用，延续抗瘙痒与过敏控制达 24h",
            )
        ],
        receptor_targets=[
            target("H1 组胺受体", "极强效透脑拮抗剂 (Ki ≈ 2 nM，母体及西替利嗪共同高效阻断)", "high", 0.98, "双相共同主导"),
            target("5-HT2A / α1 / D2 受体", "弱至中等拮抗 (贡献独特辅助抗焦虑、轻度抗吐与正立性血压微降，显著少于酚噻嗪的抗胆碱)", "moderate", 0.55, "母体主导"),
            target("心脏 hERG 钾通道", "高暴露下延长 QTc 间期风险", "moderate", 0.50, "母体主导"),
        ],
    ),
    "Unisom": pk(
        half_life="约 10.0 - 12.0 小时 (临床试验成人均值 10.5 小时；老年人延长至 15h+)",
        half_life_hours=10.5,
        t_max="2.0 - 3.0 小时 (典型 2.4h)",
        t_max_hours=2.4,
        bioavailability="口服迅速且广泛吸收（绝对定量利用度未大规模 IV 标定，约 70% - 80%+ 经尿排出母体与代谢物）",
        cyp_enzymes=[
            "CYP2D6 (重要代谢底物 / Substrate；参与 O-去甲基/羟化清除)",
            "CYP1A2 / CYP2C9 (次要 N-脱甲基氧化底物 / Substrate)",
        ],
        clearance="部分经肝 CYP2D6/1A2/2C9 脱甲基及葡萄糖醛酸化，约 60% 未变化原形经尿排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=10.5,
        washout_hours=54.0,
        washout_note="多西拉敏（Unisom 琥珀酸多西拉敏）与苯海拉明相比具有长达 ~10.5 小时的明显更长半衰期，次日宿醉嗜睡感极为突出（50+ 小时洗脱）！超量滥用同样导致严酷的抗胆碱谵妄外，亦在急诊报道高发横纹肌溶解与急性肾损伤，严禁配合酒精与阿片类。",
        mechanism_summary="长半衰期第一代吡啶乙醇胺 H1 反向激动助眠药兼抗胆碱谵妄药，由母体原形主导整晚乃至次日正午长效镇静。",
        active_metabolites=[],
        receptor_targets=[
            target("H1 组胺受体", "强效可透脑拮抗剂 (极深且长程镇静催眠主驱动)", "high", 0.96, "母体主导"),
            target("M1 - M5 毒蕈碱胆碱受体", "强效拮抗剂 (超量产生可怕视幻觉谵妄、口干高热及心动过速)", "high", 0.85, "母体主导"),
        ],
    ),
    "Promethazine": pk(
        half_life="12.0 - 19.0 小时 (典型 15.0h；慢长清除导致显著长程昏睡)",
        half_life_hours=15.0,
        t_max="口服约 4.4 小时 (范围 2 - 5h，吸收较缓慢平缓)",
        t_max_hours=4.4,
        bioavailability="口服平均约 25% (剧烈肝脏首过代谢为非活性异丙嗪亚砜与去甲产物)",
        cyp_enzymes=[
            "CYP2D6 (主要代谢羟化底物 / Substrate；亦对自身及其他 CYP2D6 药物具轻度抑制)",
            "CYP2B6 (次要 N-脱甲基途径底物 / Substrate)",
        ],
        clearance="广泛肝微粒体氧化生成非活性异丙嗪亚砜及葡糖醛酸结合产物随尿及粪便缓慢消除",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=15.0,
        washout_hours=75.0,
        washout_note="异丙嗪拥有惊人的 12 - 19 小时半衰期，单夜服用次日仍留有深重昏沉！致命隐患：常在非法街头制剂（如紫水 Lean）中与可待因混用，在增强镇静止咳同时大幅叠加阿片致死性呼吸抑制及抗胆碱热性惊厥风险（严禁用于 2 岁以下婴幼儿因致死性呼吸骤停黑框警告）；亦对血管有极强刺激性，绝不可动脉或皮下渗漏注射（致组织坏死截肢）。",
        mechanism_summary="长半衰期第一代吩噻嗪类强力中枢镇静、止吐兼中等抗胆碱/多巴胺 D2 阻滞抗组胺药。",
        active_metabolites=[],
        receptor_targets=[
            target("H1 组胺受体", "超强效可透脑拮抗剂 (Ki ≈ 1 - 2 nM，强烈嗜睡)", "high", 0.98, "母体主导"),
            target("M1 毒蕈碱受体 / α1 受体", "显著抗胆碱谵妄及低血压风险", "high", 0.85, "母体主导"),
            target("D2 多巴胺受体", "弱至中等阻断 (止吐机制兼致敏患者静坐不能/锥体外系 EPS 风险)", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Prochlorperazine": pk(
        half_life="单剂静注/口服典型 6.0 - 10.0 小时 (均值 8.0h；多次重复稳态末端半衰期延长至 14 - 22 小时)",
        half_life_hours=8.0,
        t_max="口服约 2.0 - 4.0 小时 (典型 3.0h)",
        t_max_hours=3.0,
        bioavailability="口服因剧烈首过及多途径氧化仅约 12.5% - 25% (个体差异明显；直肠/静注绕开首过大幅提高)",
        cyp_enzymes=[
            "CYP2D6 / CYP3A4 (主要负责肝脏 S-氧化、N-脱甲基及侧链羟化清除底物 / Substrate)",
        ],
        clearance="肝脏广泛氧化代谢并大部分随胆汁/粪便消除（存在明显肠肝循环延长终端暴露）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=10.0,
        washout_hours=50.0,
        washout_note="甲哌氯丙嗪是传统强效第一代吩噻嗪止吐与抗精神病药，其 D2 受体高强阻断力意味着常规使用（甚至仅单几片止吐）即可在青少年与青年女性中频发急性颈面肌张力障碍 (Acute Dystonia) 与严重烦躁静坐不能！应严防与其他 D2 阻断剂、延伸 QTc 药以及抗胆碱药物叠加。",
        mechanism_summary="经典哌嗪侧链第一代吩噻嗪强止吐药及抗精神病药：强效阻断延髓催吐化学感受区 D2 受体，兼明显 EPS 锥体外系反应。",
        active_metabolites=[],
        receptor_targets=[
            target("D2 多巴胺受体", "强效阻滞剂 (Ki ≈ 1 - 7 nM，强力止吐/镇静及突出急性肌张力障碍 EPS 风险)", "high", 0.96, "母体主导"),
            target("α1 肾上腺素能 / H1 受体", "中等拮抗（直立性低血压与轻中度镇静）", "moderate", 0.72, "母体主导"),
        ],
    ),
    "Baclofen": pk(
        half_life="约 2.0 - 6.0 小时 (人类典型均值约 4.5h；肾功能减退急剧延长蓄积导致深昏迷！)",
        half_life_hours=4.5,
        t_max="0.75 - 2.0 小时 (典型 1.5h)",
        t_max_hours=1.5,
        bioavailability="口服 70% - 85% (低剂量吸收近完全，超高剂量主动转运饱和使吸收比例回落)",
        cyp_enzymes=[
            "基本不经肝脏 CYP450 代谢 (仅 < 15% 肝脏脱氨基，几乎无 CYP 酶介导药物相互作用)",
        ],
        clearance="70% - 85% 直接以未变化原形经肾小球滤过排出体外（高度依赖肾功能！透析可清除）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=4.5,
        washout_hours=24.0,
        washout_note="巴氯芬母体半衰期约 4.5 小时，肾功能正常者约 24 小时洗脱；但有两项重磅临床安全警示：① **肾衰中毒黑洞**：即使轻中度慢性肾病/急性肾损，未减量的常规剂量即迅速蓄积造成数日不可逆脑病、肌张力消失、心动过缓乃至假死样深度昏迷；② **不可骤停**：长期高频（尤以鞘内泵或高口服用量 > 80 mg/日）绝不可骤停，否则急停 12 - 72 小时内爆发致命级幻觉谵妄、高热、横纹肌溶解与多脏器衰竭！",
        mechanism_summary="高选择性 GABAB 受体完全激动剂（抗痉挛及减酒渴求核心药物）：绕开肝脏主要由肾脏原形排出，母体直接起效。",
        active_metabolites=[],
        receptor_targets=[
            target("GABAB 受体", "高选择性原型完全激动剂 (突触前抑制 Ca2+ 内流、突触后开启 GIRK 钾通道超极化，大幅压低神经肌肉牵张反射与酒精戒断狂躁)", "high", 0.98, "母体主导"),
        ],
    ),
    "Carisoprodol": pk(
        half_life="母体仅约 1.5 - 2.5 小时 (典型 2.0h；CYP2C19 慢代谢者大幅滞留至 6h+)；唯一持久主导后半段及成瘾强度的核心活性代谢物甲丙氨酯 (Meprobamate) 半衰期长达 ~10.0 小时 (范围 6 - 17h)！",
        half_life_hours=2.0,
        t_max="母体 1.5 - 2.0 小时 (典型 1.7h)；衍生甲丙氨酯约 3.6 - 4.5 小时达峰",
        t_max_hours=1.7,
        bioavailability="口服吸收良好，经肝脏 CYP2C19 高比例转化为甲丙氨酯",
        cyp_enzymes=[
            "CYP2C19 (核心转化底物 / Substrate；负责脱异丙基生成主要活性长效产物甲丙氨酯 Meprobamate；慢代谢或合用氟伏沙明/奥美拉唑剧增母体昏迷抖动暴露)",
        ],
        clearance="肝 CYP2C19 转化为经典镇静剂甲丙氨酯后进一步羟化结合，与原形共同经肾排出",
        pk_role="PRODRUG",
        effective_half_life_hours=10.0,
        washout_hours=50.0,
        washout_note="卡立普多 (Soma) 是典型“短效快冲前药 + 长半衰期老式氨甲酸酯接力”组合：母体仅 2 小时半衰期，自身可产生快速猛烈的瞬时筋骨松弛甚至抽搐震颤（过量极易引发阵挛摇晃样 Soma Coma），而由 CYP2C19 转化出的 **Meprobamate（甲丙氨酯/眠尔通，t1/2 ≈ 10h）** 则在随后数十小时提供经典类似巴比妥的高成瘾性与持续呼吸抑制，彻底洗脱需 50 小时以上！与阿片类及苯二氮䓬合用（“Holy Trinity 致命三联征”）致死率极高！",
        mechanism_summary="双相接力肌肉松弛兼高滥用镇静体系：母体自身亦具直接 GABA-A 调节及抗焦急冲力，CYP2C19 转化的甲丙氨酯接盘持久 10h 级重度巴比妥样抑制与戒断依赖。",
        active_metabolites=[
            met(
                "Meprobamate (甲丙氨酯 / 眠尔通)",
                "母体经肝脏 CYP2C19 N-脱异丙基转化生成（Schedule IV 经典镇静抗焦虑老药）",
                "6.0 - 17.0 小时 (均值约 10.0h)",
                10.0,
                4.0,
                2.5,
                "正常代谢者甲丙氨酯血浆 AUC 暴露显著反超母体卡立普多 2 - 4 倍以上",
                "模拟巴比妥样机制直接激动/变构增强 GABA-A 受体氯通道，承担持久抗焦虑、强躯体成瘾性以及危险的呼吸抑制与重度癫痫戒断风险",
            )
        ],
        receptor_targets=[
            target("GABA-A 受体 (非苯二氮䓬巴比妥样调位点)", "母体与甲丙氨酯协同变构激活（高剂量可不依赖内源 GABA 直接撑开氯离子通道，危险性远超常规苯二氮䓬！）", "high", 0.96, "双相共同主导"),
        ],
    ),
    "Phenobarbital": pk(
        half_life="超长半衰期成人 53.0 - 140.0 小时 (典型均值 ~80.0h；婴幼儿可高达 100 - 200h)",
        half_life_hours=80.0,
        t_max="口服约 2.0 - 3.0 小时 (肌注达峰较慢约 2 - 4h)",
        t_max_hours=2.5,
        bioavailability="口服 90% - 95% (近完全吸收)",
        cyp_enzymes=[
            "CYP2C9 (主要对羟化代谢底物 / Substrate；同时极强诱导)",
            "CYP2C19 / CYP2E1 (次要清除底物 / Substrate)",
            "CYP3A4 (强效广泛诱导剂 / Strong Inducer！大幅加速华法林、避孕药、抗逆转录病毒药、地西泮、类固醇与多种精神科药物清除至失效！)",
            "CYP2C9 / CYP2C19 / CYP1A2 / CYP2B6 / UGT (广谱强力肝药酶及葡萄糖醛酸酶诱导剂 / Strong Inducer！停药后酶诱导仍持续 2 - 3 周以上！)",
        ],
        clearance="部分经肝 CYP2C9/2C19 羟基化及 N-葡糖苷酸化，约 25% 以原形经肾随尿排出（碱化尿液大幅促进离子化排泄）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=80.0,
        washout_hours=400.0,
        washout_note="苯巴比妥拥有中枢神经药物中最惊人的双重长效炸弹：① **超长半衰期（典型 80 小时，跨越 3 - 6 天）**：稳态需连用 2 - 3 周，完全清洗需长达 2.5 - 3 周（400h+）；联用酒精或阿片类将在长达数天内持续保持致死级呼吸抑制！② **全肝微粒体 CYP 核弹级广谱诱导剂**：剧烈诱导 CYP3A4 / CYP2C9 / CYP2C19 / CYP2B6 / CYP1A2 / UGT，停药后已升高的肝脏酶蛋白需要 2 - 4 周才会慢慢回落，导致合用的抗凝药、免疫抑制剂、激素避孕及镇静药反复发生巨大血药波动！",
        mechanism_summary="经典超长效巴比妥抗癫痫与镇静药：高浓度直接模拟 GABA 打开氯通道引发极窄治疗窗呼吸麻痹，兼核级广谱 CYP 酶强诱导。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (巴比妥结合位点)", "延长氯离子通道平均开放时间；高暴露时无需内源 GABA 直接强制开门引起深度麻醉昏迷及呼吸心跳停止", "high", 0.99, "母体主导"),
            target("AMPA / 红藻氨酸受体 & 电压钙通道", "抑制兴奋性谷氨酸传导，强化抗惊厥效果但同时加深认知抑制", "high", 0.85, "母体主导"),
        ],
    ),
    "Pentobarbital": pk(
        half_life="15.0 - 50.0 小时 (剂量依赖及个体差异极大，典型中长效约 25.0 小时)",
        half_life_hours=25.0,
        t_max="口服约 0.5 - 2.0 小时 (典型 1.0h，脂溶性极高吸收神速)",
        t_max_hours=1.0,
        bioavailability="口服吸收近 90% - 100%（迅速跨越血脑屏障产生急剧深镇静麻醉）",
        cyp_enzymes=[
            "CYP2C9 / CYP2C19 / CYP3A4 (主要肝微粒体氧化羟化清除底物兼明确诱导剂 / Inducer)",
        ],
        clearance="几乎全数经肝微粒体侧链羟化生成无显著活性醇产物，大部分随尿排泄",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=25.0,
        washout_hours=125.0,
        washout_note="戊巴比妥（短-中效作用，实测终末半衰期仍高达 15 - 50 小时！）具极高亲脂性与极其狭窄的安全窗（治疗量与呼吸停止致命过量仅一线之隔，远险于苯二氮䓬），合用极微量乙醇或阿片即足以诱发致命性脑干呼吸停止！完全洗脱常超过 5 天（125h）。",
        mechanism_summary="高脂溶性速穿中长半衰期巴比妥类药物：几分钟内冲刷脑干引发强效麻醉与极度呼吸抑制，母体主导且具肝酶诱导力。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (巴比妥位点)", "延长通道开放时间 + 高浓直接门控激活（极高致命性呼吸中枢抑制率）", "high", 0.99, "母体主导"),
            target("AMPA 谷氨酸受体", "显著抑制（进一步加剧麻醉呼吸停搏）", "moderate", 0.78, "母体主导"),
        ],
    ),
    "Secobarbital": pk(
        half_life="15.0 - 40.0 小时 (典型均值约 28.0h；早期快速脂溶分布退潮快，但终端体内清除极缓慢)",
        half_life_hours=28.0,
        t_max="口服 1.0 - 4.0 小时 (空腹约 2.0 - 2.5h 达峰)",
        t_max_hours=2.5,
        bioavailability="口服约 90% (高吸收率且起效极快)",
        cyp_enzymes=[
            "CYP2C9 / CYP2C19 / CYP3A4 (肝脏氧化代谢清除并具一定诱导活性 / Inducer)",
        ],
        clearance="几乎全部在肝脏侧链氧化失活后结合经肾排除（仅约 5% 原形尿排）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=28.0,
        washout_hours=140.0,
        washout_note="司可巴比妥与戊巴比妥相似，同样存在“主观 3 - 6 小时看似醒转但实测半衰期高达 28 小时”的险境！极易导致在仍有大量残余巴比妥滞留时追服或饮酒而发生突发窒息死亡，完全消除约 5 - 6 天（140h）。",
        mechanism_summary="高脂溶性高危险巴比妥镇静安眠剂，窄治疗窗及强呼吸抑制由母体原形直接支配。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (巴比妥位点)", "直接激动与强变构增强氯内流（无安全天花板效应，致死性极高）", "high", 0.99, "母体主导"),
        ],
    ),
    "Triazolam": pk(
        half_life="极短效 1.5 - 5.5 小时 (临床典型约 2.8h；无长效活性残留)",
        half_life_hours=2.8,
        t_max="1.0 - 2.0 小时 (典型 1.5h)",
        t_max_hours=1.5,
        bioavailability="口服 44% - 53% (经历显着肠壁与肝脏首过 CYP3A4 羟化；舌下途径绕开首过暴露大增)",
        cyp_enzymes=[
            "CYP3A4 (绝对单一主控代谢酶底物 / Substrate！强效 CYP3A4 抑制剂如酮康唑、伊曲康唑、利托那韦、克拉霉素与奈法唑酮可使三唑仑 AUC 暴涨 10 - 20 倍以上，FDA 列为绝对禁忌！)",
        ],
        clearance="几乎完全经肝及小肠 CYP3A4 羟基化（生成极短效且迅速葡糖醛酸灭活的 α-羟三唑仑），随后迅速经肾排空",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.8,
        washout_hours=15.0,
        washout_note="三唑仑是超短半衰期高危三唑苯二氮䓬的代表：常规代谢者 15 小时内完全清零（也正因起落极快而以严重反跳性失眠、凌晨顺行性遗忘与停药焦虑著称）；但正因为全靠肠/肝 CYP3A4 首过消除，与强效 CYP3A4 抑制剂（甚至大量西柚汁）同服会导致血药暴涨十余倍引发重度呼吸抑制与长期昏睡，FDA 严令禁用强 CYP3A4 抑制剂！",
        mechanism_summary="超短效高效价三唑并苯二氮䓬：极度依赖 CYP3A4 首过与系统清除，母体主导极速深睡诱导与严峻黑框遗忘/反跳风险。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (BZD 变构位点)", "超高效价 PAM (0.25 mg 约等效 5 - 10 mg 地西泮，强烈引起顺行性遗忘与睡眠行为紊乱)", "high", 0.98, "母体主导"),
        ],
    ),
    "Temazepam": pk(
        half_life="约 8.8 小时 (典型成人范围 3.5 - 18.4h，主要集中在 8 - 12h)",
        half_life_hours=8.8,
        t_max="口服硬胶囊/片约 1.0 - 1.5 小时 (不同制剂约 0.5 - 2h)",
        t_max_hours=1.0,
        bioavailability="口服 > 92% (几乎完全吸收，极低首过损失)",
        cyp_enzymes=[
            "不依赖肝微粒体 CYP450 氧化 (直接经肝 UGT 葡萄糖醛酸化生成无活性结合物经尿排除，是肝病/多药合用“LOT 原则”经典优选之一)",
        ],
        clearance="约 80% 直接经肝脏 UGT2B7 葡糖醛酸化为无中枢活性的替马西泮-O-葡糖苷酸迅速由肾排泄（几乎不再产生去甲地西泮/奥沙西泮蓄积）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=8.8,
        washout_hours=45.0,
        washout_note="替马西泮本身是地西泮下游天然活性中间体，直接单用时直接走 UGT 结合排出而彻底免于长半衰期 CYP 级联蓄积（与劳拉西泮、奥沙西泮同属对 CYP 抑制剂及轻中度肝病耐受最佳的 LOT 苯二氮䓬），约 45 小时清洗；仍绝对严禁合用酒精/阿片类以防呼吸暂停。",
        mechanism_summary="直接 UGT 葡糖醛酸化清除的中效催眠苯二氮䓬：母体原形主导整晚稳态睡眠维持而无超长效下游蓄积体。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (BZD 变构位点)", "中至强效 PAM (显著镇静助眠与抗焦虑效应)", "high", 0.93, "母体主导"),
        ],
    ),
    "Bromazepam": pk(
        half_life="17.0 - 30.0 小时 (典型约 20.0 - 22.0h；老年人群显著延长)",
        half_life_hours=22.0,
        t_max="0.5 - 4.0 小时 (典型 2.0h)",
        t_max_hours=2.0,
        bioavailability="口服 > 80% (平均约 84%)",
        cyp_enzymes=[
            "CYP1A2 (绝对主力代谢清除底物 / Substrate；氟伏沙明、环丙沙星或抽烟变化显着改变暴露)",
            "CYP3A4 (次要氧化代谢底物 / Substrate)",
        ],
        clearance="主要经肝 CYP1A2（主）及 CYP3A4 羟化代谢为 3-羟溴西泮，随后迅速 UGT 葡糖醛酸化经尿排出（几乎无长效母体级活性滞留产物）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=22.0,
        washout_hours=110.0,
        washout_note="溴西泮自身半衰期即长达 ~22 小时（无高比例超长效次级产物），连续每日服用可在数天内堆积并造成共济失调、认知变慢；完全消除需约 4 - 5 天（110h）；严防联用氟伏沙明等 CYP1A2 强抑制剂或阿片/乙醇。",
        mechanism_summary="典型依赖 CYP1A2 清除的中长效日间抗焦虑苯二氮䓬，母体主导持久平稳抗焦虑。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (BZD 变构位点)", "强效 PAM (中等剂量侧重抗焦虑，高剂量呈显着肌松镇静)", "high", 0.94, "母体主导"),
        ],
    ),
    "Flunitrazepam": pk(
        half_life="18.0 - 26.0 小时 (典型 20.0h；高脂溶再分布迅速但末端排出极其缓慢)",
        half_life_hours=20.0,
        t_max="1.0 - 2.0 小时 (典型 1.5h)",
        t_max_hours=1.5,
        bioavailability="口服 64% - 77% (吸收迅速且易入脑，肌注亦吸收优良)",
        cyp_enzymes=[
            "CYP3A4 (主要 N-去甲基与 3-羟化代谢底物 / Substrate；生成微量活性 7-氨基氟硝西泮等)",
            "CYP2C19 (共同参与氧化去甲基底物 / Substrate)",
        ],
        clearance="经肝脏硝基还原为 7-氨基氟硝西泮（法医强检测标志物）及经 CYP3A4/CYP2C19 去甲基生成去甲氟硝西泮（约 10% 弱蓄积）后由尿排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=22.0,
        washout_hours=110.0,
        washout_note="氟硝西泮（罗眠乐/十字架）兼具极快闪电穿透血脑屏障能力（引发极其彻底且危险的顺行性深度遗忘 Blackout）以及高达 20 小时级的长效末端滞留（约 5 天才完整清洗）！与极微量酒精合用即大概率彻底剥夺行为自控并触发致命呼吸抑制，在诸多国家属于最严管极高危致瘫遗忘苯二氮䓬。",
        mechanism_summary="超高效价、快穿慢清致顺行性深度遗忘硝基苯二氮䓬，依赖 CYP3A4/2C19 与硝基还原代谢，母体主导极端遗忘与长程镇静。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (α1 BZD 亚型高度偏好强结合)", "超高亲和力强效 PAM (极低毫克量即引发剧烈遗忘、肌肉瘫软及深度镇静昏睡)", "high", 0.99, "母体主导"),
        ],
    ),
    "Etizolam": pk(
        half_life="母体仅约 3.4 小时；主要活性代谢产物 α-羟基依替唑仑 (α-hydroxyetizolam) 半衰期延长至约 8.2 小时",
        half_life_hours=3.4,
        t_max="0.5 - 2.0 小时 (典型 1.2h，迅速吸收起效)",
        t_max_hours=1.2,
        bioavailability="口服高达 ~93% (高吸收率几乎无首过损失)",
        cyp_enzymes=[
            "CYP3A4 (主要羟基化代谢底物 / Substrate；催化生成核心活性产物 α-羟基依替唑仑)",
            "CYP2C19 (共同参与羟化代谢底物 / Substrate；亚洲常见 CYP2C19 慢代谢人群显著延长依替唑仑滞留时间！)",
        ],
        clearance="主要经 CYP3A4 与 CYP2C19 氧化生成活性产物 α-羟基依替唑仑及 8-羟基产物，随后由 UGT 结合经肾/胆汁排泄",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=8.2,
        washout_hours=42.0,
        washout_note="依替唑仑自身虽只有短小的 3.4 小时母体半衰期，但经肝 CYP3A4/2C19 生成等强甚至更持久的活性代谢物 α-羟基依替唑仑（半衰期 ~8.2h）实质撑起了 40+ 小时洗脱窗口！另外东亚人群 CYP2C19 慢代谢基因比例高（~15% - 20%），以及联合奥美拉唑/氟伏沙明或伊曲康唑时极易引发蓄积跌倒。戒断与“反向耐受敏化”争议需高度谨防。",
        mechanism_summary="噻吩并三唑并苯二氮䓬（非典型骨架但经典 BZD 位点高亲和力）：短效母体迅速平抑惊恐，中效活性产物 α-羟基依替唑仑平滑延展至次日。",
        active_metabolites=[
            met(
                "α-Hydroxyetizolam (α-羟基依替唑仑)",
                "母体经肝 CYP3A4 与 CYP2C19 侧链羟基化生成",
                "约 8.2 小时",
                8.2,
                2.5,
                0.7,
                "人体血浆暴露显著（通常占母体暴露量的 50% - 80%+，缓慢消除期主导血清总 BZD 活性）",
                "保留接近母体同等级的 GABA-A BZD 正向变构抗焦虑与镇静效价，主导给药 4 小时后的后半段镇静维持",
            )
        ],
        receptor_targets=[
            target("GABA-A 受体 (BZD 变构位点)", "高效价 PAM (1 mg 约等效 10 mg 地西泮抗焦虑效价，强力抗惊恐与中度催眠)", "high", 0.96, "双相共同主导"),
        ],
    ),
    "Cinolazepam": pk(
        half_life="约 9.0 小时 (范围 3.8 - 9h 实测，临床平均约 9.0 小时)",
        half_life_hours=9.0,
        t_max="1.0 - 2.0 小时 (典型 1.5h)",
        t_max_hours=1.5,
        bioavailability="口服 90% - 100% (吸收极其充分，蛋白结合率适中)",
        cyp_enzymes=[
            "极少依赖肝 CYP450 氧化 (直接经 3-位羟基由肝脏 UGT 迅速葡萄糖醛酸化结合排出，几乎无长效活性产物与 CYP 酶交互风险)",
        ],
        clearance="几乎完全以葡萄糖醛酸结合产物形式经肾快速排出（极少积累）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=9.0,
        washout_hours=45.0,
        washout_note="西诺西泮因带有现成的 3-位羟基而类似替马西泮/奥沙西泮直接结合排泄，约 45 小时清零；极少在老年与多药合用患者体中产生长半衰期积累，但大剂量仍存在苯二氮䓬典型的酒精/阿片协同呼吸抑制风险。",
        mechanism_summary="中效偏助眠型 3-羟基苯二氮䓬（几乎无肌松与次日遗忘干扰，母体直接结合失活清除）。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (BZD 变构位点)", "强效诱导并维持睡眠阶段结构稳态的 PAM", "high", 0.92, "母体主导"),
        ],
    ),
    "Phenazepam": pk(
        half_life="超长跨度 10.0 - 60.0 小时 (典型临床与法医人体中位均值 ~40.0 小时)",
        half_life_hours=40.0,
        t_max="口服约 2.0 - 4.0 小时 (典型 3.0h)",
        t_max_hours=3.0,
        bioavailability="口服吸收良好（肌注相对利用度 ~82%）",
        cyp_enzymes=[
            "CYP3A4 / CYP2C19 (代谢底物 / Substrate；肝脏羟化生成 3-羟基芬纳西泮等中间产物)",
        ],
        clearance="主要经肝微粒体羟化为具备一定活性的 3-羟基芬纳西泮及后续结合后缓慢经肾清除",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=40.0,
        washout_hours=200.0,
        washout_note="极高危超强效长半衰期前苏联处方苯二氮䓬（活跃量仅 0.5 - 2 mg！）：半衰期常高达 40 - 60 小时，全清除需逾 1 周（200h）！因常被毫无定量称量器具的使用者以粉末误当常规 mg 药吞服，历史上反复酿成连续昏迷/失忆“断片”数天乃至闯祸而不自知的灾难性长程过量事件！",
        mechanism_summary="超高效价（亚毫克级有效）、超长半衰期致深度遗忘失调苯二氮䓬，由缓慢消除的高亲和力母体统治全周期。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (BZD 位点)", "超强效 PAM (极易因亚毫克称量失误导致多日彻底顺行性失忆 Blackout)", "high", 0.99, "母体主导"),
        ],
    ),
    "Diclazepam": pk(
        half_life="母体终末消除半衰期约 42.0 小时 (Moosmann 2014 人类自愿者受控 1 mg 口服药代实验实测双室模型：分布相 1.9h，终末相 42.0h)！且级联生成 3 种更长效医药级苯二氮䓬活性产物！",
        half_life_hours=42.0,
        t_max="口服约 3.0 小时达峰 (Cmax 实测 ~3.4 ng/mL)",
        t_max_hours=3.0,
        bioavailability="口服吸收良好且彻底转化为地洛西泮、劳拉西泮及氯甲西泮三级活性群",
        cyp_enzymes=[
            "CYP3A4 / CYP2C19 (代谢底物 / Substrate；主要 N-去甲基为超长效地洛西泮 Delorazepam 及 3-羟基化为氯甲西泮、劳拉西泮)",
        ],
        clearance="极缓慢两阶段消除与逐级代谢（产生地洛西泮 t1/2 ≈ 60 - 140h、氯甲西泮 t1/2 ≈ 10 - 15h 与劳拉西泮 t1/2 ≈ 14h），尿液可检出持续 10 天以上！",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=80.0,
        washout_hours=400.0,
        washout_note="莫斯曼 (Moosmann et al., 2014) 人类受控试验证实：地西泮的 2'-氯代物“二氯西泮”母体半衰期已达 42 小时，更严重的是它会持续分解出半衰期高达 60 - 140 小时的强效处方苯二氮䓬 **Delorazepam（地洛西泮）** 以及经典药 **Lorazepam（劳拉西泮）** 与 **Lormetazepam（氯甲西泮）**！药理暴露和尿检阳性窗口往往长达 2 - 3 周 (400h+)，频繁补服会悄悄累加数周致重度昏睡跌倒与严酷滞后戒断！",
        mechanism_summary="多级医用苯二氮䓬产药体系（与地西泮级联同构）：母体 42h 长半衰期并接力生成超级长效地洛西泮、氯甲西泮与劳拉西泮。",
        active_metabolites=[
            met(
                "Delorazepam (地洛西泮)",
                "母体经肝 CYP2C19 / CYP3A4 N-去甲基化生成",
                "60.0 - 140.0 小时 (典型 ~80h)",
                80.0,
                24.0,
                1.5,
                "稳态暴露持续远超母体并主导第 2 日至第 2 周的持久剩余药效",
                "完整保留高亲和力抗焦虑与防惊厥 GABA-A 活性，极易导致跨周蓄积性精神运动抑制",
            ),
            met(
                "Lorazepam (劳拉西泮) / Lormetazepam (氯甲西泮)",
                "3-羟基化及进一步脱甲基级联汇合产物",
                "10.0 - 15.0 小时",
                14.0,
                12.0,
                0.4,
                "协同贡献总活性组分并完成 UGT 终末尿排出",
                "提供强效抗焦虑与镇静缓冲",
            ),
        ],
        receptor_targets=[
            target("GABA-A 受体 (BZD 变构位点)", "多代活性产物接力持续正向变构激动 (抗焦虑、抗惊厥兼长时间运动迟钝)", "high", 0.96, "双相共同主导"),
        ],
    ),
    "Flubromazepam": pk(
        half_life="极端漫长的约 106.0 小时（> 4.4 天！Moosmann 2013 人类受控 4 mg 单剂实验实测估算 t1/2 ≈ 106h）！",
        half_life_hours=106.0,
        t_max="口服吸收相对平稳延后约 3.0 - 5.0 小时达峰 (典型 4.0h，主观起效慢易误导盲目加倍补服！)",
        t_max_hours=4.0,
        bioavailability="口服高吸收（106 小时惊人消除滞留带来持续 2 - 3 周级暴露）",
        cyp_enzymes=[
            "肝微粒体缓慢氧化羟基化代谢并经 UGT 结合排出 (主要依赖母体缓慢消除，部分生成单羟基代谢产物)",
        ],
        clearance="异常缓慢的肝脏代谢与肾脏排泄，单次 4 mg 给药血清检出可持续半个月以上",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=106.0,
        washout_hours=530.0,
        washout_note="药典中半衰期最危险、最漫长的苯二氮䓬陷阱之一：氟溴西泮在人体实测消除半衰期高达惊人的 **106 小时（约 4.5 天）**！这意味着：① 连续每日服用只要 1 周，体内药物浓度就会呈雪崩式累加至首次剂量的 6 倍以上；② 单次服用彻底清洗竟需长达 22 天 (530 小时)；③ 因为起效较慢（峰值延迟 3 - 6 小时），极易诱使误判“没效”而疯狂追加剂量，直接导致长达 3 - 7 天无法唤醒的医院抢救昏迷！",
        mechanism_summary="超慢起效、极度超长滞留（4.4 天半衰期）高效价研究性苯二氮䓬，极易因蓄积与延迟峰值酿成数日失忆昏迷。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (BZD 位点)", "超长半衰期强效 PAM (极其危险的累积遗忘、共济失调及跨周呼吸抑制协调风险)", "high", 0.98, "母体主导"),
        ],
    ),
    "Pyrazolam": pk(
        half_life="约 17.0 小时 (Moosmann 2013 人类受控 1 mg 实验一室模型实测约 17.0 小时，血清可持续测出 > 50 小时)",
        half_life_hours=17.0,
        t_max="口服约 1.0 - 2.0 小时 (典型 1.5h，吸收迅速峰值高)",
        t_max_hours=1.5,
        bioavailability="口服吸收极其充分（1 mg 单剂即达 51 ng/mL 高血清峰且几无首过破坏）",
        cyp_enzymes=[
            "几乎完全不经历 I 相肝细胞色素 CYP 氧化代谢（人类受控研究证实主要以未变化原形及少量葡糖醛酸结合排出！）",
        ],
        clearance="极独特地绕开肝微粒体氧化：绝大部分直接以未变化原形或直接结合物经肾脏排泄（不受大多数 CYP 药物交互或肝硬化酶缺损干扰）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=17.0,
        washout_hours=85.0,
        washout_note="莫斯曼 (Moosmann 2013) 人类实验揭示吡唑仑两项罕见特征：① 它几乎完全不经过 CYP450 代谢，大部分直接原形肾排（因此极少受 CYP3A4/2C19 抑制剂扰动，但也因此依赖肾脏清除）；② 半衰期约 17 小时（约 3.5 天清洗），同时具备偏重 α2/α3 亚型相对清淡抗焦虑、镇静运动损伤相对较轻的临床评价——切忌因此误判安全性而高频复服形成耐受或联用乙醇/阿片！",
        mechanism_summary="偏重 α2/α3 选择性抗焦虑、不走肝 CYP 氧化而主要原形肾排的独特三唑并吡啶苯二氮䓬。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (相对偏好 α2 / α3 BZD 位点)", "选择性偏好 PAM (显著解除急性恐慌与焦虑而相对温和抑制 α1 催眠，但高量依然遗忘共济失调)", "high", 0.92, "母体主导"),
        ],
    ),
    "GHB": pk(
        half_life="极短半衰期 0.5 - 1.0 小时 (典型 0.7h；高负荷摄入呈非线性饱和动力学延长，酶饱和致消除变慢)",
        half_life_hours=0.7,
        t_max="空腹口服约 20 - 45 分钟极速达峰 (0.5h；高脂进食明显降 Cmax ~59% 且延迟 Tmax 至 ~1.5h)",
        t_max_hours=0.5,
        bioavailability="口服绝对生物利用度约 25% - 30% (受肝首过及饱和转运体影响呈非线性剂量敏感吸收——微小加量即可令血浆峰浓度暴升！)",
        cyp_enzymes=[
            "不经肝细胞色素 CYP450 清除 (经胞浆 GHB 脱氢酶转化为琥珀酸半醛并立即接入三羧酸循环彻底氧化为水与 CO2 呼出！)",
        ],
        clearance="体内快速氧化接入柠檬酸循环降解为 CO2 和 H2O，仅 1% - 5% 以原形经尿液极短暂排出（数小时后即彻底无痕！）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=0.7,
        washout_hours=4.0,
        washout_note="极端凶险的非线性陡峭剂量-效应曲线警示：GHB 母体半衰期虽然仅约 30 - 60 分钟（约 4 小时彻底燃尽），但因其吸收与 GHB 脱氢酶清除极易在高剂量瞬间饱和，**从“放松微醺”到“无意识深度昏迷/呕吐窒息致死 (G-Hole)”之间往往仅差区区 0.5 - 1.0 克！** 绝对严禁任何剂量的酒精、苯二氮䓬或氯胺酮合并！且长期高频依赖者停药会引发致命级重症谵妄癫痫戒断反应。",
        mechanism_summary="内源性短链神经调质（羟基丁酸）非线性吸收与快速柠檬酸循环代谢体：高浓度强激活抑制性 GABAB 受体带来极端窄安全窗昏睡风险。",
        active_metabolites=[],
        receptor_targets=[
            target("GABAB 受体", "低亲和力强全效激动剂 (高娱乐剂量彻底压倒 GHB 受体兴奋效应，触发深度麻醉昏睡、呕吐窒息与呼吸骤停)", "high", 0.96, "母体主导"),
            target("特异性 GHB 受体 (GHBR)", "极高亲和力内源性激活 (低剂量反而诱发微弱兴奋多巴胺释放与共情唤醒感)", "moderate", 0.70, "母体主导"),
        ],
    ),
    "GBL": pk(
        half_life="母体在血液中经高活性对氧磷酶/内酯酶 (PON1) 在 1 - 2 分钟内极速开环水解放出 GHB；转化生成的活性 GHB 半衰期同样极短约 0.5 - 1.0 小时 (0.7h)",
        half_life_hours=0.03,
        t_max="因更高脂溶性迅速穿胃黏膜并于 1 - 2 分钟变身 GHB，导致实质体内 GHB 血浆峰时比直服 GHB 更快更尖锐 (~20 - 40 分钟，0.45h)",
        t_max_hours=0.45,
        bioavailability="高于等摩尔直服 GHB！作为亲脂无极性内酯分子避开了强极性肠道转运限速步，全身吸收更快更剧烈（等量效力常高出 GHB 约 1.6 倍）",
        cyp_enzymes=[
            "不走 CYP450 通路 (血液高表达内酯酶 PON1/血清芳基酯酶数分钟内完全开环变为 GHB，继而进入三羧酸循环焚尽为 CO2)",
        ],
        clearance="闪电水解转化为 GHB 并经过琥珀酸半醛进入柠檬酸循环",
        pk_role="PRODRUG",
        effective_half_life_hours=0.7,
        washout_hours=4.0,
        washout_note="γ-丁内酯 (GBL) 是比 GHB 本体**更加剧烈危险**的超快前药！因自身属不带电荷的强亲脂小环分子，穿透消化道黏膜及血脑屏障速度远快于水溶 GHB 钠盐，进入血液后数十秒即被血清内酯酶 PON1 撕开内酯环全部生成 GHB——致使口服起效极快（10 - 15 分钟陡峰）、峰浓度更高、且极容易因为毫升与克密度换算误差（1 mL ≈ 1.12 g 纯 GBL ≈ 1.6 - 1.8 g GHB！）造成即刻重度昏迷窒息死亡！绝对严禁与酒精/镇静药混用。",
        mechanism_summary="高脂溶超速前药：凭借无电荷环状酯结构秒速跨越胃壁吸收后由血浆内酯酶瞬间转化为更猛烈尖峰的 GHB。",
        active_metabolites=[
            met(
                "GHB (γ-羟基丁酸)",
                "血清及组织内酯酶 (PON1) 1 - 2 分钟内极速水解打开 γ-内酯环释放",
                "0.5 - 1.0 小时 (高浓度酶饱和明显延迟)",
                0.7,
                0.45,
                1.0,
                "完全承担体内几乎 100% 系统药理与呼吸昏迷暴露",
                "高浓度下压倒性激动 GABAB 受体引起昏睡麻醉及极具危险的呕吐误吸呼吸骤停",
            )
        ],
        receptor_targets=[
            target("GABAB / GHB 受体", "由极快生成的 GHB 全面激活（母体自身基本无直接受体结合力，纯粹药代提速前药）", "high", 0.96, "代谢物主导"),
        ],
    ),
    "1,4-Butanediol": pk(
        half_life="母体人体实测半衰期约 39 分钟 (0.65h)；通过 ADH/ALDH 两步转化的活性主体 GHB 半衰期约 32 分钟 (0.55h；均具饱和延时风险！)",
        half_life_hours=0.65,
        t_max="母体 24 - 39 分钟达峰 (0.5h)；转化生成的 GHB 略延后约 30 - 60 分钟达峰",
        t_max_hours=0.5,
        bioavailability="口服吸收近 100%，极度依赖肝脏乙醇脱氢酶 (ADH) 及醛脱氢酶 (ALDH) 转化为 GHB！",
        cyp_enzymes=[
            "绝对依赖乙醇脱氢酶 (ADH) 与醛脱氢酶 (ALDH) 氧化生成活性 GHB——直接与乙醇争夺同一个限速代谢酶！(严重双刃剑交互！)",
        ],
        clearance="经肝 ADH 氧化为 γ-羟基丁醛后再由 ALDH 转化为 GHB，随后燃尽于三羧酸循环",
        pk_role="PRODRUG",
        effective_half_life_hours=0.7,
        washout_hours=5.0,
        washout_note="极具致命欺骗性的双酶竞争前药警报！1,4-丁二醇 (BDO) 必须依靠肝脏 ADH 才能变为起效的 GHB：① 单独饮用起效略滞后于 GBL/GHB（约 30 - 60 分钟缓慢爬升，极易诱使用户误判无效而盲目补喝导致叠加爆发极度过量昏迷！）；② 若同时喝下**哪怕少量乙醇（酒精）**，酒精会强行抢占所有 ADH 酶导致 1,4-BD 完全卡在血液里一时“似乎没感觉”；一旦数小时后酒精代谢完，堆积的 1,4-BD 瞬间倾泻化作海量 GHB，导致深夜毫无防备地突发深昏迷呼吸停止死亡！(东亚广泛携带 ALDH2 缺陷者更可能堆积剧毒中间醛导致极重度呕吐心悸崩溃！)",
        mechanism_summary="两步乙醇脱氢酶代谢依赖型 GHB 前药：起效慢易误判补服，与酒精存在致命的先延迟后爆发代谢酶竞争陷阱。",
        active_metabolites=[
            met(
                "GHB (γ-羟基丁酸)",
                "母体在肝脏经 ADH → ALDH 两步串联氧化最终生成",
                "0.5 - 1.0 小时 (人体 1,4-BD 试测实测衍生 GHB 约 0.55h)",
                0.55,
                0.75,
                0.9,
                "完全承担该物质所有的镇静欣快及呼吸停搏中枢效果",
                "与内源/直接吞服 GHB 完全相同，激活 GABAB 受体产生狭窄安全窗昏迷呼吸骤停风险",
            )
        ],
        receptor_targets=[
            target("GABAB / GHB 受体", "由转化生成的 GHB 主导激活 (母体本身活性极微，仅纯有机溶剂前体)", "high", 0.95, "代谢物主导"),
        ],
    ),
    "Phenibut": pk(
        half_life="约 5.3 小时 (人类单次口服 250 mg 药代文献经典实测均值 5.3h；大剂量高浓时显著减慢延至 8 - 12h+)",
        half_life_hours=5.3,
        t_max="口服起效非常缓慢！典型 Tmax 约 2.0 - 4.0 小时 (均值 ~3.0h，体感高峰往往延后至吞服 3 - 5 小时后才全面显现！)",
        t_max_hours=3.0,
        bioavailability="口服 ≥ 63% (主要以原型吸收入血并受高渗透延迟；盐酸盐极酸易刺激胃黏膜)",
        cyp_enzymes=[
            "极少经肝脏 CYP450 代谢 (人类给药后约 63% - 65% 以未变化原形直接经尿液排泄，几乎无常见 CYP 酶交互)",
        ],
        clearance="极少肝代谢，约 65% 直接以未变化原型经肾小球排出（其余少量经代谢灭活）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=5.3,
        washout_hours=30.0,
        washout_note="菲尼布特 (β-苯基-GABA) 虽表面血浆半衰期约 5.3 小时（完全消除约 30h），但临床有两项极痛心教训：① **爬坡极其漫长（Tmax ~3h，主观全感期长达 12 - 24h）**：新手吞下 1 - 2 小时以为无效便不断补服，3 小时后全量爆发引发连续 15 小时以上剧烈头晕喷射呕吐、昏迷休克！② **闪电形成灾难性耐受与戒断**：连续每日高用量仅仅 3 - 5 天即可建构强烈 GABAB + VGCC 躯体依赖，骤停会触发极度恐怖的崩溃惊恐、妄想幻觉甚至类似酒断的癫痫险情！每周安全上限至多 1 - 2 次。",
        mechanism_summary="苯环穿透型 GABAB 激动剂兼加巴喷丁类 α2δ 钙通道抑制剂：吸收爬坡慢而主观效应极持久，母体主导且直接经肾原形排泄。",
        active_metabolites=[],
        receptor_targets=[
            target("电压门控钙通道 α2δ 亚基 (VGCC)", "强效结合抑制（主要负责菲尼布特独特的镇静抗社交焦虑与微弱共情畅谈感，与普瑞巴林相似）", "high", 0.92, "母体主导"),
            target("GABAB 受体", "中等完全激动剂 (随剂量升高呈强力肌松、昏睡及严酷戒断依赖驱动力；显著增强酒精与阿片危险)", "high", 0.88, "母体主导"),
        ],
    ),
    "Methaqualone": pk(
        half_life="20.0 - 40.0 小时 (范围 10 - 40h，典型均值约 20.0h；慢长脂溶消除极易多次累积！)",
        half_life_hours=20.0,
        t_max="口服 2.0 - 3.0 小时 (典型 2.5h；片剂迅速分散吸收引发快感峰值)",
        t_max_hours=2.5,
        bioavailability="口服近 99% (吸收极其完全，高度分布积聚至大脑及脂肪组织)",
        cyp_enzymes=[
            "CYP2C19 / CYP3A4 / CYP1A2 (代谢底物 / Substrate；主要肝脏多种微粒体单羟化代谢并经葡萄糖醛酸化排出)",
        ],
        clearance="几乎全数经肝微粒体羟化酶系转化生成无显著活性单羟基产物后结合尿排（仅 < 2% 原形排出）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=20.0,
        washout_hours=100.0,
        washout_note="安眠酮（甲喹酮 / Quaalude）是经典非巴比妥喹唑啉酮高危强效镇静剂：母体半衰期长达约 20 小时（100h 完全洗脱），极易在连续服用后发生体内剧烈累积、爆发强直性肌阵挛抽搐与呼吸心跳猝停；与极少量酒精叠加即可瞬间突破致死线（历史上著名的“Disco Biscuits”过量元凶）。",
        mechanism_summary="强效跨膜 GABA-A 正向变构镇静麻醉剂（独立作用于非苯二氮䓬 β 亚基调位点），母体长效主导严重运动失调与致命呼吸压制。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (跨膜 β 亚基特异性变构位点)", "强效正向变构激动调节 (区别于苯二氮䓬，无安全天花板，极易高剂量下呼吸衰竭)", "high", 0.96, "母体主导"),
        ],
    ),

    # ==================== Group 5: Nootropics & Classic Clinical Rx ====================
    "Tianeptine": pk(
        half_life="母体仅约 2.5 小时 (典型短效)；主力强效 μ-阿片/抗抑郁活性长效代谢物 MC5 半衰期长达 ~7.2 小时 (范围 6 - 9h)",
        half_life_hours=2.5,
        t_max="口服约 0.94 小时 (约 55 分钟极快达峰)",
        t_max_hours=0.94,
        bioavailability="口服高达 99% (无肝脏首过损耗，几乎全数吸收)",
        cyp_enzymes=[
            "几乎不经肝微粒体 CYP450 代谢 (主要经线粒体 β-氧化将戊酸侧链缩短两个碳，生成主力活性代谢物 MC5 丙酸衍生物)",
        ],
        clearance="几乎全数经线粒体 β-氧化降解为活性 MC5（丙酸产物）与非活性 MC3 后随尿排出；肾功能减退显著蓄积 MC5",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=7.2,
        washout_hours=36.0,
        washout_note="噻奈普汀曾因机制描述被称为“SSRE”其实完全是个误导性旧观念——现代权威药理学已明确证实噻奈普汀实质是一种 **不经 CYP、高利用度的典型 μ-阿片受体 (MOR) 完全激动剂**！虽然母体半衰期仅 2.5 小时，但其 β-氧化产生的活跃产物 **MC5 (t1/2 ≈ 7.2h)** 在高剂量持续滥用下强力维持 MOR 激动，一旦过量或反复高用量（>100 mg/日乃至克级“气体站海洛因 Zaza”）即爆发剧烈的阿片依赖、痛彻骨髓的阿片型戒断与致死呼吸抑制（需用纳洛酮抢救，严禁混服任何其它阿片、苯二氮䓬或乙醇）！",
        mechanism_summary="高生物利用度非 CYP β-氧化型非典型 μ-阿片完全激动抗抑郁药：母体 2.5h 闪冲结合 7.2h 活性代谢物 MC5 协同撑起谷氨酸 AMPA 调节及强力 MOR 激活风险。",
        active_metabolites=[
            met(
                "MC5 (C5-戊酸侧链 β-氧化丙酸代谢产物)",
                "母体不走 CYP450，而经肝线粒体 β-氧化缩短侧链 2 个碳原子生成",
                "6.0 - 9.0 小时 (均值约 7.2h；肾功能不全明显延长)",
                7.2,
                2.5,
                0.65,
                "正常每日多次给药稳态血浆浓度接近或持续平齐于母体暴露",
                "体外及体内均完整保留与母体相当的 μ-阿片受体激动与抗抑郁效价，大幅延伸持续药理窗口至 7 小时级",
            )
        ],
        receptor_targets=[
            target("μ-阿片受体 (MOR)", "高内源性活性完全激动剂 (母体 Ki ≈ 383 nM，治疗量温和舒缓，高量暴用等同强阿片成瘾、致死呼吸暂停及严酷戒断！)", "high", 0.94, "双相共同主导"),
            target("AMPA / NMDA 谷氨酸突触重塑通路", "下游间接调节（促进海马 BDNF 与防止应激树突萎缩，治疗剂量主机制）", "moderate", 0.72, "双相共同主导"),
        ],
    ),
    "Tizanidine": pk(
        half_life="2.0 - 2.5 小时 (典型 2.3h；合用 CYP1A2 抑制剂时剧烈拖延至十数小时)",
        half_life_hours=2.3,
        t_max="约 1.0 小时 (空腹片剂与胶囊生物利用与峰时随进食状态极度波动！)",
        t_max_hours=1.0,
        bioavailability="口服平均仅约 40% (剧烈肝脏首过 CYP1A2 消除！高脂餐改变胶囊/片剂释放导致暴露骤变，严禁任意混换剂型及餐前餐后吃法)",
        cyp_enzymes=[
            "CYP1A2 (绝对唯一主控代谢底物 / Substrate！合用强效 CYP1A2 抑制剂氟伏沙明可使替扎尼定 AUC 暴增 33 倍、环丙沙星暴增 10 倍——FDA 黑框级绝对禁忌！)",
        ],
        clearance="95% 以上经肝 CYP1A2 高度首过代谢失活并随后结合尿排（原形 < 2%）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.3,
        washout_hours=14.0,
        washout_note="核心临床极高危 CYP1A2 黑框禁区警示：替扎尼定本身仅 2.3 小时半衰期，常规 14 小时内清除；但因口服首过 60% 全靠 CYP1A2 清除，若与常用抗抑郁药**氟伏沙明 (Fluvoxamine)** 或常用抗生素**环丙沙星 (Ciprofloxacin)** 联用，其血药 AUC 将恐怖暴涨 **10 - 33 倍**，立刻引起极重度低血压休克、心动过缓、深昏迷与跌倒损伤！此外绝对不可与氯胺酮、可乐定及其他中枢降压药或过量乙醇合用。",
        mechanism_summary="高选择性中枢 α2 肾上腺素能激动剂型肌松药：母体短暂作用压低脊髓多突触痉挛反射，极度受控于肝 CYP1A2 首过清除率。",
        active_metabolites=[],
        receptor_targets=[
            target("α2 肾上腺素能受体", "高选择性激动剂 (抑制脊髓中间神经元释放兴奋性氨基酸，降肌肉痉挛兼剧烈强致嗜睡与体位性低血压休克风险)", "high", 0.96, "母体主导"),
            target("咪唑啉 I1/I2 受体", "中度结合（进一步加强中枢降压低心率风险）", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Galantamine": pk(
        half_life="约 7.0 小时 (常释与缓释清除半衰期均约 7h；重度肝肾损害明显延缓)",
        half_life_hours=7.0,
        t_max="常释约 1.0 小时；缓释胶囊约 4.5 - 5.0 小时",
        t_max_hours=1.0,
        bioavailability="口服约 90% (吸收极其良好且几乎不受进食削减总 AUC)",
        cyp_enzymes=[
            "CYP2D6 (主要 O-去甲基化代谢底物 / Substrate；慢代谢及抑制剂升高 AUC 约 25% - 50%)",
            "CYP3A4 (主要 N-氧化代谢底物 / Substrate；合用强 CYP3A4 抑制剂同样升高暴露 ~30% - 40%)",
        ],
        clearance="约 50% 经 CYP2D6 与 CYP3A4 肝氧化，约 20% - 30% 以未变化原形及葡糖醛酸结合物经肾脏排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=7.0,
        washout_hours=36.0,
        washout_note="加兰他敏半衰期约 7 小时（约 36 小时洗脱），受 CYP2D6 与 CYP3A4 双通道清除保护而不易单通路崩盘（但合用如氟西汀+酮康唑双阻滞仍升高明显）。注意其除了可逆乙酰胆碱酯酶抑制外还正变构增敏烟碱 α4β2/α7 受体，故与尼古丁或强胆碱药物协同易引起剧烈恶心呕吐、心动过缓晕厥或高度清醒梦（Oneirogen 常见用途）。",
        mechanism_summary="双机制认知增强与梦境诱导剂（AChE 竞争性抑制剂 + 烟碱型 nAChR 正向变构增敏剂 PAM），母体原形主导胆碱能增幅。",
        active_metabolites=[],
        receptor_targets=[
            target("乙酰胆碱酯酶 (AChE)", "竞争性可逆抑制剂 (增加中枢突触乙酰胆碱浓度，改善记忆同时诱发生动清晰梦与外周胃肠绞痛心率慢)", "high", 0.94, "母体主导"),
            target("α7 / α4β2 烟碱型乙酰胆碱受体 (nAChR)", "正向变构调节剂 PAM (在低内源 ACh 下放大烟碱通道灵敏度)", "high", 0.85, "母体主导"),
        ],
    ),
    "Piracetam": pk(
        half_life="4.0 - 6.0 小时 (典型成人均值 5.0h；脑脊液半衰期略长约 7 - 8h)",
        half_life_hours=5.0,
        t_max="口服 0.5 - 1.0 小时 (空腹片剂/溶液极快达峰 0.75h；饱餐略后移至 ~1.5h)",
        t_max_hours=0.75,
        bioavailability="口服近 100% (吸收极其彻底、零首过损耗)",
        cyp_enzymes=[
            "绝对不经肝脏任何 CYP450 代谢 (0% 肝代谢，几乎无已知任何 CYP 药物相互作用风险)",
        ],
        clearance="95% - 100% 完全以未变化原形经肾小球滤过直接随尿排空（严重肾衰必须按肌酐清除率减量或禁用）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=5.0,
        washout_hours=28.0,
        washout_note="吡拉西坦（脑复康）作为最经典初代拉西坦，生物利用度高达 100%、完全不走肝脏、近 100% 原形肾排且半衰期仅 5 小时（约 28 小时彻底洗脱）。日常交互极为安全温和，唯一须注意：① 抗血小板聚集协同（高剂量合用华法林/氯吡格雷略微轻微增大出血或抗凝 INR）；② 严禁重度终末期肾病未调量使用。",
        mechanism_summary="初代 AMPA 正向变构兼细胞膜流动性调节型拉西坦促智药：不结合经典单胺/GABA 受体而强化神经元突触可塑性与脑微循环。",
        active_metabolites=[],
        receptor_targets=[
            target("AMPA 谷氨酸受体", "轻至中度正向变构调节剂 (增强受体钙内流与突触信号传导效能，非直接激动)", "moderate", 0.75, "母体主导"),
            target("神经细胞膜磷脂双分子层 / 红细胞变形度", "物理化学膜流动性改善兼抗血小板黏附效应", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Aniracetam": pk(
        half_life="母体在血液中极端快速水解：口服后母体半衰期仅约 0.5 - 2.5 小时 (典型 1.8h)；广泛转化生成 3 种主要循环代谢产物 (N-茴香酰-GABA t1/2 ~2h，茴香酸/2-吡咯烷酮)",
        half_life_hours=1.8,
        t_max="0.5 - 2.0 小时 (典型 1.0h；高脂溶性与随脂餐同服轻微提升峰值吸收)",
        t_max_hours=1.0,
        bioavailability="口服绝对未变化原形利用度极低仅约 0.2% - 2.5% (主要经肝与肠壁剧烈首过水解为活性 N-茴香酰-GABA 等代谢产物入脑起效！)",
        cyp_enzymes=[
            "非 CYP450 主控水解 (主要经肝/血浆酰胺酶首过裂解成 N-茴香酰-GABA、2-吡咯烷酮及对茴香酸等)",
        ],
        clearance="极快首过转化为 N-茴香酰-GABA 及对茴香酸结合物后约 84% 经尿排出，母体本身 2 - 4 小时即消失",
        pk_role="ACTIVE_METABOLITE",
        effective_half_life_hours=2.5,
        washout_hours=15.0,
        washout_note="阿尼西坦与吡拉西坦完全不同：虽然亲脂性强、常带脂溶性抗焦虑兼促专注感，但口服绝对原形利用度竟然不足 2.5%！绝大多数以首过生成的活性产物 **N-茴香酰-GABA** 及对茴香酸协同主导 2 - 4 小时温和认知与抗焦虑感（约 15 小时全洗脱）。",
        mechanism_summary="高脂溶快速转化型拉西坦：母体兼强效正向调节 AMPA 与代谢型谷氨酸受体，配合首过产物 N-茴香酰-GABA 协同介导温和专注与抗焦虑。",
        active_metabolites=[
            met(
                "N-Anisoyl-GABA (N-茴香酰-GABA)",
                "母体在肠壁及肝脏经开环水解反应首过广泛生成（人体血浆最高浓度产物之一）",
                "约 1.5 - 3.0 小时 (典型 2.2h)",
                2.2,
                1.5,
                10.0,
                "系统暴露远高于游离母体阿尼西坦原形数十倍",
                "参与促进中枢神经递质稳态释放、AMPA 增敏及部分情绪平缓稳定效果",
            )
        ],
        receptor_targets=[
            target("AMPA 谷氨酸受体", "显著正向变构调节减慢受体脱敏速率 (较吡拉西坦亲和效力明显更高)", "high", 0.85, "双相共同主导"),
            target("mGluR2/3 / 5-HT2A / D2 间接调控", "经研究提示参与独有的温和抗社交紧张与视觉色彩细腻感增强", "moderate", 0.60, "代谢物主导"),
        ],
    ),
    "Oxiracetam": pk(
        half_life="3.0 - 6.0 小时 (典型健康人类均值 4.5h；部分研究老年人群或多次达 6 - 8h)",
        half_life_hours=4.5,
        t_max="1.0 - 3.0 小时 (典型 1.8h)",
        t_max_hours=1.8,
        bioavailability="口服 56% - 82% (典型 ~70%，水溶极佳直接吸收)",
        cyp_enzymes=[
            "不经肝脏 CYP450 代谢 (0% 细胞色素酶依赖，无酶抑/诱交互)",
        ],
        clearance="几乎完全以未变化原形（> 80% - 90%）经肾脏直接排泄（零肝毒性与肝药酶冲突）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=4.5,
        washout_hours=24.0,
        washout_note="奥拉西坦临床广泛用作医院神经科促醒与记忆修复药，几乎完全原形肾排（~24h 洗脱）、无 CYP 冲突且安全窗极高；仅重度肾衰患者须减半剂量，晚间过晚服用可能引起轻微清醒难眠。",
        mechanism_summary="羟基修饰水溶性升级版拉西坦：母体直接增强皮层海马乙酰胆碱利用及 AMPA/NMDA 突触可塑性（无肝酶负载）。",
        active_metabolites=[],
        receptor_targets=[
            target("AMPA / NMDA 谷氨酸传导系统", "非直接激动之正向突触效能强化 (促进长时程增强 LTP)", "moderate", 0.78, "母体主导"),
            target("中枢乙酰胆碱合成与高亲和胆碱摄取", "温和提高海马乙酰胆碱周转率 (过量偶伴轻度缺胆碱头痛)", "moderate", 0.68, "母体主导"),
        ],
    ),
    "Phenylpiracetam": pk(
        half_life="3.0 - 5.0 小时 (俄罗斯原研临床及欧洲药代文献经典实测均值 4.0h)",
        half_life_hours=4.0,
        t_max="约 1.0 小时 (极快穿过血脑屏障起效迅速)",
        t_max_hours=1.0,
        bioavailability="口服高达 ~100% (高脂溶苯环取代使血脑穿透与利用度显著超越吡拉西坦数十倍)",
        cyp_enzymes=[
            "不经显著肝 CYP450 代谢 (约 40% - 60% 以未变化原形随尿排泄，其余经胆汁缓慢消除，无显着已知 CYP 交互)",
        ],
        clearance="主要原形经肾排泄（约 40%）及部分非微粒体排泄，无蓄积型长效代谢物",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=4.0,
        washout_hours=24.0,
        washout_note="苯吡拉西坦（Phenylpiracetam / Carphedon）因在吡拉西坦上附加了苯环，赋予了显着不同于普通拉西坦的**直接多巴胺转运体 DAT 弱至中等抑制**与抗寒冷耐力增强（已列为世界反兴奋剂机构 WADA 禁药）！虽半衰期仅 4 小时（24h 清除），但急性多巴胺能耐受形成极其迅速，连续服用 2 - 3 天后提神锐感即显著钝化，不宜与强力单胺兴奋剂或 MAOI 高量叠加。",
        mechanism_summary="高脂溶兼多巴胺能复合型强效拉西坦：除传统 AMPA/nAChR 增效外直接轻微阻断 DAT 带来数小时清醒耐力激励，母体直接支配且快建耐受。",
        active_metabolites=[],
        receptor_targets=[
            target("DAT (多巴胺转运体)", "中等竞争性再摄取抑制 (赋予其独特的轻度多巴胺能驱动与极快多巴胺急性耐受特性)", "high", 0.85, "母体主导"),
            target("AMPA 受体 / nAChR 密度上调", "显著提升突触可塑性与认知抗疲劳阈值", "moderate", 0.75, "母体主导"),
        ],
    ),
    "Pramiracetam": pk(
        half_life="4.5 - 6.5 小时 (人类受控 400 - 1600 mg 药代研究均值约 5.5h，呈现严格线性动力学)",
        half_life_hours=5.5,
        t_max="口服 2.0 - 3.0 小时 (典型 2.5h)",
        t_max_hours=2.5,
        bioavailability="口服吸收良好近 ~100%（血浆蛋白基本不结合）",
        cyp_enzymes=[
            "不经肝脏微粒体 CYP 代谢 (主要以未变化原形经肾脏完全排出，线性无首过滞留)",
        ],
        clearance="几乎完全以未变化原形直接经肾随尿排出，在 400 - 1600 mg 临床试验中呈完美线性暴露比例",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=5.5,
        washout_hours=30.0,
        washout_note="普拉西坦半衰期约 5.5 小时且零 CYP 交互（约 30 小时完全清洗）；其突出机制在于剧烈提高大脑海马区高亲和胆碱摄取率 (HACU)，因此极易在连续使用中耗尽突触前游离胆碱而诱发典型额部紧张性头痛（适度补充胆碱前体通常即可缓解）。",
        mechanism_summary="高亲脂二异丙氨乙基修饰强效促智拉西坦：大幅加速海马高亲和力胆碱摄取与乙酰胆碱合成，母体直接原形肾排。",
        active_metabolites=[],
        receptor_targets=[
            target("海马高亲和力胆碱转运体 (HACU / CHT1)", "显著间接增强胆碱能转化率（强烈促进专注与工作记忆整合但消耗胞外胆碱）", "high", 0.88, "母体主导"),
            target("一氧化氮合酶 (NOS)", "大脑皮层 NOS 温和激活调节血流与可塑性", "moderate", 0.60, "母体主导"),
        ],
    ),
    "Efavirenz": pk(
        half_life="单剂后超长 52.0 - 76.0 小时；连续每日给药由于自我强诱导 CYP2B6 / CYP3A4，稳态半衰期回落缩短至 40.0 - 55.0 小时 (典型 48.0h)",
        half_life_hours=48.0,
        t_max="口服 3.0 - 5.0 小时 (典型 4.0h；高脂餐使暴露与神经毒副作用大幅飙升，空腹睡前服用！)",
        t_max_hours=4.0,
        bioavailability="口服吸收 40% - 45% (高脂餐可使 Cmax 暴增达 79%、AUC 升 28% 并引发剧烈头晕噩梦，故明确要求空腹给药！)",
        cyp_enzymes=[
            "CYP2B6 (绝对核心代谢清除底物 / Substrate；CYP2B6*6 慢代谢基因型暴露暴增至重度精神神经毒性)",
            "CYP2B6 (强效自诱导剂 / Strong Inducer！自身稳态清除亦加速)",
            "CYP3A4 (重要辅助代谢底物 / Substrate；同时作为明确的强效 CYP3A4 诱导剂 / Strong Inducer，暴跌美沙酮、丁丙诺啡、苯二氮䓬与避孕药浓度！)",
            "CYP2C9 / CYP2C19 (临床中效抑制/混合调节，升高华法林等部分底物暴露 / Inhibitor)",
        ],
        clearance="主要经 CYP2B6 羟化生成 8-羟依法韦仑 (8-OH-EFV，具有一定神经毒性潜力) 后经 UGT2B7 葡糖醛酸化随粪便/尿排泄",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=48.0,
        washout_hours=240.0,
        washout_note="依法韦仑兼具极端漫长的半衰期 (~48h 稳态，完全清零与酶诱导回落需 2 - 3 周！) 与两大致命药物警示：① **CYP2B6/CYP3A4 强效诱导引擎**：猛烈拉低美沙酮（导致阿片患者突然爆发痛性戒断）、多数抗精神病药、奥昔/羟考酮、多种苯二氮䓬、抗抑郁药及口服避孕药血浓度！② **精神神经黑框效应**：约半数用药者经历极其逼真的生动噩梦、解离感、眩晕乃至严重抑郁幻觉（部分源自母体直接激动 5-HT2A/5-HT2C 并结合 GABAA，高脂饮食同服瞬间剧增峰值中毒！）。",
        mechanism_summary="超长半衰期非核苷类逆转录酶抑制剂兼强效 CYP3A4/CYP2B6 诱导引擎：高脂餐暴增吸收致剧烈生动噩梦与 5-HT2A 样离奇神经症状。",
        active_metabolites=[],
        receptor_targets=[
            target("HIV-1 逆转录酶 (NNRTI 变构口袋)", "超高亲和力非竞争性抗病毒抑制 (主治适应症核心)", "high", 0.99, "母体主导"),
            target("5-HT2A / 5-HT2C / GABA-A 神经脱靶点", "部分激动 5-HT2A 及复杂 GABA 调节（直接诱发着名极度清醒噩梦、轻度迷幻视角、解离眩晕及偶发精神危机）", "high", 0.85, "母体主导"),
        ],
    ),
    "Apomorphine": pk(
        half_life="约 0.55 - 0.67 小时 (皮下注射 30 - 40 分钟，典型 0.6h；高脂溶快消退)",
        half_life_hours=0.6,
        t_max="皮下注射仅 10 - 20 分钟 (0.25h) 极速达峰；舌下含片约 30 分钟",
        t_max_hours=0.25,
        bioavailability="口服受剧烈肝脏首过硫酸/葡糖醛酸化利用度 < 4% (临床几乎绝不口服！)；皮下注射近 100%；舌下绕首过约 17% - 20%",
        cyp_enzymes=[
            "几乎不经 CYP450 清除 (主要通过 COMT 甲基化、硫酸转移酶 SULT 及 UGT 结合极速失活)",
        ],
        clearance="肝脏迅速硫酸化、葡糖醛酸化与 COMT 邻位甲基化，90% 以上数小时内随尿及粪便排除",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=0.6,
        washout_hours=4.0,
        washout_note="阿扑吗啡名字虽带吗啡但**完全不结合任何阿片受体**！它是极强效非麦角类多巴胺 D1/D2 完全激动剂，专门作为帕金森骤发性“关期冻结”的皮下急救针剂（10 分钟起效，半衰期仅 35 分钟、4 小时清空）。严重临床黑框警示：绝禁与 **5-HT3 拮抗型止吐药（如昂丹司琼 Ondansetron）** 同用——否则必定爆发致命性重度血管麻痹性低血压休克与意识丧失！（常规须配伍外周多潘立酮防剧烈催吐反应）",
        mechanism_summary="极速短效非麦角类多巴胺全受体激动急救针（彻底无阿片活性）：皮下 15 分钟闪电逆转帕金森冻住期，极快经 COMT/结合清除。",
        active_metabolites=[],
        receptor_targets=[
            target("D2 / D3 / D4 多巴胺受体", "极强效完全激动剂 (Ki ≈ 24 - 35 nM，迅速重启黑质纹状体运动通路并刺激延髓催吐感受区诱发猛烈吐泻)", "high", 0.98, "母体主导"),
            target("D1 / D5 多巴胺受体", "强效完全激动剂 (明显区别于普拉克索的广谱 D1 协同)", "high", 0.88, "母体主导"),
            target("α2 肾上腺素能 / 5-HT2A", "中等结合激活 (偶致低血压晕厥与打哈欠反应)", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Etomidate": pk(
        half_life="血浆快推仅约 3 - 5 分钟分布苏醒，但组织深室终端消除半衰期约 2.0 - 5.0 小时 (典型 3.5h)",
        half_life_hours=3.5,
        t_max="静注 < 1 分钟 (0.02h) 瞬间入脑麻醉",
        t_max_hours=0.02,
        bioavailability="静脉注射 100%（临床仅限静脉全麻诱导制剂）",
        cyp_enzymes=[
            "血浆丁酰胆碱酯酶及肝脏微粒体酯酶快速水解乙酯键成无活性依托咪酯酸",
            "CYP11B1 / CYP17A1 (非肝药酶而是肾上腺皮质 11β-羟化酶！依托咪酯以亚微摩尔超强效抑制皮质醇合成导致严重肾上腺皮质功能衰竭！)",
        ],
        clearance="几乎全数经血浆与肝酯酶迅速水解失活后经尿排泄（约 75% - 85%）",
        pk_role="IRREVERSIBLE_PD",
        effective_half_life_hours=3.5,
        washout_hours=24.0,
        washout_note="重大生命危机黑框提示（电子烟非法添加“上头烟油”剧毒真相！）：依托咪酯虽仅作为 ICU/手术室单次全身麻醉诱导药（血浆 3 - 5 分钟入睡苏醒，总清除 ~3.5h），一旦像近年来非法电子烟那样连续反复抽吸，它会极其残酷地彻底阻断肾上腺皮质 **11β-羟化酶 (CYP11B1)**长达 24 - 72 小时以上——直接导致人体皮质醇清零、醛固酮失衡、严重低钠低钾血容量休克、面部手足怪异肌阵挛抽搐与猝死！(且极易与任何酒精/苯二氮䓬联用窒息)",
        mechanism_summary="高选择性 β2/β3 GABA-A 麻醉诱导剂：虽具备 5 分钟快速清醒诱导表象，但持续抑制肾上腺 11β-羟化酶停摆皮质醇合成长达数天。",
        active_metabolites=[],
        receptor_targets=[
            target("GABA-A 受体 (β2/β3 亚基特异性位点)", "强效正向变构与直接激动全麻效果 (几乎不压制心肌交感故临床曾保血压，但高浓度致呼吸骤停与顽固肌阵挛)", "high", 0.96, "母体主导"),
            target("肾上腺皮质 11β-羟化酶 (CYP11B1)", "极强效可逆但持久酶阻断（IC50 纳摩尔级，导致皮质醇崩溃与致命急性肾上腺危象！）", "high", 0.98, "药效动力学不可逆主导"),
        ],
    ),
    "Gaboxadol": pk(
        half_life="约 1.5 - 2.0 小时 (人类临床试验典型均值 1.75h)",
        half_life_hours=1.75,
        t_max="口服 0.5 - 1.0 小时 (典型 0.75h)",
        t_max_hours=0.75,
        bioavailability="口服高达 92% (几乎完全吸收，不受常见肝脏首过清除显著损失)",
        cyp_enzymes=[
            "不经肝脏 CYP450 氧化途径 (主要经尿苷二磷酸葡糖醛酸转移酶 UGT1A9 直接葡萄糖醛酸化结合，兼大量原形肾排)",
        ],
        clearance="大约 50% - 60% 直接以未变化原形经肾排出，其余经 UGT1A9 葡糖醛酸化结合物迅速经尿排空",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=1.75,
        washout_hours=10.0,
        washout_note="加波沙朵 (THIP) 半衰期极短且彻底洗脱仅约 10 小时，极少产生次日残余困倦；区别于苯二氮䓬或 Z 类药只绑 BZD 位点，它是**正构位点直接激动含 δ 亚基的突触外 GABA-A 受体**（极强加深慢波深睡眠同时在高剂量极易产生幻觉、空间眩晕与共济失调，这亦是该药当年 III 期失眠开发最终叫停之因）。切勿混用其他强镇静物。",
        mechanism_summary="高选择性突触外 α4βδ-GABA-A 正构直接完全激动剂（非 BZD 变构机制），母体快消快净诱导强深慢波睡眠。",
        active_metabolites=[],
        receptor_targets=[
            target("突触外含 δ 亚基 GABA-A 受体 (α4βδ)", "正构位点高选择性超完全激动剂 (直接放大强直性抑制流，显著提高慢波睡眠与高量眩晕解离幻觉)", "high", 0.97, "母体主导"),
        ],
    ),
    "SAM-e": pk(
        half_life="口服血浆消除典型约 4.0 - 6.0 小时 (典型 5.0h；迅速参入全身单碳甲基供体循环转化为 SAH)",
        half_life_hours=5.0,
        t_max="肠溶制剂空腹口服约 4.0 - 6.0 小时 (典型 5.0h，进食明显降低吸收)",
        t_max_hours=5.0,
        bioavailability="因游离分子极不稳定且经历极剧烈肠道/肝首过转甲基消耗，口服肠溶片全身绝对利用度仅约 2% - 5% (静注为 100%)",
        cyp_enzymes=[
            "完全不走肝脏 CYP450 系统 (生理性内源转甲基化限速分子：经多种甲基转移酶脱去甲基变成 SAH 再循回到同型半胱氨酸/谷胱甘肽池)",
        ],
        clearance="生理内源性并入转甲基、转硫途径（最终部分经肾排出硫酸盐或同化为磷脂/核酸/谷胱甘肽）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=5.0,
        washout_hours=24.0,
        washout_note="S-腺苷蛋氨酸 (SAM-e) 是人体生理必需通用甲基供体（辅助单胺神经递质合成与胆汁酸清除），本身完全不干扰 CYP450，约 24 小时回落平衡。重要精神安全警告：**绝对禁止双相障碍患者单用高剂量 SAM-e**（有极高诱发急性躁狂转相的明确临床实录！），并且联用 SSRI、SNRI 或 MAOI 抗抑郁药时会协同升高中枢 5-HT 活性，存在叠加血清素能激动甚至轻中度综合征的风险！",
        mechanism_summary="内源性全身甲基供体辅酶分子：肠溶吸收约 2 - 5% 并推动 5-HT/NE/DA 合成与肝脏转硫解毒通路，母体短时稳态后融入全身代谢池。",
        active_metabolites=[],
        receptor_targets=[
            target("儿茶酚胺 / 5-HT 合成与磷脂甲基转移酶 (COMT/PNMT/PEMT)", "直接提供活性甲基加速内源性去甲肾上腺素、多巴胺及 5-HT 生物合成与磷脂流动 (诱发双相转躁关键机制)", "high", 0.85, "母体主导"),
        ],
    ),
    "Benzydamine": pk(
        half_life="人类实测表观终末血浆消除半衰期约 8.0 小时 (广泛全身组织分布，Vd 高达 ~110 L)",
        half_life_hours=8.0,
        t_max="口服约 2.0 - 4.0 小时 (典型 3.0h)",
        t_max_hours=3.0,
        bioavailability="口服全身吸收优良约 87%（局部含漱口腔黏膜亦可发生明显局部与轻度全身吸收）",
        cyp_enzymes=[
            "主要经肝微粒体氧化生成苯达明-N-氧化物 (Benzydamine N-oxide) 及结合清除",
        ],
        clearance="肝内广泛氧化（以 N-氧化物为最主产物）、去烷基化及葡萄糖醛酸结合后主要经尿液排出（低系统清除率 ~160 mL/min）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=8.0,
        washout_hours=40.0,
        washout_note="苯达明（消炎灵）常规局部含漱作为温和镇痛消炎药极安全；但高剂量吞服（≥ 500 - 1500 mg 超说明书滥用时，因母体具备显著脂溶中枢入脑及约 8 小时长时间停留，完整洗脱需 40 小时）会爆发极奇异且剧烈、长达 10 - 24 小时的鲜明视幻觉、时间拖尾、激动震颤与类谵妄兴奋状态！高量严禁驾驶与合用兴奋剂/酒精。",
        mechanism_summary="局部镇痛非典型吲哚唑 NSAID：治疗量主要靠抑制局部前列腺素及稳膜止痛，超剂量入脑则激发明显多巴胺能/致幻兴奋协同。",
        active_metabolites=[],
        receptor_targets=[
            target("前列腺素 / 炎症细胞因子 & 局部钠通道稳膜", "外周非典型抗炎镇痛麻醉效应（常规咽喉喷雾/漱口核心）", "high", 0.90, "母体主导"),
            target("中枢多巴胺能与抗胆碱/5-HT2A 脱靶调节", "仅超大量口服时显著显现（诱发长达数小时逼真幻视、亢奋及极强震颤共济失调）", "moderate", 0.65, "母体主导"),
        ],
    ),
    "Bromantane": pk(
        half_life="人类受控口服研究实测消除半衰期约 11.21 小时 (显著长于大鼠 ~7h，且高度亲脂积聚脂肪慢速释出)",
        half_life_hours=11.2,
        t_max="显著性别吸收差异！女性口服约 2.75 小时达峰，男性约 4.0 小时达峰 (总体中位 ~3.4h)",
        t_max_hours=3.4,
        bioavailability="口服绝对生物利用度估计约 27.5% - 42% (受高脂溶分配影响)",
        cyp_enzymes=[
            "主要经肝脏微粒体细胞色素氧化羟化生成主力 6β-羟溴曼坦 (6β-hydroxybromantane)",
        ],
        clearance="肝脏广泛氧化羟化并在脂肪高度库藏后历经数天缓慢排泄（代谢产物在尿中长时间存留）",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=11.2,
        washout_hours=56.0,
        washout_note="溴曼坦（Ladasten / 神经节适应原促智药）并非传统瞬间倒空单胺的拟交感兴奋剂，而是通过上调纹状体 **酪氨酸羟化酶 (TH)** 与 **AADC** 基因表达来温和提升多巴胺生理合成能力兼轻微抑制 DAT/SERT；正因血浆半衰期达 11.2 小时（约 56h 完全清除）加上表观基因组上调后效可持续整天甚至次日，睡前服用容易扰乱入睡且已列为 WADA 禁药。",
        mechanism_summary="独特金刚烷类适应原兼多巴胺合成基因上调促醒抗乏药（区别于传统安非他命消耗囊泡），长半衰期平稳支撑 8 - 12 小时清朗耐力。",
        active_metabolites=[],
        receptor_targets=[
            target("酪氨酸羟化酶 (TH) & AADC 基因转录表达", "显著上调促内源性多巴胺生物合成（低耗竭与几乎无下行“崩溃感”的药理精髓）", "high", 0.90, "母体主导"),
            target("DAT / SERT", "高微摩尔级弱至中等再摄取抑制协同", "moderate", 0.65, "母体主导"),
        ],
    ),
    "5-Hydroxytryptophan": pk(
        half_life="约 2.2 - 7.4 小时 (典型成人口服研究均值约 3.0 小时；常见出现胃肠分节吸收双峰血药曲线)",
        half_life_hours=3.0,
        t_max="口服 1.0 - 2.0 小时 (典型 1.5h)",
        t_max_hours=1.5,
        bioavailability="单用口服生物利用度受剧烈外周 AADC 脱羧限制变化极大 (~20% - 35%)；若联用卡比多巴抑制外周脱羧则显著提升至 ~48% ± 15%！",
        cyp_enzymes=[
            "完全不依赖肝脏 CYP450 激活 (直接由广泛分布的芳香族 L-氨基酸脱羧酶 AADC/DDC 脱去羧基转化成内源血清素 5-HT)",
        ],
        clearance="几乎全数迅速经肠壁、肝与脑内 AADC 脱羧成为血清素 (5-HT)，继而被 MAO-A 氧化降解为 5-羟吲哚乙酸 (5-HIAA) 由尿排出",
        pk_role="PRODRUG",
        effective_half_life_hours=3.0,
        washout_hours=18.0,
        washout_note="重大常见错误交互警示！5-HTP 绝不同于 L-色氨酸：L-色氨酸尚需限速酶（色氨酸羟化酶）把关安全上限，而 **5-HTP 直接越过全部人体限速步骤**，一口吞下即在外周和脑内无节制地转化倾泻出大量活性血清素 5-HT！① 单独吞服极易因肠道外周产出的 5-HT 刺激引起剧烈反胃呕吐（通常配少量绿茶 EGCG 抑制外周 DDC）；② **绝不可与 SSRI、SNRI、MAOI 或曲马多联用，亦绝对禁忌在刚用完或正在使用 MDMA 时高剂量同吃——协同直接引爆致命性血清素综合征！**",
        mechanism_summary="绕开生理限速步的血清素直接前体氨基酸前药：由 AADC 一步直接转化为 5-HT，全周期无保护限速闸门。",
        active_metabolites=[
            met(
                "Serotonin (5-HT / 内源性 5-羟色胺)",
                "外周及中枢神经元芳香族 L-氨基酸脱羧酶 (AADC/DDC) 一步脱羧直接生成",
                "极快被突触回收及 MAO-A 降解 (脑内 5-HT 池升高维持数小时，随 5-HTP 约 3h 清除)",
                1.5,
                2.0,
                1.0,
                "完全承担 5-HTP 全部情绪舒缓、外周致吐及潜在 5-HT 综合征高危暴露",
                "直接激动全亚型 5-HT 受体并作为神经递质填充囊泡（跳过限速酶故高量极危险）",
            )
        ],
        receptor_targets=[
            target("全系中枢及外周 5-HT 受体 (间接通过释放 5-HT)", "无生理天花板快速抬升突触 5-HT 浓度（严防合用 MAOI/SSRI/MDMA！）", "high", 0.94, "代谢物主导"),
        ],
    ),
    "Citicoline": pk(
        half_life="极具特色的双峰吸收与极其缓慢的系统整合排泄：初峰 1h 随后第 2 峰出现在 24h；呼出 CO2 同位素消除表观半衰期约 56.0 小时、尿排表观半衰期高达 71.0 小时",
        half_life_hours=56.0,
        t_max="明确双峰特征 (Biphasic)：肠壁水解吸收入血第 1 峰约 1.0 小时，经脑与组织再分布重合成后第 2 高峰延至 24.0 小时",
        t_max_hours=1.0,
        bioavailability="口服吸收近乎完全 > 90%（< 1% 随粪便丢失；于肠壁全数水解为胆碱与胞苷——尿苷高效穿透血脑屏障）",
        cyp_enzymes=[
            "完全不走肝脏 CYP450 系统 (肠黏膜磷酸酶/焦磷酸酶水解生成胆碱与胞苷/尿苷，在大脑肯尼迪途径再合成磷脂酰胆碱膜)",
        ],
        clearance="纳入内源神经磷脂酰胆碱膜池与核酸代谢，仅极缓慢经呼吸 CO2（表观 t1/2 ≈ 56h）与尿液（表观 t1/2 ≈ 71h）更新淘汰",
        pk_role="PRODRUG",
        effective_half_life_hours=56.0,
        washout_hours=240.0,
        washout_note="胞磷胆碱 (CDP-Choline) 口服吸收率高达 90% 以上，并非以原形直接进脑，而是在小肠壁精准一分为二放出 **胆碱 (Choline)** 与 **胞苷/尿苷 (Cytidine → Uridine)** 双重营养底物后再于神经细胞重聚合成膜磷脂与乙酰胆碱！因为深度融合细胞膜磷脂周期（56 - 71 小时缓慢更新），属于非常安全且具跨日温和神经修复蓄积效力的促智基础剂；少数人在停服高胆碱前偶出现微弱轻微多梦或胆碱过剩情绪低落。",
        mechanism_summary="生理性双组分协同磷脂与乙酰胆碱前药：几乎 100% 吸收拆分为游离胆碱及尿苷共同穿透血脑屏障重塑神经元胞膜并支持多巴胺受体密度。",
        active_metabolites=[
            met(
                "Choline & Uridine (游离胆碱与转化尿苷)",
                "小肠壁刷状缘水解酶分解胞磷胆碱后胞苷进一步脱氨基为尿苷进入血液",
                "尿苷血浆数小时快步入脑，整体膜磷脂合成滞留更新长达 56 - 71 小时",
                56.0,
                24.0,
                1.0,
                "体内 100% 有效入脑与膜修复物质主体",
                "胆碱直接支持乙酰胆碱合成；尿苷刺激大脑多巴胺分泌与突触磷脂/膜新生修复",
            )
        ],
        receptor_targets=[
            target("乙酰胆碱前体池 & 肯尼迪磷脂膜合成途径", "双通路协同增加大脑乙酰胆碱合成并促进神经元细胞膜及 D2 多巴胺受体更新修复", "high", 0.88, "代谢物主导"),
        ],
    ),
    "Creatine": pk(
        half_life="血浆消除半衰期较短约 1.5 - 3.0 小时 (典型 2.2h；快速被骨骼肌与大脑 CrT 转运摄取储存，肌内磷酸肌酸池彻底消除需 4 - 6 周)",
        half_life_hours=2.2,
        t_max="口服水溶液或常释片约 0.8 - 1.5 小时 (典型 1.0h)",
        t_max_hours=1.0,
        bioavailability="小至常规单次剂量 (≤ 5 g) 口服吸收极佳 > 80% - 95%（一次吞服 10 - 20g 以上则肠转运饱和降吸收并致渗透性腹泻）",
        cyp_enzymes=[
            "完全不经肝脏 CYP450 代谢 (不可逆非酶促自发环化生成肌酐 Creatinine 随尿排出)",
        ],
        clearance="通过骨骼肌与脑神经血管平滑肌细胞膜 CRT1 (SLC6A8) 转运摄取储为磷酸肌酸，每日恒定约 1.5% - 2% 肌酸池自发脱水利化为肌酐由肾排出",
        pk_role="PARENT_DOMINANT",
        effective_half_life_hours=2.2,
        washout_hours=14.0,
        washout_note="一水肌酸血浆清零极快（2.2h 半衰期，十余小时即可离开血清），但真正发挥体能及缺睡脑雾认知增强作用依赖饱和脑内与肌肉里的磷酸肌酸 (PCr) 储库（每天 3 - 5g 连续补充约需 3 - 4 周饱和，停用亦缓慢递减月余）。提醒：补充肌酸会生理性小幅抬高血清肌酐化验值但**绝不代表肾损伤**（肾功能评估应结合胱抑素 C）；保持充足饮水。",
        mechanism_summary="胞内 ATP/ADP 瞬时高能磷酸盐缓冲补给剂：母体快吸快摄取入肌脑储存库并稳定 ATP 能量代谢。",
        active_metabolites=[],
        receptor_targets=[
            target("肌酸激酶 / 磷酸肌酸 (PCr-ATP) 缓冲系统", "细胞线粒体能量稳态调节（在睡眠剥夺与高强度脑力/体力负荷下显着抗疲劳与护脑认知）", "high", 0.90, "母体主导"),
        ],
    ),
}


def merge_substance_pk(sub: dict[str, Any], patch: dict[str, Any]) -> None:
    existing_pk = sub.get("pharmacokinetics")
    if existing_pk is None:
        sub["pharmacokinetics"] = copy.deepcopy(patch)
        return
    for key, val in patch.items():
        existing_pk[key] = copy.deepcopy(val)


def update_zh_dict(zh_dict_data: dict[str, Any]) -> list[str]:
    subs = zh_dict_data.setdefault("substances", {})
    synced: list[str] = []
    for item in NEW_CLINICAL_DRUGS:
        name_en = item["name"]
        zh_name = item["zh_name"]
        zh_aliases = item["zh_aliases"]
        spaced, joined, abbrs = build_pinyin_tokens(zh_aliases)
        pinyin_list: list[str] = []
        for sp, jn in zip(spaced, joined):
            pinyin_list.append(sp)
            pinyin_list.append(jn)
        subs[name_en] = {
            "name_en": name_en,
            "name_zh": zh_name,
            "zh_aliases": zh_aliases,
            "pinyin": pinyin_list,
            "pinyin_abbr": abbrs,
            "categories": item["categories"],
            "categories_zh": item["categories_zh"],
            "url": item["url"],
        }
        synced.append(name_en)
    return synced


def update_localization_helper(new_mappings: dict[str, str]) -> list[str]:
    content = HELPER_KT_PATH.read_text(encoding="utf-8")
    pattern = re.compile(
        r"(private val ZH_NAME_MAP: Map<String, String> = mapOf\()(?P<body>.*?)(\n    \))",
        re.DOTALL,
    )
    match = pattern.search(content)
    if not match:
        raise RuntimeError("Could not locate ZH_NAME_MAP inside SubstanceLocalizationHelper.kt")

    body = match.group("body")
    lines = body.splitlines()
    inserted: list[str] = []

    for en_name, zh_name in new_mappings.items():
        if re.search(rf'"{re.escape(en_name)}"\s+to\s+"', body):
            # Already mapped; assert consistency
            existing = re.search(rf'"{re.escape(en_name)}"\s+to\s+"([^"]+)"', body)
            if existing and existing.group(1) != zh_name:
                raise ValueError(
                    f"ZH_NAME_MAP conflict for {en_name}: existing={existing.group(1)} vs new={zh_name}"
                )
            continue
        entry_line = f'        "{en_name}" to "{zh_name}",'
        # Insert in case-insensitive alphabetical order among ASCII substance entries
        insert_idx = len(lines)
        for idx, line in enumerate(lines):
            m_key = re.match(r'\s*"([^"]+)"\s+to\s+"', line)
            if not m_key:
                continue
            key_text = m_key.group(1)
            # Stop before non-ASCII trailing strings (Chinese) or when alphabetical order passes
            if not key_text[0].isascii():
                insert_idx = idx
                break
            if key_text.lower() > en_name.lower():
                insert_idx = idx
                break
        lines.insert(insert_idx, entry_line)
        body = "\n".join(lines)
        inserted.append(en_name)

    updated_content = content[: match.start("body")] + body + content[match.end("body") :]
    HELPER_KT_PATH.write_text(updated_content, encoding="utf-8")
    return inserted


def validate_all(
    substances_data: dict[str, Any],
    zh_dict_data: dict[str, Any],
    enriched_names: list[str],
    new_names: list[str],
) -> None:
    valid_categories = {
        c["name"] if isinstance(c, dict) else str(c)
        for c in substances_data["categories"]
    }
    sub_list: list[dict[str, Any]] = substances_data["substances"]
    sub_map: dict[str, dict[str, Any]] = {s["name"]: s for s in sub_list}

    # 1. Global catalog checks
    seen_names: set[str] = set()
    for s in sub_list:
        name = s.get("name", "")
        assert isinstance(name, str) and name.strip(), f"Empty substance name: {s}"
        assert name not in seen_names, f"Duplicate substance name: {name}"
        seen_names.add(name)
        assert isinstance(s.get("url"), str) and s["url"].strip(), f"Missing url on {name}"
        for cat in s.get("categories", []):
            assert cat in valid_categories, f"Invalid category '{cat}' on {name}"
        for roa in s.get("roas", []):
            dose = roa.get("dose")
            if isinstance(dose, dict):
                assert isinstance(dose.get("units"), str) and dose["units"].strip(), (
                    f"Missing dose.units on {name} {roa.get('name')}"
                )
                prev_val: float | None = None
                prev_key: str | None = None
                for k in ("threshold", "lightMin", "commonMin", "strongMin", "heavyMin"):
                    v = dose.get(k)
                    if isinstance(v, (int, float)):
                        assert v >= 0, f"Negative dose {k}={v} on {name}"
                        if prev_val is not None:
                            if name in new_names:
                                assert prev_val < v, (
                                    f"Non-strictly increasing dose on new drug {name}: "
                                    f"{prev_key}={prev_val} >= {k}={v}"
                                )
                            elif prev_key != "threshold":
                                assert prev_val < v, (
                                    f"Non-increasing dose on {name}: {prev_key}={prev_val} >= {k}={v}"
                                )
                            else:
                                assert prev_val <= v, (
                                    f"Decreasing dose on {name}: {prev_key}={prev_val} > {k}={v}"
                                )
                        prev_val = float(v)
                        prev_key = k
            dur = roa.get("duration")
            if isinstance(dur, dict):
                for phase_name, rng in dur.items():
                    if isinstance(rng, dict):
                        units = rng.get("units")
                        assert units in VALID_DURATION_UNITS, (
                            f"Invalid duration units '{units}' on {name} {roa.get('name')}.{phase_name}"
                        )
                        mn, mx = rng.get("min"), rng.get("max")
                        if isinstance(mn, (int, float)) and isinstance(mx, (int, float)):
                            assert mn <= mx, f"Duration min > max on {name}.{phase_name}: {mn} > {mx}"

    # 2. Target Phase 5 substance checks (10 new + enriched existing)
    for name in new_names + enriched_names:
        assert name in sub_map, f"Missing target substance {name}"
        sub = sub_map[name]
        cats = sub.get("categories", [])
        assert isinstance(cats, list) and len(cats) > 0, f"Empty categories on {name}"
        pk_obj = sub.get("pharmacokinetics")
        assert isinstance(pk_obj, dict), f"Missing pharmacokinetics on {name}"
        assert pk_obj.get("pkRole") in VALID_PK_ROLES, f"Invalid pkRole on {name}: {pk_obj.get('pkRole')}"
        for num_field in ("halfLifeHours", "tMaxHours", "effectiveHalfLifeHours", "washoutHours"):
            val = pk_obj.get(num_field)
            assert isinstance(val, (int, float)) and val > 0, (
                f"Invalid {num_field}={val} on {name}"
            )
        for text_field in ("halfLife", "tMax", "bioavailability", "clearance", "washoutNote", "mechanismSummary"):
            val = pk_obj.get(text_field)
            assert isinstance(val, str) and val.strip(), f"Empty {text_field} on {name}"
        assert isinstance(pk_obj.get("cypEnzymes"), list) and len(pk_obj["cypEnzymes"]) > 0, (
            f"Empty cypEnzymes on {name}"
        )
        assert isinstance(pk_obj.get("receptorTargets"), list) and len(pk_obj["receptorTargets"]) > 0, (
            f"Empty receptorTargets on {name}"
        )
        for m in pk_obj.get("activeMetabolites", []):
            assert isinstance(m.get("name"), str) and m["name"].strip(), f"Empty metabolite name on {name}"
            assert isinstance(m.get("halfLifeHours"), (int, float)) and m["halfLifeHours"] > 0, (
                f"Invalid metabolite halfLifeHours on {name}: {m}"
            )
            if m.get("tMaxHours") is not None:
                assert isinstance(m["tMaxHours"], (int, float)) and m["tMaxHours"] > 0, (
                    f"Invalid metabolite tMaxHours on {name}: {m}"
                )
            assert isinstance(m.get("exposureRatio"), (int, float)) and m["exposureRatio"] > 0, (
                f"Invalid metabolite exposureRatio on {name}: {m}"
            )

    # 3. Verify 10 new drugs exist cleanly in zh_dict & ZH_NAME_MAP
    zh_subs = zh_dict_data.get("substances", {})
    kt_content = HELPER_KT_PATH.read_text(encoding="utf-8")
    for item in NEW_CLINICAL_DRUGS:
        name = item["name"]
        assert name in zh_subs, f"Missing {name} in substances_zh_dict.json"
        assert zh_subs[name]["name_zh"] == item["zh_name"]
        assert f'"{name}" to "{item["zh_name"]}"' in kt_content, (
            f"Missing ZH_NAME_MAP entry for {name} -> {item['zh_name']}"
        )

    # 4. Verify intentionally omitted no-human-PK substances STILL have no pharmacokinetics
    for omitted in INTENTIONALLY_OMITTED_NO_HUMAN_PK:
        if omitted in sub_map:
            assert sub_map[omitted].get("pharmacokinetics") is None, (
                f"Iron-rule violation: {omitted} must NOT have fabricated pharmacokinetics!"
            )


def main() -> None:
    with SUBSTANCES_PATH.open("r", encoding="utf-8") as f:
        substances_data = json.load(f)

    valid_categories = {
        c["name"] if isinstance(c, dict) else str(c)
        for c in substances_data["categories"]
    }
    sub_list: list[dict[str, Any]] = substances_data["substances"]
    sub_map: dict[str, dict[str, Any]] = {s["name"]: s for s in sub_list}

    # Add 10 new clinical Rx drugs cleanly and idempotently
    newly_inserted: list[str] = []
    updated_existing_new: list[str] = []
    new_names: list[str] = []
    for raw_new in NEW_CLINICAL_DRUGS:
        entry = {
            k: copy.deepcopy(v)
            for k, v in raw_new.items()
            if k not in ("zh_name", "zh_aliases", "categories_zh")
        }
        unknown_cats = [c for c in entry["categories"] if c not in valid_categories]
        if unknown_cats:
            raise ValueError(f"Unknown categories {unknown_cats} in {entry['name']}")
        if entry["name"] in sub_map:
            sub_map[entry["name"]].update(entry)
            updated_existing_new.append(entry["name"])
        else:
            sub_list.append(entry)
            sub_map[entry["name"]] = entry
            newly_inserted.append(entry["name"])
        new_names.append(entry["name"])

    # Enrich existing substances with verified human clinical PK
    enriched_names: list[str] = []
    missing_targets: list[str] = []
    for name, patch in EXISTING_PK_ENRICHMENTS.items():
        if name not in sub_map:
            missing_targets.append(name)
            continue
        # Ensure Tilidin has valid non-empty categories without overriding existing populated categories
        if name == "Tilidin" and not sub_map[name].get("categories"):
            sub_map[name]["categories"] = ["opioid", "depressant", "habit-forming", "analgesic"]
        merge_substance_pk(sub_map[name], patch)
        enriched_names.append(name)

    if missing_targets:
        raise KeyError(f"Targets missing in Substances.json: {missing_targets}")

    # Persist Substances.json (2-space indent, clean UTF-8 LF)
    with SUBSTANCES_PATH.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(substances_data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    # Sync substances_zh_dict.json
    with ZH_DICT_PATH.open("r", encoding="utf-8") as f:
        zh_dict_data = json.load(f)
    zh_synced = update_zh_dict(zh_dict_data)
    with ZH_DICT_PATH.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(zh_dict_data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    # Sync SubstanceLocalizationHelper.kt ZH_NAME_MAP
    kt_inserted = update_localization_helper(
        {item["name"]: item["zh_name"] for item in NEW_CLINICAL_DRUGS}
    )

    # Full post-write validation
    validate_all(substances_data, zh_dict_data, enriched_names, new_names)

    pk_total = sum(1 for s in sub_list if s.get("pharmacokinetics"))
    no_pk_total = len(sub_list) - pk_total
    size_mb = SUBSTANCES_PATH.stat().st_size / (1024 * 1024)

    print(
        f"Phase 5 enrichment complete: total substances={len(sub_list)}, "
        f"with_pk={pk_total}, without_pk={no_pk_total}"
    )
    print(f"Existing substances PK-enriched: {len(enriched_names)}")
    print(
        f"New Rx drugs: inserted={newly_inserted}, idempotent_updated={updated_existing_new}"
    )
    print(f"zh_dict synced: {zh_synced}; ZH_NAME_MAP newly inserted: {kt_inserted}")
    print(
        f"Verified omitted (no human clinical PK): {len(INTENTIONALLY_OMITTED_NO_HUMAN_PK)} checked entries remain null"
    )
    print(f"Substances.json size: {size_mb:.2f} MB")


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""
Upgrade script for dual-track pharmacokinetics (parent + active metabolites),
clinical washout windows, and pharmacodynamic receptor profiles in Substances.json,
plus missing psychiatric medications (Cariprazine, Brexpiprazole, Desvenlafaxine).

Usage:
    python tools/upgrade_dual_pk_and_receptors.py
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


def build_pinyin_tokens(names: list[str]) -> tuple[list[str], list[str], list[str]]:
    """Generate spaced pinyin, joined pinyin, and abbr tokens matching inject_psychiatric_and_hrt.py."""
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
    # Deduplicate while preserving order
    return (
        list(dict.fromkeys(pinyin_spaced)),
        list(dict.fromkeys(pinyin_raw)),
        list(dict.fromkeys(abbrs)),
    )


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
    actor: str,
) -> dict[str, Any]:
    return {
        "target": name,
        "action": action,
        "affinityTier": tier,
        "affinityWeight": float(weight),
        "dominantActor": actor,
    }


def make_common_names(zh_names: list[str], en_aliases: list[str]) -> list[str]:
    spaced, joined, abbrs = build_pinyin_tokens(zh_names)
    combined_pinyin: list[str] = []
    for sp, jn in zip(spaced, joined):
        combined_pinyin.append(sp)
        combined_pinyin.append(jn)
    return list(dict.fromkeys([*zh_names, *en_aliases, *abbrs, *combined_pinyin]))


# ---------------------------------------------------------------------------
# 1. Three missing psychiatric drugs to add cleanly across all 3 assets
# ---------------------------------------------------------------------------
NEW_SUBSTANCES: list[dict[str, Any]] = [
    {
        "name": "Cariprazine",
        "zh_name": "卡利哌嗪",
        "zh_aliases": ["卡利哌嗪", "瑞吉拉", "Vraylar", "Reagila"],
        "commonNames": make_common_names(
            ["卡利哌嗪", "瑞吉拉"],
            ["Vraylar", "Reagila", "RGH-188"],
        ),
        "url": "https://en.wikipedia.org/wiki/Cariprazine",
        "isApproved": True,
        "tolerance": {
            "full": "随 DDCAR 数周蓄积达到稳态药效，非耐受性脱敏",
            "half": "停药后约 2-3 周",
            "zero": "4 - 6 周 (取决于 DDCAR 长消除半衰期)",
        },
        "crossTolerances": ["dopamine", "antipsychotic"],
        "addictionPotential": "无成瘾性及滥用潜力，但存在显著延迟型静坐不能与锥体外系反应风险",
        "toxicities": [
            "延迟性静坐不能 (停药或加量数周后随 DDCAR 蓄积显现)",
            "锥体外系反应 (EPS) 与帕金森样肌张力障碍",
            "老年人痴呆相关性精神病死亡风险增加 (黑框警示)",
        ],
        "categories": ["antipsychotic", "atypical antipsychotic", "common"],
        "categories_zh": ["抗精神病药", "非典型抗精神病药", "常见物质"],
        "summary": "第三代 D3 偏好型 D3/D2 部分激动剂（与 5-HT1A 部分激动、5-HT2B 拮抗）。核心特征为经 CYP3A4 生成极长半衰期活性代谢物 DDCAR（1-3 周），稳态暴露量可达母体 2-4 倍，主导双相抑郁、精神分裂症阴性症状治疗及长达 4-6 周的临床洗脱期。",
        "interactions": {
            "dangerous": [
                "强效 CYP3A4 抑制剂 (如伊曲康唑/酮康唑/克拉霉素/利托那韦，可使总活性组分 AUC 翻倍以上，需减半甚至停用)",
                "强效 CYP3A4 诱导剂 (如卡马西平/苯妥英/利福平/圣约翰草，严重降低血药浓度)",
            ],
            "unsafe": [
                "酒精 (乙醇) 与中枢神经抑制剂 (加重嗜睡与直立性低血压)",
                "其他多巴胺受体阻滞剂或锂盐 (叠加锥体外系反应与神经阻滞剂恶性综合征风险)",
                "单胺氧化酶抑制剂 (MAOIs) 或三环类抗抑郁药",
            ],
            "uncertain": [
                "CYP2D6 抑制剂 (对 DCAR/DDCAR 转化次要影响)",
                "葡萄柚汁 (中度 CYP3A4 抑制，建议避免大量同服)",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 1.5,
                    "commonMin": 3.0,
                    "strongMin": 4.5,
                    "heavyMin": 6.0,
                },
                "duration": {
                    "onset": {"min": 1.0, "max": 3.0, "units": "hours"},
                    "comeup": {"min": 2.0, "max": 4.0, "units": "hours"},
                    "peak": {"min": 3.0, "max": 6.0, "units": "hours"},
                    "offset": {"min": 48.0, "max": 96.0, "units": "hours"},
                    "total": {"min": 72.0, "max": 168.0, "units": "hours"},
                },
                "bioavailability": {"min": 50.0, "max": 80.0},
            }
        ],
        "pharmacokinetics": {
            "brandName": "瑞吉拉 (Vraylar / Reagila) | 艾伯维 (AbbVie) / 吉瑞医药 (Gedeon Richter)",
            "halfLife": "母体 2 - 4 天 (48 - 96h)；DCAR 1 - 2 天；主力活性代谢物 DDCAR 长达 1 - 3 周 (240 - 504h)",
            "halfLifeHours": 72.0,
            "tMax": "3.0 - 6.0 小时 (DDCAR 稳态达峰约 4 - 8 周)",
            "tMaxHours": 4.5,
            "bioavailability": "约 52% - 80% (高脂饮食略延后达峰但总暴露量相当)",
            "cypEnzymes": [
                "CYP3A4 (主要负责母体→DCAR→DDCAR 级联脱甲基与清除)",
                "CYP2D6 (次要途径，受多态性影响极小)",
            ],
            "clearance": "肝脏经 CYP3A4 逐级脱甲基生成去甲基卡利哌嗪 (DCAR) 与双去甲基卡利哌嗪 (DDCAR)；总活性组分主要通过极缓慢的 DDCAR 消除与胆汁/肾脏排泄",
            "indications": [
                "精神分裂症 (尤其伴突出阴性症状及动机缺损)",
                "双相 I 型障碍躁狂及混合发作",
                "双相 I 型障碍重度抑郁发作 (低剂量 1.5-3 mg 增效)",
                "重度抑郁症 (MDD) 辅助治疗",
            ],
            "contraindications": [
                "联用强效或中效 CYP3A4 诱导剂 (疗效骤降)",
                "对卡利哌嗪过敏或严重迟发性运动障碍病史",
                "老年痴呆相关性精神病 (增加死亡与脑血管事件风险)",
            ],
            "pkRole": "ACTIVE_METABOLITE",
            "effectiveHalfLifeHours": 336.0,
            "washoutHours": 1008.0,
            "washoutNote": "卡利哌嗪绝不能按母体 2-4 天半衰期评估停药速度：核心活性代谢物 DDCAR 半衰期长达 1-3 周，长期规律给药后稳态浓度可达母体的 200%-400%。减量或停药后 3-6 周内 DDCAR 仍持续占据大脑 D3/D2 受体，既解释为何副作用常在加药数周后才延迟出现，也决定换药清洗期需按数周计。",
            "mechanismSummary": "高选择性 D3 偏好型 D3/D2 部分激动剂（体外 D3 亲和力约为 D2 的 6-8 倍），由母体与超长效活性代谢物 DDCAR 共同维持长达数周的多巴胺系统稳态调节。",
            "activeMetabolites": [
                met(
                    name="DCAR (去甲基卡利哌嗪)",
                    pathway="母体经 CYP3A4 (主) 与 CYP2D6 (次) 首次 N-脱甲基",
                    half_life="1 - 2 天 (26 - 38 小时)",
                    half_life_hours=32.0,
                    t_max_hours=6.0,
                    exposure_ratio=0.35,
                    relative_exposure="稳态血药浓度约为母体的 30% - 40%",
                    role="具备与母体相当的 D3/D2 部分激动活性，为进一步转化为 DDCAR 的中间活性代谢体",
                ),
                met(
                    name="DDCAR (双去甲基卡利哌嗪)",
                    pathway="DCAR 经 CYP3A4 继续二次脱甲基生成，消除极度缓慢",
                    half_life="1 - 3 周 (约 240 - 504 小时，典型中位 14 天)",
                    half_life_hours=336.0,
                    t_max_hours=48.0,
                    exposure_ratio=3.0,
                    relative_exposure="稳态暴露量达母体的 200% - 400%（绝对药效主力）",
                    role="核心活性代谢物，主导维持期 D3/D2 受体占领率、迟发性静坐不能及停药后数周残余药效与相互作用窗口",
                ),
            ],
            "receptorTargets": [
                target("D3 受体", "高亲和力部分激动剂 (优选靶点, Ki ≈ 0.085 nM)", "high", 0.98, "母体 + DDCAR 共主导"),
                target("D2 受体", "部分激动剂 (内源性活性适中, Ki ≈ 0.49 nM)", "high", 0.88, "母体 + DDCAR 共主导"),
                target("5-HT2B 受体", "高亲和力拮抗剂 (Ki ≈ 0.58 nM)", "high", 0.85, "母体 + DDCAR 共主导"),
                target("5-HT1A 受体", "部分激动剂 (助抗抑郁与抗焦虑, Ki ≈ 2.6 nM)", "moderate", 0.72, "母体主导"),
                target("5-HT2A 受体", "弱至中等拮抗剂 (Ki ≈ 18.8 nM)", "low", 0.45, "母体主导"),
                target("H1 / M1 受体", "极弱或无显著结合 (低代谢综合征与低嗜睡)", "low", 0.35, "母体主导"),
            ],
        },
    },
    {
        "name": "Brexpiprazole",
        "zh_name": "依派哌唑",
        "zh_aliases": ["依派哌唑", "布瑞哌唑", "瑞雪丽", "Rexulti"],
        "commonNames": make_common_names(
            ["依派哌唑", "布瑞哌唑", "瑞雪丽"],
            ["Rexulti", "OPC-34712"],
        ),
        "url": "https://en.wikipedia.org/wiki/Brexpiprazole",
        "isApproved": True,
        "tolerance": {
            "full": "随 91h 半衰期约 10-14 天达稳态，无耐受脱敏",
            "half": "停药后约 4 天",
            "zero": "约 18 - 21 天",
        },
        "crossTolerances": ["dopamine", "antipsychotic"],
        "addictionPotential": "无躯体成瘾性，但过快加量或合用抑制剂可致静坐不能与冲动控制行为异常",
        "toxicities": [
            "体重增加与代谢变化（通常介于阿立哌唑与奥氮平之间）",
            "静坐不能（发生率低于阿立哌唑但仍需随访）",
            "老年痴呆精神病死亡风险增加",
        ],
        "categories": ["antipsychotic", "atypical antipsychotic", "antidepressant"],
        "categories_zh": ["抗精神病药", "非典型抗精神病药", "抗抑郁药"],
        "summary": "新一代 SDAM（5-HT 与多巴胺活性调节剂），结构源自阿立哌唑但对 D2 内源性激动活性更低且 5-HT1A/5-HT2A/α1B 拮抗显著增强，静坐不能与激越发生率更低；母体半衰期 91 小时直接主导稳态药效，主要代谢物 DM-3411 不入脑。",
        "interactions": {
            "dangerous": [
                "同时存在 CYP2D6 慢代谢基因型且联用强效 CYP3A4 抑制剂 (需将剂量降至原剂量的 1/4)",
            ],
            "unsafe": [
                "强效 CYP3A4 或 CYP2D6 抑制剂 (如氟西汀、帕罗西汀、酮康唑、伊曲康唑，剂量需减半)",
                "强效 CYP3A4 诱导剂 (如卡马西平、利福平，剂量通常需翻倍)",
                "酒精及苯二氮䓬类中枢抑制剂",
            ],
            "uncertain": [
                "单胺氧化酶抑制剂 (MAOIs) 及强肾上腺素α阻滞剂",
            ],
        },
        "roas": [
            {
                "name": "oral",
                "dose": {
                    "units": "mg",
                    "lightMin": 0.5,
                    "commonMin": 2.0,
                    "strongMin": 3.0,
                    "heavyMin": 4.0,
                },
                "duration": {
                    "onset": {"min": 1.0, "max": 2.5, "units": "hours"},
                    "comeup": {"min": 1.5, "max": 3.5, "units": "hours"},
                    "peak": {"min": 3.5, "max": 5.0, "units": "hours"},
                    "offset": {"min": 48.0, "max": 96.0, "units": "hours"},
                    "total": {"min": 72.0, "max": 120.0, "units": "hours"},
                },
                "bioavailability": {"min": 90.0, "max": 95.0},
            }
        ],
        "pharmacokinetics": {
            "brandName": "瑞雪丽 (Rexulti) | 大冢制药 (Otsuka) / 灵北 (Lundbeck)",
            "halfLife": "91 小时 (母体原形主导；主循环代谢物 DM-3411 几乎不透过血脑屏障，无临床中枢贡献)",
            "halfLifeHours": 91.0,
            "tMax": "4.0 小时",
            "tMaxHours": 4.0,
            "bioavailability": "95% (几乎不受食物影响)",
            "cypEnzymes": [
                "CYP3A4 (主要代谢酶)",
                "CYP2D6 (主要代谢酶；慢代谢者与联用抑制剂时血药浓度显著翻倍)",
            ],
            "clearance": "主要经 CYP3A4 与 CYP2D6 氧化生成无显著中枢活性的 DM-3411 对比对照产物；约 25% 经尿液、46% 经粪便消除，极少以原形排泄",
            "indications": [
                "重度抑郁症 (MDD) 辅助增效治疗",
                "精神分裂症维持与急性期治疗",
                "阿尔茨海默病相关激越症状 (特定获批适应症)",
            ],
            "contraindications": [
                "对依派哌唑过敏者",
                "未调整剂量的重度 CYP2D6/3A4 双重酶抑制联合用药",
            ],
            "pkRole": "PARENT_DOMINANT",
            "effectiveHalfLifeHours": 91.0,
            "washoutHours": 455.0,
            "washoutNote": "母体原形半衰期长达 91 小时（约 3.8 天），对照代谢物 DM-3411 不参与中枢疗效；连续服用需约 10-14 天达稳态，停药完全洗脱需约 19 天（455 小时）。合用氟西汀、帕罗西汀等 CYP2D6 强抑制剂会明显叠加延长母体停留。",
            "mechanismSummary": "母体原形直接发挥 5-HT1A/D2 部分激动与强效 5-HT2A/α1B 拮抗协同作用，无活跃中枢代谢物接力。",
            "activeMetabolites": [],
            "receptorTargets": [
                target("5-HT1A 受体", "强效部分激动剂 (Ki ≈ 0.12 nM，抗焦虑与改善前额叶功能)", "high", 0.96, "母体主导"),
                target("D2 受体", "低内源性活性部分激动剂 (Ki ≈ 0.30 nM，平衡阻滞与低静坐不能)", "high", 0.92, "母体主导"),
                target("5-HT2A 受体", "强效拮抗剂 (Ki ≈ 0.47 nM)", "high", 0.90, "母体主导"),
                target("α1B / α2C 肾上腺素受体", "强效拮抗剂 (Ki ≈ 0.17 nM，稳定躁动并可能致轻度直立低血压)", "high", 0.88, "母体主导"),
                target("D3 受体", "部分激动剂 (Ki ≈ 1.1 nM)", "moderate", 0.72, "母体主导"),
                target("H1 受体", "弱至中等拮抗剂 (Ki ≈ 19 nM)", "low", 0.45, "母体主导"),
            ],
        },
    },
    {
        "name": "Desvenlafaxine",
        "zh_name": "去甲文拉法辛",
        "zh_aliases": ["去甲文拉法辛", "地文拉法辛", "Pristiq", "ODV"],
        "commonNames": make_common_names(
            ["去甲文拉法辛", "地文拉法辛"],
            ["Pristiq", "ODV", "O-desmethylvenlafaxine", "WY-45233"],
        ),
        "url": "https://en.wikipedia.org/wiki/Desvenlafaxine",
        "isApproved": True,
        "tolerance": {
            "full": "药效随稳态 2-4 周逐步展开，骤停极易引发撤药反应",
            "half": "停药后约 24 小时",
            "zero": "停药后约 3 - 5 天 (撤药晕眩症状可能持续更久)",
        },
        "crossTolerances": ["snri", "ssri"],
        "addictionPotential": "无精神成瘾渴求，但因 11h 半衰期偏短而具有较高躯体撤药依赖反应（电击感、头晕）",
        "toxicities": [
            "剂量相关性收缩压/舒张压升高（100 mg/d 以上尤需监测）",
            "骤停引发显著 SNRI 撤药综合征",
            "与血清素能药物合用致 5-HT 综合征",
        ],
        "categories": ["antidepressant", "snri", "common"],
        "categories_zh": ["抗抑郁药", "SNRI", "常见物质"],
        "summary": "文拉法辛体内主要活性代谢产物 (O-去甲基文拉法辛 / ODV) 的直接成品化缓释制剂。无需经由 CYP2D6 酶代谢活化，彻底绕开 CYP2D6 基因多态性造成的个体疗效与副作用差异，同时提供稳定的 SERT/NET 双通道抑制。",
        "interactions": {
            "dangerous": [
                "单胺氧化酶抑制剂 (MAOIs，停药后至少间隔 7 天方可使用 MAOI；停用 MAOI 需间隔 14 天)",
                "利奈唑胺与亚甲蓝 (致死性血清素综合征)",
            ],
            "unsafe": [
                "SSRIs、其他 SNRIs、曲马多、锂盐、圣约翰草、右美沙芬 (血清素综合征及出血风险)",
                "文拉法辛 (与母体药效成分高度重叠，严禁重复叠服)",
                "非甾体抗炎药 (NSAIDs) 与阿司匹林 (协同增加上消化道出血风险)",
            ],
            "uncertain": [
                "强效 CYP3A4 抑制剂 (影响极有限，因仅极少部分经 CYP3A4 代谢)",
                "拟交感兴奋剂 (叠加升压及心率过速反应)",
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
                    "onset": {"min": 1.0, "max": 2.0, "units": "hours"},
                    "comeup": {"min": 2.0, "max": 4.0, "units": "hours"},
                    "peak": {"min": 6.0, "max": 8.0, "units": "hours"},
                    "offset": {"min": 12.0, "max": 20.0, "units": "hours"},
                    "total": {"min": 20.0, "max": 28.0, "units": "hours"},
                },
                "bioavailability": {"min": 75.0, "max": 82.0},
            }
        ],
        "pharmacokinetics": {
            "brandName": "Pristiq (去甲文拉法辛缓释片) | 辉瑞 (Pfizer)",
            "halfLife": "约 11 小时 (自身即文拉法辛的核心活性代谢体 ODV，口服直接入血发挥作用)",
            "halfLifeHours": 11.0,
            "tMax": "7.5 小时 (缓释渗透泵/基质稳定释放)",
            "tMaxHours": 7.5,
            "bioavailability": "80% (不受食物显著影响)",
            "cypEnzymes": [
                "主要经 UGT 酶系直接葡糖醛酸化及约 45% 原形经肾排泄",
                "CYP3A4 仅参与微弱 N-去甲基 (<5%)，完全绕开 CYP2D6 代谢",
            ],
            "clearance": "约 45% 原形经肾脏排泄、19% 以葡糖醛酸结合物排泄；肾功能中重度不全者需将剂量减半或改为隔日一次",
            "indications": [
                "成人重度抑郁症 (MDD)",
                "伴疼痛或显著乏力的抑郁症 / 围绝经期潮热 (超说明书)",
            ],
            "contraindications": [
                "过去 14 天内曾使用 MAOI 或存在重度未控制高血压",
                "对文拉法辛或去甲文拉法辛严重过敏者",
            ],
            "pkRole": "PARENT_DOMINANT",
            "effectiveHalfLifeHours": 11.0,
            "washoutHours": 72.0,
            "washoutNote": "半衰期约 11 小时（3 天内完成药动学清洗），药物相互作用极少受 CYP2D6 抑制剂影响；但停药减量仍需逐周梯级递减以减轻 SNRI 停药反应。",
            "mechanismSummary": "文拉法辛已活化产物的直接给药形态：母体本身即可均衡抑制 SERT 与 NET（约 10:1 选择性），药代线性可预测且极少 CYP 药物冲突。",
            "activeMetabolites": [],
            "receptorTargets": [
                target("SERT (5-HT 转运体)", "高亲和力抑制剂 (Ki ≈ 40 nM)", "high", 0.90, "母体主导"),
                target("NET (去甲肾上腺素转运体)", "中至强效抑制剂 (Ki ≈ 558 nM，稳定抑制)", "moderate", 0.72, "母体主导"),
                target("DAT (多巴胺转运体)", "无显著结合 (临床剂量几乎无直接抑制)", "low", 0.12, "母体主导"),
            ],
        },
    },
]


# ---------------------------------------------------------------------------
# 2. Upgrades for ~69 existing substances (partial patch merged into existing PK)
# ---------------------------------------------------------------------------
PK_UPGRADES: dict[str, dict[str, Any]] = {
    # === SSRIs ===
    "Fluoxetine": {
        "halfLife": "母体 1 - 4 天 (典型 72h)；核心活性代谢物去甲氟西汀长达 7 - 15 天 (典型 216h)",
        "halfLifeHours": 72.0,
        "tMax": "6.0 - 8.0 小时",
        "tMaxHours": 7.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 216.0,
        "washoutHours": 840.0,
        "washoutNote": "去甲氟西汀半衰期高达 7-15 天（且抑制 CYP2D6/2C19 具有自我蓄积延迟消除效应）；临床规范要求停用氟西汀后至少等待整整 5 周（35 天 / 840h）才可安全启用 MAOIs 或美沙酮/硫利达嗪等高危联用。",
        "mechanismSummary": "高选择性 SSRI 兼微弱 5-HT2C 拮抗剂：母体与极长效等活代谢物去甲氟西汀共同维持持久 SERT 占领及强效 CYP2D6 酶抑制。",
        "activeMetabolites": [
            met(
                "Norfluoxetine (去甲氟西汀)",
                "经 CYP2D6 / CYP2C9 / CYP2C19 N-脱甲基生成",
                "7 - 15 天 (168 - 360 小时，典型约 9 天 / 216h)",
                216.0,
                24.0,
                1.5,
                "稳态 S-去甲氟西汀暴露量达母体的 100% - 200%",
                "与母体几乎等强抑制 SERT 且同为 CYP2D6 强抑制剂，彻底决定长达 5 周的停药清洗期与低撤药反应率",
            ),
        ],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "高亲和力强抑制剂 (Ki ≈ 0.8 nM)", "high", 0.96, "双相共同主导"),
            target("5-HT2C 受体", "弱至中等拮抗剂 (促前额叶 NE/DA 间接释放)", "low", 0.42, "母体主导"),
            target("CYP2D6 酶 (药代动力学靶点)", "极强效可逆/机制抑制剂 (致并用药浓度飙升)", "high", 0.92, "双相共同主导"),
        ],
    },
    "Sertraline": {
        "halfLife": "母体约 26 小时 (弱活性去甲基舍曲林 62 - 104 小时)",
        "halfLifeHours": 26.0,
        "tMaxHours": 6.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 36.0,
        "washoutHours": 336.0,
        "washoutNote": "虽去甲基舍曲林半衰期 62-104 小时，但其 SERT 抑制效力仅约母体 5%-10%，故药效仍由母体主导；出于致死性血清素综合征防线，停药转用 MAOI 仍需等待 14 天（336h）。",
        "mechanismSummary": "母体主导的 SSRI（兼具一定 DAT 弱抑制与高 σ1 受体亲和力），对 CYP 酶系干预整体温和。",
        "activeMetabolites": [
            met(
                "N-Desmethylsertraline (去甲基舍曲林)",
                "经 CYP2B6 / CYP2C19 / CYP3A4 N-脱甲基",
                "62 - 104 小时 (典型 80h)",
                80.0,
                12.0,
                1.4,
                "稳态血浆总浓度高，但药理效价仅为母体 1/10–1/20",
                "极弱 SERT 抑制残余体，贡献少量稳态拖尾但临床疗效由母体原形绝对主导",
            ),
        ],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "超强亲和力抑制剂 (Ki ≈ 0.29 nM)", "high", 0.97, "母体主导"),
            target("σ1 (Sigma-1) 受体", "拮抗剂/调节剂 (可能参与抗焦虑及精神病性抑郁调节)", "moderate", 0.68, "母体主导"),
            target("DAT (多巴胺转运体)", "弱至中等抑制 (SSRI 中相对较强，高剂量有贡献)", "low", 0.45, "母体主导"),
        ],
    },
    "Escitalopram": {
        "halfLifeHours": 30.0,
        "tMaxHours": 4.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 30.0,
        "washoutHours": 336.0,
        "washoutNote": "母体半衰期 27-32 小时，主要代谢物 S-DCT 活性弱且不主导药效；合用 QTc 延长药物需防心律失常，转用不可逆 MAOI 需保留 14 天洗脱。",
        "mechanismSummary": "纯 S-对映体变构型高选择性 SSRI，母体同时结合 SERT 主位点与变构调节位点。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "双位点变构极高选择性抑制剂 (Ki ≈ 1.1 nM)", "high", 0.96, "母体主导"),
            target("H1 / α1 / M1 受体", "几无临床结合活性 (耐受性极佳)", "low", 0.12, "母体主导"),
        ],
    },
    "Citalopram": {
        "halfLifeHours": 35.0,
        "tMaxHours": 4.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 35.0,
        "washoutHours": 336.0,
        "washoutNote": "含消旋 R/S-西酞普兰；半衰期约 35 小时，日剂量受限于 QTc 延长上限 (40mg/日，老年人 20mg/日)，转用 MAOI 需 14 天清洗。",
        "mechanismSummary": "S-对映体强效阻滞 SERT，而 R-对映体轻微拮抗组胺 H1 受体并竞争削弱 SERT 变构效力。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "高选择性抑制剂 (主要由 S-对映体贡献)", "high", 0.92, "母体主导"),
            target("H1 受体", "弱拮抗剂 (主要由 R-对映体贡献，略增镇静)", "low", 0.35, "母体主导"),
        ],
    },
    "Paroxetine": {
        "halfLifeHours": 22.0,
        "tMaxHours": 5.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 24.0,
        "washoutHours": 336.0,
        "washoutNote": "帕罗西汀对 CYP2D6 具有非线性自抑制饱和特性（高剂量或长程服用清除明显放慢），骤停撤药反应在 SSRI 中最剧烈；且极强抑制 CYP2D6 会令曲马多/可待因失效。",
        "mechanismSummary": "强效 SERT 抑制兼明确抗胆碱能 M1 阻滞（易致镇静、体重上升及显著撤药综合征），并几乎完全失活 CYP2D6。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "现有 SSRI 中极高亲和力抑制剂 (Ki ≈ 0.13 nM)", "high", 0.98, "母体主导"),
            target("M1 / M3 毒蕈碱受体", "中等拮抗剂 (抗胆碱作用最强的 SSRI，易口干便秘)", "moderate", 0.58, "母体主导"),
            target("CYP2D6 酶 (药代动力学靶点)", "极强效自杀型抑制剂 (显著阻断多种药物活化或清除)", "high", 0.94, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "高剂量呈微弱抑制 (Ki ≈ 40-85 nM)", "low", 0.38, "母体主导"),
        ],
    },
    "Fluvoxamine": {
        "halfLifeHours": 18.0,
        "tMaxHours": 5.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 18.0,
        "washoutHours": 336.0,
        "washoutNote": "对 CYP1A2 与 CYP2C19 为极强抑制剂（严禁与阿戈美拉汀、替扎尼定、雷美替胺联用，并可大幅推高氯氮平、奥氮平、咖啡因、褪黑素血药浓度）。",
        "mechanismSummary": "强效 SSRI 与所有 SSRI 中最强 Sigma-1 受体激动剂；对多种 CYP 酶存在强抑制风险。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "强效抑制剂 (Ki ≈ 2.2 nM)", "high", 0.91, "母体主导"),
            target("σ1 (Sigma-1) 受体", "高亲和力内源激动剂 (Ki ≈ 36 nM，可能助益强迫与精神病性抑郁)", "high", 0.80, "母体主导"),
            target("CYP1A2 / CYP2C19 酶", "极强效抑制剂 (易引发氯氮平/奥氮平/咖啡因严重蓄积)", "high", 0.95, "母体主导"),
        ],
    },
    # === SNRIs, NDRI, NaSSA, SARI, Multimodal, Melatonergic ===
    "Venlafaxine": {
        "halfLife": "母体约 5 小时；主要活性代谢物去甲文拉法辛 (ODV) 约 11 小时",
        "halfLifeHours": 5.0,
        "tMaxHours": 5.5,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 11.0,
        "washoutHours": 168.0,
        "washoutNote": "母体文拉法辛半衰期仅约 5 小时，在正常 CYP2D6 人群中迅速转化为半衰期 11 小时的去甲文拉法辛 (ODV)；CYP2D6 慢代谢或强抑制剂（氟西汀/帕罗西汀/安非他酮）会令母体骤增、ODV 减少并升高副作用。停药易出现电击样撤药，换 MAOI 须间隔 7-14 天。",
        "mechanismSummary": "浓度依赖性 SNRI：低剂量主要阻滞 SERT，≥150 mg/d 时母体与 ODV 共同带来临床显著 NET 抑制。",
        "activeMetabolites": [
            met(
                "O-Desmethylvenlafaxine (去甲文拉法辛 / ODV)",
                "经 CYP2D6 O-脱甲基生成 (正常代谢者转化主力)",
                "约 11 小时",
                11.0,
                6.0,
                2.2,
                "稳态暴露量为母体的 1.5 - 3 倍（CYP2D6 正常者主力）",
                "药效活性组分主角，具有与母体近似的 SERT/NET 抑制谱但更少受 CYP 酶继续干扰",
            ),
        ],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "强效抑制剂 (全剂量范围起效)", "high", 0.90, "母体 + ODV 共主导"),
            target("NET (去甲肾上腺素转运体)", "剂量依赖性抑制 (≥150 mg/d 显效)", "moderate", 0.68, "母体 + ODV 共主导"),
            target("DAT (多巴胺转运体)", "仅超高剂量极弱抑制", "low", 0.20, "母体主导"),
        ],
    },
    "Duloxetine": {
        "halfLifeHours": 12.0,
        "tMaxHours": 6.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 12.0,
        "washoutHours": 168.0,
        "washoutNote": "母体直接主导均衡双通道抑制；经 CYP1A2/2D6 广泛代谢后均为无活性葡糖醛酸/硫酸结合物（严禁与氟伏沙明等 CYP1A2 强抑制剂合用）。转用 MAOI 须至少清洗 5-14 天。",
        "mechanismSummary": "起始即均衡覆盖 SERT 与 NET 的强效双通道抑制剂，兼具神经病理性疼痛下行抑制镇痛机制。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "极高亲和力抑制剂 (Ki ≈ 0.8 nM)", "high", 0.95, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "强效抑制剂 (Ki ≈ 7.5 nM，较早进入双通道区)", "high", 0.85, "母体主导"),
            target("DAT (多巴胺转运体)", "极微弱抑制", "low", 0.25, "母体主导"),
        ],
    },
    "Bupropion": {
        "halfLife": "母体约 14 小时；核心活性代谢物羟安非他酮约 20 - 27 小时 (苏式/赤式 33 - 37 小时)",
        "halfLifeHours": 14.0,
        "tMaxHours": 4.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 21.0,
        "washoutHours": 168.0,
        "washoutNote": "口服安非他酮经首过代谢大量转化为羟安非他酮——稳态血浆暴露量可达母体的 10 倍，才是临床抗抑郁、戒烟及 CYP2D6 强抑制的绝对主力；停药后至少 7 天仍有较高羟安非他酮残留。",
        "mechanismSummary": "非典型 NDRI（且为强效 CYP2D6 抑制剂）：体内实际上高度依赖 CYP2B6 生成极高浓度的活性产物羟安非他酮发挥主导作用。",
        "activeMetabolites": [
            met(
                "Hydroxybupropion (羟安非他酮)",
                "经 CYP2B6 催化羟基化生成",
                "20 - 27 小时 (典型 21h)",
                21.0,
                6.0,
                10.0,
                "稳态血浆暴露量可达母体的 8 - 12 倍（绝对药效主体）",
                "主导去甲肾上腺素/多巴胺再摄取抑制、烟碱受体系拮抗、惊厥阈值降低及强力 CYP2D6 抑制效应",
            ),
            met(
                "Threohydrobupropion (苏式氢安非他酮)",
                "经羰基还原酶生成",
                "33 - 40 小时 (典型 37h)",
                37.0,
                8.0,
                4.5,
                "稳态暴露量可达母体的 4 - 5 倍（药效约母体 20%）",
                "长效次要活性蓄积代谢物，进一步拉长停药拖尾",
            ),
            met(
                "Erythrohydrobupropion (赤式氢安非他酮)",
                "经羰基还原酶生成",
                "约 33 小时",
                33.0,
                8.0,
                1.2,
                "稳态暴露量接近或略高于母体",
                "弱活性辅助代谢物",
            ),
        ],
        "receptorTargets": [
            target("NET (去甲肾上腺素转运体)", "主要单胺靶点抑制 (由羟安非他酮主力维持)", "high", 0.80, "代谢物主导"),
            target("nAChR (烟碱乙酰胆碱受体)", "非竞争性拮抗剂 (α3β4/α4β2，戒烟核心机制)", "high", 0.78, "代谢物主导"),
            target("DAT (多巴胺转运体)", "中等占位抑制 (脑内约 15-26% 占有率)", "moderate", 0.65, "双相共同主导"),
            target("CYP2D6 酶 (药代动力学靶点)", "强效抑制剂 (显著升高 SSRI/TCA/利培酮/阿立哌唑浓度)", "high", 0.90, "代谢物主导"),
        ],
    },
    "Mirtazapine": {
        "halfLifeHours": 30.0,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 30.0,
        "washoutHours": 336.0,
        "washoutNote": "母体半衰期 20-40h（女性偏长）；去甲基米氮平活性仅约为母体 1/10。停药联用 MAOI 需 14 天洗脱。",
        "mechanismSummary": "去甲肾上腺素能与特异性血清素能抗抑郁剂 (NaSSA)：阻断中枢 α2 自/异体受体增进 NE 与 5-HT 释放，并封锁 5-HT2A/2C/3 及超强拮抗 H1。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("H1 组胺受体", "超强反向激动剂 (Ki ≈ 0.14 nM，低剂量即产生强效镇静与增重)", "high", 0.98, "母体主导"),
            target("5-HT2A / 5-HT2C 受体", "强效拮抗剂 (改善睡眠、减轻焦躁且无性功能障碍)", "high", 0.88, "母体主导"),
            target("α2 肾上腺素受体", "突触前自/异体受体拮抗剂 (解抑制 NE/5-HT 释放)", "high", 0.84, "母体主导"),
            target("5-HT3 受体", "强效拮抗剂 (显著止吐并减少胃肠不适)", "high", 0.82, "母体主导"),
        ],
    },
    "Trazodone": {
        "halfLifeHours": 7.0,
        "tMaxHours": 1.5,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 7.0,
        "washoutHours": 72.0,
        "washoutNote": "母体半衰期约 7 小时（日间困倦常因 α1/H1 残余），次级活性代谢物 mCPP 在 CYP2D6 慢代谢或强 CYP2D6 抑制时可异常升高，引发焦虑/失眠反跳或 5-HT 综合征叠加风险。",
        "mechanismSummary": "SARI 典型代表：低剂量 25-100mg 以母体强拮抗 5-HT2A / α1 / H1 助眠，高剂量 ≥150mg 发挥 SERT 抑制及 5-HT1A 激动抗抑郁；活性代谢物 mCPP 则偏致焦虑与 5-HT2C 激动。",
        "activeMetabolites": [
            met(
                "mCPP (间氯苯哌嗪)",
                "经 CYP3A4 脱芳基生成，并由 CYP2D6 负责清除",
                "4 - 8 小时 (典型 6h)",
                6.0,
                3.0,
                0.15,
                "正常暴露量为母体的 10% - 20% (CYP2D6 受抑时显著爬升)",
                "强效 5-HT2C/2B 激动与 5-HT 释放体，贡献偏头痛、焦虑或意外激动反应，抵消部分母体镇静效应",
            ),
        ],
        "receptorTargets": [
            target("5-HT2A 受体", "强效拮抗剂 (Ki ≈ 36 nM，母体主力助眠机制)", "high", 0.92, "母体主导"),
            target("α1 肾上腺素受体", "强效拮抗剂 (Ki ≈ 12-42 nM，易致直立性低血压与鼻塞)", "high", 0.88, "母体主导"),
            target("H1 组胺受体", "中等拮抗剂 (协同镇静)", "moderate", 0.72, "母体主导"),
            target("5-HT2C 受体", "母体拮抗 vs 代谢物 mCPP 激动 (双向平衡)", "moderate", 0.65, "双相共同主导"),
            target("SERT (5-HT 转运体)", "中等抑制剂 (高剂量 ≥150 mg 显效)", "moderate", 0.58, "母体主导"),
        ],
    },
    "Vortioxetine": {
        "halfLifeHours": 66.0,
        "tMaxHours": 9.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 66.0,
        "washoutHours": 336.0,
        "washoutNote": "母体半衰期长达约 66 小时，代谢物完全无活性；合用安非他酮/氟西汀等 CYP2D6 强抑制剂可令母体暴露量翻倍（须将伏硫西汀日剂量减半）。联用 MAOI 需 14-21 天清洗。",
        "mechanismSummary": "多模态抗抑郁剂 (Multimodal)：母体原形兼备 SERT 抑制、5-HT3/5-HT7 拮抗与 5-HT1A 激动，间接增强前额叶皮层谷氨酸/胆碱能认知通路。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("5-HT3 受体", "超强拮抗剂 (Ki ≈ 3.7 nM，但早期高占领仍易诱发恶心)", "high", 0.96, "母体主导"),
            target("SERT (5-HT 转运体)", "强效抑制剂 (Ki ≈ 1.6 nM)", "high", 0.94, "母体主导"),
            target("5-HT1A 受体", "完全激动剂 (Ki ≈ 15 nM)", "high", 0.78, "母体主导"),
            target("5-HT1B 受体", "部分激动剂 (Ki ≈ 33 nM)", "moderate", 0.68, "母体主导"),
            target("5-HT7 / 5-HT1D 受体", "拮抗剂 (改善抑郁相关认知与执行功能障碍)", "moderate", 0.62, "母体主导"),
        ],
    },
    "Agomelatine": {
        "halfLifeHours": 1.5,
        "tMaxHours": 1.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 1.5,
        "washoutHours": 24.0,
        "washoutNote": "生物利用度不足 5% 且 1.5h 极快消除；但与氟伏沙明、环丙沙星等 CYP1A2 强抑制剂合用可使暴露量飙升数十倍，属绝对禁忌；另有肝转氨酶监测硬要求。",
        "mechanismSummary": "褪黑素 MT1/MT2 激动与 5-HT2C 拮抗协同恢复昼夜节律并激活前额叶多巴胺/去甲肾上腺素释放。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("MT1 / MT2 褪黑素受体", "超强激动剂 (Ki ≈ 0.1-0.2 nM，重塑昼夜节律与睡眠时相)", "high", 0.98, "母体主导"),
            target("5-HT2C 受体", "中等选择性拮抗剂 (Ki ≈ 630 nM，解除前额叶 DA/NE 抑制)", "moderate", 0.65, "母体主导"),
        ],
    },
    # === TCAs & Irreversible MAOIs ===
    "Amitriptyline": {
        "halfLife": "母体 10 - 28 小时 (典型 21h)；活性代谢物去甲替林 18 - 44 小时 (典型 30h)",
        "halfLifeHours": 21.0,
        "tMaxHours": 3.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 30.0,
        "washoutHours": 336.0,
        "washoutNote": "经 CYP2C19 转化为强去甲肾上腺素能活性代谢物去甲替林（半衰期延长至 30h+，且易因 CYP2D6 抑制蓄积导致 QT/QRS 延长及抗胆碱毒性）；转用 MAOI 须等待 14 天。",
        "mechanismSummary": "经典双作用三环类 (TCA)：母体强抑 SERT、H1 与 M1，而体内持续生成的去甲替林明显补强 NET 抑制并主导后期消除。",
        "activeMetabolites": [
            met(
                "Nortriptyline (去甲替林)",
                "经 CYP2C19 / CYP3A4 N-脱甲基生成，由 CYP2D6 羟基化清除",
                "18 - 44 小时 (典型 30h)",
                30.0,
                7.0,
                0.9,
                "稳态浓度常达母体的 70% - 120% (慢代谢者更高)",
                "选择性倾向 NET 抑制、镇痛效能更突出，并共同决定心脏传导及抗胆碱残余风险",
            ),
        ],
        "receptorTargets": [
            target("H1 组胺受体", "超强拮抗剂 (Ki ≈ 1.1 nM，强烈嗜睡)", "high", 0.96, "母体主导"),
            target("SERT (5-HT 转运体)", "强效抑制剂 (母体远强于去甲替林)", "high", 0.88, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "强效抑制剂 (主要由活性去甲替林大幅增强)", "high", 0.86, "代谢物主导"),
            target("M1-M5 毒蕈碱受体", "强效抗胆碱能拮抗 (口干、便秘、尿潴留)", "high", 0.84, "母体主导"),
            target("α1 / Nav1.5 钠通道", "强效阻滞 (超量致死性 QRS 增宽与直立低血压核心机制)", "moderate", 0.74, "双相共同主导"),
        ],
    },
    "Clomipramine": {
        "halfLife": "母体 19 - 37 小时 (典型 32h)；核心代谢物去甲基氯米帕明高达 54 - 77 小时 (典型 65h)",
        "halfLifeHours": 32.0,
        "tMaxHours": 4.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 65.0,
        "washoutHours": 336.0,
        "washoutNote": "母体为强效 SERT 抑制剂（抗强迫核心），但其活性代谢物去甲基氯米帕明半衰期长达 54-77h（稳态浓度约为母体 2 倍），药理转变为超强 NET 抑制且严禁近 MAOI 给药（清洗 ≥14 天）。",
        "mechanismSummary": "临床最强强迫症三环药之一：母体主攻 SERT（抗强迫），长效代谢物去甲基氯米帕明极强抑制 NET 并控制整体消除窗口。",
        "activeMetabolites": [
            met(
                "Desmethylclomipramine (去甲基氯米帕明)",
                "经 CYP2C19 / CYP1A2 / CYP3A4 N-脱甲基生成",
                "54 - 77 小时 (典型 65h)",
                65.0,
                12.0,
                2.2,
                "稳态暴露量通常为母体的 150% - 300%",
                "极强 NET 抑制剂，重塑稳态期交感兴奋与抗抑郁协同效应并使清除大幅延迟",
            ),
        ],
        "receptorTargets": [
            target("SERT (5-HT 转运体)", "极高亲和力抑制剂 (Ki ≈ 0.28 nM，抗强迫核心)", "high", 0.98, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "极强效抑制剂 (稳态下主要由去甲基代谢物主导)", "high", 0.95, "代谢物主导"),
            target("H1 / α1 / M1 受体", "中等至强效阻滞 (嗜睡、出汗、排尿困难与惊厥风险)", "moderate", 0.72, "双相共同主导"),
        ],
    },
    "Doxepin": {
        "halfLife": "母体 15 - 19 小时 (典型 17h)；去甲基多塞平约 31 小时",
        "halfLifeHours": 17.0,
        "tMaxHours": 2.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 31.0,
        "washoutHours": 336.0,
        "washoutNote": "极低剂量 (3-6mg) 用作选择性 H1 助眠，高剂量具备典型 TCA 心脏钠通道及 5-HT/NE 抑制；活性代谢物去甲基多塞平半衰期延长至约 31 小时，转 MAOI 需 14 天洗脱。",
        "mechanismSummary": "现有精神药物中最强效 H1 拮抗剂之一（低剂量助眠），中高剂量兼具 SERT/NET 抑制与明显去甲基长效代谢衔接。",
        "activeMetabolites": [
            met(
                "Nordoxepin (去甲基多塞平)",
                "经 CYP2C19 / CYP2D6 N-脱甲基",
                "28 - 34 小时 (典型 31h)",
                31.0,
                8.0,
                1.1,
                "稳态暴露量接近或略高于母体",
                "维持后续抗抑郁 SERT/NET 活性并主要负责停药后数日内延迟镇静及 TCA 相互作用窗口",
            ),
        ],
        "receptorTargets": [
            target("H1 组胺受体", "极高亲和力反向激动剂 (Ki ≈ 0.24 nM，强效助眠)", "high", 0.99, "母体主导"),
            target("SERT / NET 转运体", "中等抑制剂 (抗抑郁量级 ≥75-150mg/d)", "moderate", 0.65, "双相共同主导"),
            target("M1 / α1 受体", "强效拮抗 (高剂量抗胆碱与直立低血压)", "moderate", 0.72, "双相共同主导"),
        ],
    },
    "Tranylcypromine": {
        "halfLife": "血浆母体仅 1.5 - 3.5 小时 (典型 2.5h)；但因不可逆共价失活 MAO-A/B 酶蛋白，停药恢复新酶需 10 - 14 天",
        "halfLifeHours": 2.5,
        "tMaxHours": 1.5,
        "pkRole": "IRREVERSIBLE_PD",
        "effectiveHalfLifeHours": 2.5,
        "washoutHours": 336.0,
        "washoutNote": "经典不可逆药效动力学与血浆半衰期背离案例！母体 12 小时内几乎从血浆排空，但脑内及肠道 MAO-A/B 酶已被环丙环结构共价永久灭活，必须等待人体从头合成新酶蛋白（整整 14 天 / 336 小时）。在此 14 天内联用 SSRI/SNRI/MDMA/曲马多/高酪胺饮食仍可致命。",
        "mechanismSummary": "不可逆非选择性 MAO-A/MAO-B 抑制剂（母体快速清除但药效持续 14 天，兼轻微儿茶酚胺释放与 CYP2A6 自抑制）。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("MAO-A / MAO-B 酶", "不可逆共价结合失活 (恢复期 10-14 天，洗脱期核心限制)", "high", 0.99, "药效动力学不可逆主导"),
            target("NET / DAT (高剂量)", "轻至中度单胺释放与再摄取阻滞 (部分唤醒激发感)", "moderate", 0.55, "母体主导"),
        ],
    },
    "L-Deprenyl, N-Propargyl-L-methamphetamine": {
        "halfLife": "母体血浆仅 1.5 - 2.5 小时 (典型 2h)；脑内不可逆 MAO-B/A 酶蛋白周转恢复需 14 天 (代谢产生 L-手性苯丙胺族代谢物)",
        "halfLifeHours": 2.0,
        "tMaxHours": 0.75,
        "pkRole": "IRREVERSIBLE_PD",
        "effectiveHalfLifeHours": 2.0,
        "washoutHours": 336.0,
        "washoutNote": "兼具两大陷阱：(1) 不可逆炔丙基共价结合导致 MAO 活性恢复长达 14 天（336h），高剂量（口服 >10mg/d 或经皮贴剂）同时不可逆抑制 MAO-A，需按全套 14 天洗脱规范严防血清素综合征；(2) 口服首过代谢生成 L-甲基苯丙胺与 L-苯丙胺（可致尿检安非他明阳性及轻度交感兴奋，经皮贴剂/口腔崩解片大幅减少该代谢物）。",
        "mechanismSummary": "低剂量口服选择性、高剂量或贴剂非选择性的不可逆 MAO-B/A 抑制剂，伴 L-手性苯丙胺代谢支路与长达两周的酶合成期。",
        "activeMetabolites": [
            met(
                "L-Desmethylselegiline (L-去甲基司来吉兰)",
                "经 CYP2B6 / CYP2C19 首过脱炔丙基生成",
                "约 2 - 4 小时",
                2.5,
                1.0,
                0.7,
                "口服后稳态暴露量接近或超过母体",
                "同样保留一定不可逆 MAO-B 抑制活性",
            ),
            met(
                "L-Methamphetamine & L-Amphetamine (L-去氧麻黄碱/左旋苯丙胺)",
                "经 CYP2B6 / CYP3A4 脱甲基/脱炔丙基代谢（主要见于普通口服吞服）",
                "10 - 15 小时 (典型 12h)",
                12.0,
                3.0,
                1.5,
                "普通口服后尿液检出量高（L-异构体中枢致欣快弱但外周交感明确）",
                "几乎不产生 D-苯丙胺样强欣快，但贡献轻微外周拟交感升压与抗嗜睡（经皮 Emsam 贴剂可旁路规避）",
            ),
        ],
        "receptorTargets": [
            target("MAO-B 酶", "不可逆共价抑制 (低剂量即 >90% 脑内灭活，增强内源 DA)", "high", 0.99, "药效动力学不可逆主导"),
            target("MAO-A 酶", "高剂量 (>10mg 口服或高贴剂) 不可逆灭活 (失去底物选择性，诱发 5-HT 危机)", "high", 0.85, "药效动力学不可逆主导"),
            target("α/β 肾上腺素通路 (经 L-代谢物)", "轻度外周拟交感激动 (可能扰动睡眠与血压)", "low", 0.35, "代谢物主导"),
        ],
    },
    # === Antipsychotics ===
    "Risperidone": {
        "halfLife": "母体原形仅约 3 - 4 小时；主力活性代谢物帕利哌酮 (9-OH-利培酮) 约 21 - 24 小时 (典型 22h)",
        "halfLifeHours": 3.5,
        "tMaxHours": 1.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 22.0,
        "washoutHours": 144.0,
        "washoutNote": "正常代谢者口服利培酮后 3-4h 母体即迅速通过 CYP2D6 转化为几乎等活、半衰期 22h 的 9-羟利培酮（即帕利哌酮），故实际总活性组分靠 9-OH-利培酮维持每日一次给药；但 CYP2D6 慢代谢者（或联用氟西汀/帕罗西汀/安非他酮）母体半衰期可暴增至 20h，并诱发更强直立性低血压及催乳素飙升。",
        "mechanismSummary": "高亲和力 SDA（5-HT2A / D2 拮抗）：临床绝大部分时间由活性代谢物 9-羟利培酮维持 D2/5-HT2A 占领，共同构成“总活性组分”。",
        "activeMetabolites": [
            met(
                "9-OH-Risperidone (帕利哌酮 / 9-羟利培酮)",
                "经 CYP2D6 (主要) 及 CYP3A4 9-羟基化生成",
                "21 - 24 小时 (肾衰显著延长至 50h+)",
                22.0,
                4.0,
                4.0,
                "CYP2D6 正常代谢者稳态暴露量为母体的 3 - 5 倍 (占总活性组分 75%-85%)",
                "几乎与利培酮母体等强阻滞 D2 与 5-HT2A 受体（且直接制成帕利哌酮原药上市），主导日常稳定疗效与高泌乳素血症",
            ),
        ],
        "receptorTargets": [
            target("5-HT2A 受体", "极高亲和力拮抗剂 (Ki ≈ 0.17 nM，远强于 D2)", "high", 0.98, "双相共同主导"),
            target("D2 受体", "高亲和力强效拮抗剂 (Ki ≈ 1.1-3.1 nM，>65% 占领起效，>80% 易 EPS/高泌乳素)", "high", 0.94, "代谢物主导"),
            target("α1 / α2 肾上腺素受体", "强效拮抗 (显著首剂直立性低血压，需慢速滴定)", "high", 0.82, "母体主导"),
            target("H1 组胺受体", "中等拮抗 (轻中度镇静与体重增加)", "moderate", 0.62, "双相共同主导"),
        ],
    },
    "Paliperidone": {
        "halfLifeHours": 23.0,
        "tMaxHours": 24.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 23.0,
        "washoutHours": 144.0,
        "washoutNote": "口服 OROS 渗透泵剂型几乎不经肝脏 CYP 酶代谢（~60% 原形直接经肾排泄，肝损友好但中重度肾损须大幅减量）；注意长效肌肉注射剂（Invega Sustenna/Trinza）采用脂质纳米晶缓释库，呈极长翻转 (flip-flop) 消除（半衰期 25-139 天，停针后清洗期可达数月）。",
        "mechanismSummary": "即利培酮的直接活性代谢产物 (9-羟利培酮)：绕开 CYP2D6 代谢变异与大量肝酶药物相互作用，以 23h 母体平稳占领 D2/5-HT2A。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("5-HT2A 受体", "极强拮抗剂 (Ki ≈ 0.25 nM)", "high", 0.96, "母体主导"),
            target("D2 受体", "强效拮抗剂 (Ki ≈ 2.8 nM，稳态波动更平稳，高剂量仍致高泌乳素与 EPS)", "high", 0.92, "母体主导"),
            target("α1 / α2 受体", "中至强拮抗 (较母体利培酮直立低血压略轻)", "moderate", 0.72, "母体主导"),
            target("H1 受体", "中等拮抗", "low", 0.50, "母体主导"),
        ],
    },
    "Aripiprazole": {
        "halfLife": "母体约 75 小时 (CYP2D6 慢代谢可达 146h)；核心活性代谢物脱氢阿立哌唑约 94 小时",
        "halfLifeHours": 75.0,
        "tMaxHours": 4.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 94.0,
        "washoutHours": 470.0,
        "washoutNote": "母体 75h + 等活代谢物脱氢阿立哌唑 94h，构成将近 20 天（~470h）的超长生理清洗期！加量或停药需满 2 周后才真正显现全部稳态变化，联用强 CYP2D6/3A4 抑制剂（氟西汀/帕罗西汀/酮康唑）常规需减半剂量。",
        "mechanismSummary": "第三代多巴胺系统稳定剂 (D2/D3 高亲和部分激动，内源活性 ~25-30%)，母体与长半衰期脱氢阿立哌唑联手持久稳定突触传导。",
        "activeMetabolites": [
            met(
                "Dehydroaripiprazole (脱氢阿立哌唑)",
                "经 CYP3A4 及 CYP2D6 脱氢代谢生成",
                "约 94 小时 (近 4 天)",
                94.0,
                24.0,
                0.40,
                "稳态血浆暴露量约为母体的 35% - 45%",
                "具有与母体几乎等价的 D2/D3 部分激动与 5-HT2A 拮抗活性，进一步延长稳态平台并拉长停药清洗",
            ),
        ],
        "receptorTargets": [
            target("D2 受体", "高亲和低内源活性部分激动剂 (Ki ≈ 0.34 nM，稳态占据率可 >85% 却极少 EPS)", "high", 0.98, "母体主导"),
            target("D3 受体", "强部分激动剂 (Ki ≈ 0.8 nM)", "high", 0.88, "母体主导"),
            target("5-HT1A 受体", "强部分激动剂 (Ki ≈ 1.7 nM，助抗抑郁与抗焦虑)", "high", 0.84, "母体主导"),
            target("5-HT2A / 5-HT2B 受体", "高亲和力拮抗/反向激动剂", "high", 0.82, "双相共同主导"),
            target("α1 / H1 受体", "弱至中等拮抗 (轻度静坐不能常见，但低体重增长)", "low", 0.48, "母体主导"),
        ],
    },
    "Quetiapine": {
        "halfLife": "母体约 6 - 7 小时；核心活性代谢物去烷基喹硫平 (N-去烷基喹硫平) 约 12 小时",
        "halfLifeHours": 7.0,
        "tMaxHours": 1.5,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 12.0,
        "washoutHours": 72.0,
        "washoutNote": "母体迅速以瞬时“快解离 (fast-off)”机制结合 D2 与极强拮抗 H1（低剂量即高效催眠）；其核心代谢物去烷基喹硫平半衰期延长至 12h 并负责强力 NET 抑制与 5-HT1A 激动——这正是喹硫平兼有独立抗抑郁及双相抑郁疗效的关键。",
        "mechanismSummary": "三阶段剂量切换的非典型药物，依赖母体镇静/抗精神病快解离与活性代谢物去烷基喹硫平的抗抑郁机制互补。",
        "activeMetabolites": [
            met(
                "Norquetiapine (去烷基喹硫平)",
                "经 CYP3A4 N-脱烷基生成，并由 CYP2D6 羟基化",
                "约 11 - 12 小时",
                12.0,
                4.0,
                0.60,
                "稳态暴露量约为母体的 50% - 75%",
                "赋予母体所缺乏的强效 NET 去甲肾上腺素抑制 (IC50 ≈ 12 nM) 与更高 5-HT1A 部分激动及 M1 抗胆碱贡献，主导抗双相/单相抑郁疗效",
            ),
        ],
        "receptorTargets": [
            target("H1 组胺受体", "极强拮抗剂 (Ki ≈ 2.2-11 nM，25-100mg 即主导深睡镇静与代谢増重)", "high", 0.96, "母体主导"),
            target("α1 肾上腺素受体", "强效拮抗 (致明显首剂直立性低血压与鼻塞)", "high", 0.86, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "强效抑制 (仅来自代谢物去烷基喹硫平)", "high", 0.82, "代谢物主导"),
            target("5-HT2A / 5-HT1A 受体", "5-HT2A 拮抗 + 5-HT1A 部分激动 (协同稳定前额叶多巴胺)", "moderate", 0.72, "双相共同主导"),
            target("D2 受体", "高解离率低亲和拮抗 (仅高剂量 400-800mg 才达抗精神病所需占据率)", "moderate", 0.56, "母体主导"),
        ],
    },
    "Olanzapine": {
        "halfLifeHours": 33.0,
        "tMaxHours": 6.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 33.0,
        "washoutHours": 168.0,
        "washoutNote": "母体原形半衰期 30-38 小时（不吸烟与女性消除更慢，吸烟经多环芳烃强诱导 CYP1A2 可令血药浓度暴降 30%-45%！戒烟期需监测过量中毒）；代谢产物均无中枢活性。",
        "mechanismSummary": "经典“马丁鸡尾酒”多靶点 MARTA 类药物，由母体原形强力阻滞 5-HT2A/2C、D2、H1 与毒蕈碱 M 受体协同增效抗躁狂/难治抑郁。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("H1 组胺受体", "极高亲和力拮抗剂 (Ki ≈ 2.0 nM，主导嗜睡与显著体重增加)", "high", 0.96, "母体主导"),
            target("5-HT2A / 5-HT2C 受体", "极强拮抗剂 (5-HT2C + H1 双阻滞共促胰岛素抵抗与食欲亢进)", "high", 0.92, "母体主导"),
            target("D2 / D4 受体", "强效拮抗剂 (Ki ≈ 11-31 nM，抗躁狂与抗幻觉主力)", "high", 0.86, "母体主导"),
            target("M1-M5 毒蕈碱受体", "强效抗胆碱拮抗 (降低 EPS 发生率，但引起口干便秘及胰岛β细胞 M3 代谢干扰)", "high", 0.80, "母体主导"),
        ],
    },
    "Clozapine": {
        "halfLife": "母体约 12 - 14 小时；主要活性代谢物去甲氯氮平约 16 小时",
        "halfLifeHours": 12.0,
        "tMaxHours": 2.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 16.0,
        "washoutHours": 96.0,
        "washoutNote": "经 CYP1A2 代谢对吸烟（诱导）与氟伏沙明/环丙沙星（超强抑制，可使血浓暴涨 5-10 倍并触发惊厥/中毒性心肌炎！）极度敏感；停药骤停易触发剧烈胆碱反跳性精神病恶化。",
        "mechanismSummary": "终极难治性精神分裂症金标准：母体极快解离阻滞 D2/D4 并强阻 5-HT2A/α1/H1，而长半衰期去甲氯氮平 (NDMC) 反而具部分 M1 激动效应引发流涎并关联部分代谢毒性。",
        "activeMetabolites": [
            met(
                "Norclozapine (N-去甲基氯氮平 / NDMC)",
                "主要经 CYP1A2 与 CYP3A4 N-脱甲基生成",
                "14 - 20 小时 (典型 16h)",
                16.0,
                5.0,
                0.65,
                "稳态血浆比值常在母体的 50% - 80%",
                "毒蕈碱 M1 部分激动（导致流涎并可能贡献部分认知获益），同时其偏高比值常提示代谢异常与中性粒细胞毒性风险",
            ),
        ],
        "receptorTargets": [
            target("H1 / 5-HT2A / α1 受体", "超强拮抗 (高嗜睡、流涎与严重直立低血压需严格滴定)", "high", 0.96, "母体主导"),
            target("M1 / M3 / M4 毒蕈碱受体", "母体拮抗 vs 去甲氯氮平 M1/M4 激动 (混合双向致反常性大量流涎与重度便秘)", "high", 0.88, "双相共同主导"),
            target("D4 / 5-HT2C / 5-HT6 受体", "强效阻滞", "high", 0.82, "母体主导"),
            target("D2 受体", "低占领快解离拮抗 (仅 ~40%-60% 占领，几乎无 EPS 或高泌乳素)", "moderate", 0.58, "母体主导"),
        ],
    },
    "Amisulpride": {
        "halfLifeHours": 12.0,
        "tMaxHours": 2.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 12.0,
        "washoutHours": 72.0,
        "washoutNote": "极少经过肝脏代谢（几乎完全不涉及 CYP 酶系，绝大部分原形直接由肾脏排泄），肾功能不全者必须大幅减量；低剂量促进前额叶多巴胺，高剂量强阻突触后 D2 易显著抬高泌乳素并有 QT 风险。",
        "mechanismSummary": "超高选择性苯甲酰胺类 D2/D3 拮抗剂兼 5-HT7 拮抗，高剂量阻滞中脑边缘 D2、低剂量 (50-300mg) 阻滞突触前 D2/D3 自受体反促释放改善阴性症状与恶劣心境。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("D2 / D3 受体", "纯粹极高选择性拮抗剂 (低剂量阻前自受体促释放，高剂量阻后受体；无 H1/M1/α1 脱靶)", "high", 0.98, "母体主导"),
            target("5-HT7 受体", "强效拮抗剂 (Ki ≈ 11.5 nM，直接贡献显著抗抑郁效应)", "high", 0.82, "母体主导"),
        ],
    },
    "Lurasidone": {
        "halfLifeHours": 18.0,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 18.0,
        "washoutHours": 120.0,
        "washoutNote": "母体主要经 CYP3A4 代谢（严禁与强效 CYP3A4 抑制剂酮康唑或强诱导剂卡马西平合用，且空腹生物利用度骤减一半以上——必须随至少 350 大卡食物整片服用！）。",
        "mechanismSummary": "几乎无 H1 与 M1 亲和力（极低镇静与极少体重/血糖风险）的 D2 / 5-HT2A / 5-HT7 / 5-HT1A 多靶点稳定剂，为双相抑郁首选药物之一。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("5-HT7 受体", "极高亲和力拮抗剂 (Ki ≈ 0.49 nM，助双相抗抑郁与认知调节)", "high", 0.96, "母体主导"),
            target("D2 受体", "高亲和力拮抗剂 (Ki ≈ 1.0 nM)", "high", 0.94, "母体主导"),
            target("5-HT2A 受体", "极高亲和力拮抗剂 (Ki ≈ 0.47 nM)", "high", 0.94, "母体主导"),
            target("5-HT1A 受体", "强效部分激动剂 (Ki ≈ 6.4 nM)", "high", 0.80, "母体主导"),
            target("H1 / M1 受体", "基本无结合 (代谢副作用极小)", "low", 0.12, "母体主导"),
        ],
    },
    "Ziprasidone": {
        "halfLifeHours": 6.6,
        "tMaxHours": 7.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 6.6,
        "washoutHours": 48.0,
        "washoutNote": "消除相对较快（半衰期仅约 6.6h，需严格每日两次随 ≥500 kcal 餐食同服以维持 60% 吸收率）；具中等程度 QTc 延长效应，严禁与胺碘酮/索他洛尔/美沙酮/匹莫齐特等 QT 延长药联用。",
        "mechanismSummary": "兼有高 5-HT2A/D2 比值、5-HT1A 部分激动与中等 SERT/NET 再摄取阻滞的代谢友好型非典型抗精神病药。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("5-HT2A / D2 受体", "超强拮抗 (极低 EPS 与低体重增长倾向)", "high", 0.95, "母体主导"),
            target("5-HT1A / 5-HT1D 受体", "5-HT1A 部分激动 + 5-HT1D/2C 拮抗", "high", 0.82, "母体主导"),
            target("SERT / NET 转运体", "中等抑制活性 (类似文拉法辛的辅助单胺阻滞)", "moderate", 0.58, "母体主导"),
            target("hERG 钾通道 (心脏)", "中等阻滞 (致 QTc 间期显著延长，严防与高危药物叠用)", "moderate", 0.68, "母体主导"),
        ],
    },
    "Haloperidol": {
        "halfLifeHours": 20.0,
        "tMaxHours": 4.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 20.0,
        "washoutHours": 144.0,
        "washoutNote": "口服半衰期约 20 小时（经还原型氟哌啶醇可逆氧化回收，实际组织消除偏长）；强力阻滞 D2 易诱发急性肌张力障碍、静坐不能及高泌乳素血症，静脉注射有严重尖端扭转型室速 (TdP) 风险。",
        "mechanismSummary": "第一代强效丁酰苯类典型抗精神病药，母体极强选择性阻滞 D2/D3 受体并强力绑定 Sigma-1。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("D2 / D3 受体", "超强纯拮抗剂 (Ki ≈ 0.4-1.5 nM，强抗妄想激越与高 EPS 风险)", "high", 0.99, "母体主导"),
            target("σ1 (Sigma-1) 受体", "超强拮抗/高亲和绑定 (Ki ≈ 3 nM)", "high", 0.88, "母体主导"),
            target("α1 肾上腺素受体", "中等拮抗 (轻中度直立血压变化)", "moderate", 0.62, "母体主导"),
        ],
    },
    "Chlorpromazine": {
        "halfLife": "母体与活性 7-羟氯丙嗪半衰期约 30 小时 (消除波动跨度大 16 - 40 小时)",
        "halfLifeHours": 30.0,
        "tMaxHours": 3.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 30.0,
        "washoutHours": 168.0,
        "washoutNote": "广谱酚噻嗪类第一代老药，代谢产生数十种产物，其中 7-羟氯丙嗪维持部分中枢抗多巴胺效应；极强阻滞 α1 与 H1，导致严重嗜睡、低血压、光敏性皮炎及癫痫阈值降低。",
        "mechanismSummary": "最早期酚噻嗪类低效价典型抗精神病药，脱靶涵盖 α1、H1、M1 与多巴胺系统。",
        "activeMetabolites": [
            met(
                "7-Hydroxychlorpromazine (7-羟氯丙嗪)",
                "经 CYP2D6 / CYP1A2 芳香环 7-羟基化生成",
                "约 20 - 35 小时 (典型 30h)",
                30.0,
                6.0,
                0.35,
                "稳态暴露量约为母体的 25% - 45%",
                "保持接近母体的 D2 与 α1 拮抗活性，贡献持久抗精神病及镇静作用",
            ),
        ],
        "receptorTargets": [
            target("α1 肾上腺素受体", "极强拮抗剂 (显著体位性低血压与头晕)", "high", 0.95, "母体主导"),
            target("H1 组胺受体", "极强拮抗剂 (显著深度镇静与强止吐)", "high", 0.92, "母体主导"),
            target("D2 / 5-HT2A 受体", "高亲和力阻滞", "high", 0.85, "双相共同主导"),
            target("M1-M4 毒蕈碱受体", "中等至强抗胆碱拮抗", "moderate", 0.72, "母体主导"),
        ],
    },
    # === Benzodiazepines & Z-Drugs ===
    "Diazepam": {
        "halfLife": "母体 20 - 50 小时 (典型 40h)；长效接力代谢物去甲地西泮高达 36 - 200 小时 (典型 100h)",
        "halfLifeHours": 40.0,
        "tMaxHours": 1.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 100.0,
        "washoutHours": 500.0,
        "washoutNote": "地西泮拥有经典的四级长链代谢接力：母体虽然快速入脑起效（脂溶性高），但经 CYP2C19/3A4 生成半衰期高达 100 小时（老年人及肝病可达 200h）的活性代谢物去甲地西泮，随后再经替马西泮→奥沙西泮清除。连续服用数天即深度蓄积，停药清洗及与酒精/阿片类的致死性呼吸抑制警戒期长逾 2-3 周！",
        "mechanismSummary": "高脂溶速效但超长蓄积苯二氮䓬：母体提供 15-30 分钟超快起效与抗惊厥，后续由长达 100h 的去甲地西泮与终端替马西泮/奥沙西泮无缝维持持久 GABA-A 正向变构效应。",
        "activeMetabolites": [
            met(
                "Nordiazepam (去甲地西泮)",
                "经 CYP2C19 与 CYP3A4 N-去甲基生成 (主体长效主力)",
                "36 - 200 小时 (典型 100h)",
                100.0,
                24.0,
                1.8,
                "连续给药稳态浓度可达母体的 150% - 250%",
                "几乎保留全部抗焦虑及 GABA-A PAM 效力，是地西泮长期连续给药时引发蓄积嗜睡、认知迟钝及数周级呼吸抑制残余的绝对元凶",
            ),
            met(
                "Temazepam (替马西泮)",
                "母体经 CYP3A4 3-羟基化生成",
                "8 - 15 小时 (典型 11h)",
                11.0,
                4.0,
                0.25,
                "暴露量约为母体的 20% - 30%",
                "高效 GABAA 正向变构助眠中间产物",
            ),
            met(
                "Oxazepam (奥沙西泮)",
                "去甲地西泮羟基化与替马西泮脱甲基终端汇合产物",
                "6 - 10 小时 (典型 8h)",
                8.0,
                6.0,
                0.20,
                "约为母体暴露量的 15% - 25%，经 UGT 直接排出",
                "四级级联末段活性体，完成最终葡糖醛酸化肾脏消除",
            ),
        ],
        "receptorTargets": [
            target("GABA-A 受体 (α1/α2/α3/α5 BZD 位点)", "全亚型正向变构调节剂 (抗焦虑、肌松、镇静、抗癫痫持续状态)", "high", 0.96, "双相共同主导"),
            target("TSPO (外周苯二氮䓬受体)", "中等结合 (促神经甾体合成辅助调节)", "low", 0.35, "母体主导"),
        ],
    },
    "Chlordiazepoxide": {
        "brandName": "利眠宁 (Librium) | 罗氏 (Roche)",
        "halfLife": "母体 5 - 30 小时 (典型 15h)；经去甲氧氯氮䓬 (35h) 最终汇入超长效去甲地西泮 (100h)",
        "halfLifeHours": 15.0,
        "tMax": "1.0 - 4.0 小时",
        "tMaxHours": 2.0,
        "bioavailability": "口服 80% - 100% (肌肉注射吸收极慢且不稳定)",
        "cypEnzymes": [
            "CYP3A4 / CYP2C19 (脱氨基、氧化及级联代谢为长效活性产物)",
        ],
        "clearance": "全肝脏连续生成去甲氯氮䓬 → 去甲氧氯氮䓬 → 去甲地西泮 → 奥沙西泮，酒精性肝硬化患者半衰期极度延长",
        "indications": [
            "急性酒精戒断综合征一线防震颤谵妄药",
            "重度焦虑障碍",
        ],
        "contraindications": [
            "重度呼吸功能不全、睡眠呼吸暂停综合征",
            "重症肌无力、失代偿期重度肝衰竭",
        ],
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 100.0,
        "washoutHours": 500.0,
        "washoutNote": "利眠宁母体半衰期看似只有十余小时，实则通过级联反应产生去甲氧氯氮䓬 (~35h) 并汇入长达 100 小时的去甲地西泮！用于酒精戒断或连服极易发生数日后渐进加深的中枢抑制。",
        "mechanismSummary": "第一代经典超长效苯二氮䓬前药型接力体系：自身半衰期虽中等，但下游连续接力 3 级活性代谢产物直至去甲地西泮与奥沙西泮。",
        "activeMetabolites": [
            met(
                "Demoxepam (去甲氧氯氮䓬)",
                "经氧化脱氨基由中间体脱甲基氯氮䓬转化",
                "14 - 95 小时 (典型 35h)",
                35.0,
                12.0,
                0.8,
                "中等稳态累计（约母体 60% - 100%）",
                "保留全部抗焦虑及 GABA-A 正向调节活性，并向下继续供应去甲地西泮",
            ),
            met(
                "Nordiazepam (去甲地西泮)",
                "由去甲氧氯氮䓬进一步脱氧生成",
                "36 - 200 小时 (典型 100h)",
                100.0,
                28.0,
                1.5,
                "稳态浓度可达母体的 120% - 200%",
                "真正控制戒断防惊厥平缓递减与数周级消除窗口的核心产物",
            ),
            met(
                "Oxazepam (奥沙西泮)",
                "终端羟基化活性产物",
                "约 8 小时",
                8.0,
                8.0,
                0.20,
                "终末消除占比 ~20%",
                "末尾环节经 UGT 结合后经肾安全排出",
            ),
        ],
        "receptorTargets": [
            target("GABA-A 受体 (BZD 变构位点)", "强效 PAM 镇静抗惊厥调节剂", "high", 0.94, "代谢物主导"),
        ],
    },
    "Clonazepam": {
        "halfLifeHours": 35.0,
        "tMaxHours": 2.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 35.0,
        "washoutHours": 180.0,
        "washoutNote": "虽不产生长效活性代谢物（经硝基还原与 NAT2 乙酰化直接灭活），但其自身 30-40h 长半衰期及极强效价仍导致显著次日蓄积、运动失调及长达 7 天以上的呼吸抑制交叉窗口。",
        "mechanismSummary": "高效价、高半衰期苯二氮䓬，由母体原形主导强效抗癫痫与抗惊恐作用。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GABA-A 受体 (α1/α2/α3/α5 BZD 位点)", "超高效价正向变构调节剂 (0.5mg 约等效 10mg 地西泮)", "high", 0.98, "母体主导"),
        ],
    },
    "Alprazolam": {
        "halfLifeHours": 11.2,
        "tMaxHours": 1.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 11.2,
        "washoutHours": 72.0,
        "washoutNote": "母体半衰期约 11 小时但临床作用仅维持 4-6h（高脂溶快速再分布出脑导致极快剂间反跳焦虑与高戒断风险）；α-羟基产物临床浓低于母体 10%，无主导接力。严禁联用阿片类或乙醇。",
        "mechanismSummary": "三唑并苯二氮䓬类高效速效抗惊恐药物，完全依靠母体迅速入脑与快速再分布动力学决定临床特点。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GABA-A 受体 (α1/α2/α3 BZD 位点)", "超快起效高效价 PAM (抗惊恐显著但骤停易发癫痫)", "high", 0.97, "母体主导"),
        ],
    },
    "Lorazepam": {
        "halfLifeHours": 14.0,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 14.0,
        "washoutHours": 84.0,
        "washoutNote": "完全绕开细胞色素 P450 氧化途径，直接经肝脏 UGT2B15 葡糖醛酸化生成完全无活性的结合物经肾排泄——因而对肝功能损害及 CYP 抑制药物极少敏感（LOT 原则优选药之一）。",
        "mechanismSummary": "经典无 CYP 氧化步骤的直接 UGT 代谢中效苯二氮䓬，母体原形主导全周期。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GABA-A 受体 (BZD 变构位点)", "高效价 PAM (中枢遗忘与抗紧张/肌松均明显)", "high", 0.95, "母体主导"),
        ],
    },
    "Oxazepam": {
        "halfLifeHours": 8.0,
        "tMaxHours": 3.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 8.0,
        "washoutHours": 48.0,
        "washoutNote": "已处于地西泮四级代谢联的最末端！自身直接经 UGT 结合后经肾排除，完全无活性下游产物，对老年及肝硬化患者蓄积风险最低，吸收较慢故较少瞬时成瘾冲击。",
        "mechanismSummary": "苯二氮䓬代谢终点产物自身：无活性次级代谢物，母体约 8 小时平稳消除。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GABA-A 受体 (BZD 变构位点)", "中效温和 PAM (缓和抗焦虑，减少老年跌倒蓄积)", "high", 0.90, "母体主导"),
        ],
    },
    "Midazolam": {
        "halfLife": "母体 1.5 - 2.5 小时；活性代谢物 1-羟咪达唑仑约 1 - 2 小时",
        "halfLifeHours": 2.0,
        "tMaxHours": 0.75,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 2.5,
        "washoutHours": 24.0,
        "washoutNote": "经 CYP3A4 超快清除，若与利托那韦、伊曲康唑、红霉素或大量葡萄柚汁等强效 CYP3A4 抑制剂合用，口服生物利用度可骤增 3-10 倍致重度昏迷；另肾衰重症患者体内 1-羟咪达唑仑葡糖苷酸也会异常蓄积导致持续镇静。",
        "mechanismSummary": "超短效水溶→脂溶构象切换苯二氮䓬，母体与高活性 1-羟咪达唑仑共用快速起效与快速醒转特征。",
        "activeMetabolites": [
            met(
                "1-Hydroxymidazolam (α-羟咪达唑仑)",
                "经 CYP3A4 快速首过与全身 1'-羟基化",
                "1.0 - 2.0 小时 (典型 1.5h)",
                1.5,
                0.75,
                0.45,
                "口服后稳态暴露量可达母体约 40% - 50% (静脉约 15%)",
                "保持母体 60% - 80% 镇静效能，肾衰时其结合物蓄积可令持续呼吸抑制长达数日",
            ),
        ],
        "receptorTargets": [
            target("GABA-A 受体 (α1/α2 BZD 位点)", "超快强效 PAM (主导顺行性遗忘与麻醉前镇静)", "high", 0.98, "双相共同主导"),
        ],
    },
    "Zolpidem": {
        "halfLifeHours": 2.4,
        "tMaxHours": 1.6,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 2.4,
        "washoutHours": 24.0,
        "washoutNote": "非苯二氮䓬 Z-Drug，选择性绑定 α1 亚基，代谢物全部无活性；女性清除普遍低于男性约 30%-40%，次晨残余嗜睡与梦游/异常无记忆驾驶行为与血药残余高度相关。",
        "mechanismSummary": "α1 亚型高选择性 GABA-A 调节剂：母体短效速入脑助眠，几乎无肌松或强抗焦虑成分。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GABA-A α1 亚基 (ω1 位点)", "高选择性激动 PAM (促睡效果强，显著大于 α2/3/5)", "high", 0.96, "母体主导"),
            target("GABA-A α2 / α3 / α5 亚基", "极低亲和力 (几无抗焦虑及肌松效力)", "low", 0.25, "母体主导"),
        ],
    },
    "Zopiclone": {
        "halfLife": "母体约 5 小时；部分活性去甲基佐匹克隆约 7 小时",
        "halfLifeHours": 5.0,
        "tMaxHours": 1.8,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 5.5,
        "washoutHours": 36.0,
        "washoutNote": "消旋制剂 (含左旋与右旋体)，代谢生成的 N-去甲基佐匹克隆具有微弱抗焦虑效能并轻度延长总镇静拖尾，常见胆碱味觉异常（口中金属苦味）。",
        "mechanismSummary": "环吡咯酮类非苯二氮䓬催眠药，母体与弱活性去甲基代谢物联同维持整夜睡眠结构。",
        "activeMetabolites": [
            met(
                "N-Desmethylzopiclone (N-去甲基佐匹克隆)",
                "经 CYP3A4 / CYP2C8 氧化脱甲基",
                "约 7 小时",
                7.0,
                2.5,
                0.30,
                "稳态暴露量约为母体的 25% - 35%",
                "保留约母体 30%-50% 的中枢变构结合，偏贡献轻度日间抗焦虑与清晨持续效应",
            ),
        ],
        "receptorTargets": [
            target("GABA-A 受体 (α1/α2/α3/α5 BZD 附近位点)", "非苯二氮䓬全亚型 PAM (兼助眠与轻度肌松)", "high", 0.92, "母体主导"),
        ],
    },
    "Eszopiclone": {
        "halfLifeHours": 6.0,
        "tMaxHours": 1.2,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 6.0,
        "washoutHours": 36.0,
        "washoutNote": "即佐匹克隆的纯 S-(+)-右旋有效异构体，效价约为消旋体 2 倍、半衰期约 6 小时，基本依赖母体原形作用；极高脂餐饮食可延后入睡达峰约 1 小时。",
        "mechanismSummary": "纯右旋艾司佐匹克隆，偏重 GABA-A α2/α3/α1 位点以改善维持睡眠而减少晨起昏沉。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GABA-A α2 / α3 亚基", "强效正向变构调节 (维持整夜睡眠与缓释焦虑缓解)", "high", 0.94, "母体主导"),
            target("GABA-A α1 亚基", "中等至强 PAM (主导快速入眠)", "high", 0.88, "母体主导"),
        ],
    },
    # === Prodrugs, Opioids & Stimulants ===
    "Codeine": {
        "brandName": "磷酸可待因 (Codeine) / 复方制剂 (如泰勒宁/止咳糖浆)",
        "halfLife": "母体约 3 小时 (真正镇痛与呼吸抑制活性取决于经 CYP2D6 转化的吗啡)",
        "halfLifeHours": 3.0,
        "tMax": "1.0 - 1.5 小时",
        "tMaxHours": 1.2,
        "bioavailability": "口服 50% - 90%",
        "cypEnzymes": [
            "CYP2D6 (将约 5%-10% 可待因 O-脱甲基活化为吗啡；关键限速酶)",
            "UGT2B7 (将 70%-80% 转化为 C6G)",
            "CYP3A4 (生成无活性去甲可待因)",
        ],
        "clearance": "肝脏代谢后主要经肾脏排泄；CYP2D6 超快代谢者易在常规剂量下发生致死性吗啡中毒",
        "indications": [
            "轻至中度镇痛",
            "顽固性剧烈干咳短期镇咳",
        ],
        "contraindications": [
            "12 岁以下儿童及扁桃体/腺样体切除术后 18 岁以下青少年 (致死呼吸抑制警示)",
            "已知 CYP2D6 超快代谢基因型或哺乳期",
            "合用强效 CYP2D6 抑制剂（帕罗西汀/氟西汀/安非他酮）会令其完全失去镇痛效应",
        ],
        "pkRole": "PRODRUG",
        "effectiveHalfLifeHours": 3.5,
        "washoutHours": 24.0,
        "washoutNote": "可待因原形与 μ-阿片受体结合极弱（几乎为无活性前体药物！）。它完全依赖肝脏 CYP2D6 酶将约 5%-10% 剂量转化为强效 200 倍的“吗啡”才发挥镇痛与欣快感：CYP2D6 慢代谢者（或正联用氟西汀/帕罗西汀/安非他酮者）完全无效；反之 CYP2D6 超快代谢基因拷贝者即便吃日常止咳量，也会瞬间生成中毒量吗啡引发呼吸骤停！",
        "mechanismSummary": "典型的 CYP2D6 依赖型前体药物：母体主要起前药载具与部分镇咳作用，绝大部分 μ-阿片镇痛、欣快及致死性呼吸抑制均由体内生成的吗啡执行。",
        "activeMetabolites": [
            met(
                "Morphine (吗啡)",
                "经 CYP2D6 O-脱甲基生成（仅占转化量 ~5-10%，但受体亲和力暴涨 ~200 倍）",
                "2 - 4 小时 (典型 3.0h)",
                3.0,
                1.2,
                0.25,
                "血浆质量分数虽约母体 5%-15%，但主导超过 90% 的 MOR 镇痛药效",
                "高亲和力 μ-阿片受体完全激动剂；受基因多态性与 CYP2D6 抑制剂支配，极易在超快代谢者体内超标致呼吸衰竭",
            ),
            met(
                "Codeine-6-glucuronide (可待因-6-葡糖苷酸 / C6G)",
                "经 UGT2B7 直接葡糖醛酸化生成 (~50-70% 代谢流)",
                "约 4 小时 (肾衰蓄积)",
                4.0,
                1.5,
                1.2,
                "循环血浓度约为母体 1 - 1.5 倍，效力弱",
                "保留微弱部分阿片活性，肾功能严重不全患者可蓄积加深昏睡",
            ),
        ],
        "receptorTargets": [
            target("μ-阿片受体 (MOR)", "强效完全激动剂 (几乎完全经代谢产物吗啡实现，母体仅 ~1/200 活性)", "high", 0.94, "代谢物主导"),
            target("δ / κ-阿片受体", "弱激动辅助镇痛", "low", 0.35, "代谢物主导"),
        ],
    },
    "Tramadol": {
        "brandName": "奇曼丁 / 舒敏 (Ultram / Tramal) | 格兰泰 (Grünenthal)",
        "halfLife": "母体约 6 小时 (SNRI 促癫痫/5-HT 主体)；CYP2D6 活性产物 O-DSMT 约 7.5 小时 (阿片镇痛主力)",
        "halfLifeHours": 6.0,
        "tMax": "1.5 - 2.0 小时 (缓释制剂约 4 - 6 小时)",
        "tMaxHours": 2.0,
        "bioavailability": "约 70% - 75% (连续给药可达 ~90%)",
        "cypEnzymes": [
            "CYP2D6 (将母体活化为超强阿片代谢体 O-DSMT / M1)",
            "CYP2B6 / CYP3A4 (生成无活性 N-去甲基曲马多 M2)",
        ],
        "clearance": "肝脏氧化后 ~90% 经尿液排出（含约 30% 原形及 60% 代谢物）；同时受肾功能与 CYP2D6 酶活双重调控",
        "indications": [
            "中至中重度急慢性疼痛",
        ],
        "contraindications": [
            "合并癫痫病史或同时合用降低癫痫阈值药物（极易诱发全面性强直阵挛发作）",
            "与 MAOIs、SSRIs、SNRIs、MDMA 等 5-HT 能药物联用（高致死性血清素综合征风险！）",
            "12 岁以下儿童或 CYP2D6 超快代谢者",
        ],
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 7.5,
        "washoutHours": 48.0,
        "washoutNote": "曲马多具有罕见的“双面代谢分裂”风险：母体原形本质是 SNRI（强抑 SERT 与 NET，直接诱发血清素综合征和显著降低惊厥阈值），而真正的 μ-阿片镇痛效力来自经 CYP2D6 生成的 O-DSMT（亲和力高达母体的 200 倍！）。合用氟西汀/帕罗西汀/安非他酮不仅剥夺 O-DSMT 镇痛效用，还会反向叠加母体致 5-HT 危机与癫痫发作！",
        "mechanismSummary": "非典型双机制混合镇痛药：母体执行 SERT/NET 抑制（促癫痫、禁联用 MAOI/SSRI），活性产物 O-DSMT 则担当真正的强效 μ-阿片激动主角。",
        "activeMetabolites": [
            met(
                "O-DSMT (O-去甲基曲马多 / M1)",
                "经 CYP2D6 催化 O-脱甲基活化",
                "7 - 9 小时 (典型 7.5h)",
                7.5,
                3.0,
                0.45,
                "稳态暴露量约为母体的 35% - 55%，但 MOR 亲和力约高出母体 200 倍",
                "几乎承担曲马多全部临床 μ-阿片镇痛与部分欣快呼吸抑制；去除母体后对 SERT 极弱、更不易致癫痫",
            ),
        ],
        "receptorTargets": [
            target("μ-阿片受体 (MOR)", "强效完全激动剂 (几乎全部由代谢物 O-DSMT 主导，Ki 高出母体百倍)", "high", 0.94, "代谢物主导"),
            target("SERT (5-HT 转运体)", "中至强抑制 + 促释放 (+)-曲马多母体主力，与 SSRI/MAOI 致命冲突来源", "high", 0.85, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "中等抑制 ((-)-曲马多与 O-DSMT 共协助下行疼痛抑制与降惊厥阈值)", "moderate", 0.72, "双相共同主导"),
        ],
    },
    "O-DSMT": {
        "halfLifeHours": 8.0,
        "tMaxHours": 2.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 8.0,
        "washoutHours": 48.0,
        "washoutNote": "直接跳过了曲马多母体的 5-HT 促释放及降惊厥阈值隐患，也无需依赖易变的 CYP2D6 活化环节；但仍保留较明显 μ-阿片呼吸抑制风险及中等去甲肾上腺素再摄取阻滞特性（勿与呼吸抑制剂或降压药混用）。",
        "mechanismSummary": "即曲马多的纯正活性阿片代谢产物本身：由母体直接激活 MOR 与适度 NET 阻滞，不再带入母体的重度血清素能致癫痫危机。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("μ-阿片受体 (MOR)", "强效完全激动剂 (效力高出母药曲马多约 200 倍)", "high", 0.95, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "中等抑制剂 (保留微弱激动或恶心伴随效应)", "moderate", 0.65, "母体主导"),
            target("SERT (5-HT 转运体)", "极微弱作用 (远低于曲马多原药)", "low", 0.15, "母体主导"),
        ],
    },
    "Lisdexamfetamine": {
        "brandName": "Vyvanse / Elvanse (二甲磺酸赖右苯丙胺) | 武田制药 (Takeda / Shire)",
        "halfLife": "惰性前药母体 < 1 小时；活性右旋苯丙胺 (d-Amphetamine) 约 10 - 12 小时 (典型 11h)",
        "halfLifeHours": 1.0,
        "tMax": "前药约 1.0 小时达峰；活性右旋苯丙胺约 3.5 - 4.5 小时达峰",
        "tMaxHours": 1.0,
        "bioavailability": "口服经肠 PEPT1 转运蛋白完全主动吸收，前药几乎 100% 在红细胞中裂解活化",
        "cypEnzymes": [
            "非 CYP 酶系活化：红细胞肽酶水解离去 L-赖氨酸",
            "释放出右旋苯丙胺后部分经 CYP2D6 羟基化，其余随尿液 pH 排泄",
        ],
        "clearance": "高脂餐后仅略推迟活化达峰 ~1h，但总暴露量基本不受损；尿液明显酸化会加速 d-Amphetamine 清除，碱化则会严重延长滞留",
        "indications": [
            "注意缺陷多动障碍 (ADHD)",
            "中重度暴食症 (BED)",
        ],
        "contraindications": [
            "14 天内联用单胺氧化酶抑制剂 (MAOIs，致致命性高血压危象！)",
            "症状性心血管疾病、中重度高血压、甲亢、青光眼",
        ],
        "pkRole": "PRODRUG",
        "effectiveHalfLifeHours": 11.0,
        "washoutHours": 60.0,
        "washoutNote": "二甲磺酸赖右苯丙胺本身毫无中枢精神活性（鼻吸或静脉注射也无法快速起效！）。口服后经红细胞内肽酶缓慢稳速剪切 L-赖氨酸，平稳释放长效右旋苯丙胺；因而虽然前药半衰期仅约 1h，实际药理与心脑血管刺激会稳态维持约 12-14 小时，停药洗脱按右旋苯丙胺 11h 半衰期计算。",
        "mechanismSummary": "防滥用恒速限流前体药物：母体完全惰性，经红细胞酶稳速转化为右旋苯丙胺 (d-Amphetamine) 再促发突触囊泡及 DAT/NET 反向释放。",
        "activeMetabolites": [
            met(
                "d-Amphetamine (右旋苯丙胺)",
                "母体在红细胞中经非肝酶肽酶水解切除赖氨酸后释放",
                "10 - 12 小时 (尿液偏碱时可延至 16h+)",
                11.0,
                3.8,
                3.5,
                "完全取代惰性母体，承载 100% 临床药效与暴露面积",
                "作用于 VMAT2、TAAR1 及 DAT/NET 的强效多巴胺与去甲肾上腺素促释放剂",
            ),
        ],
        "receptorTargets": [
            target("TAAR1 / VMAT2", "激动剂 / 囊泡囊腔逆转释放 (强促胞浆 DA/NE 外排)", "high", 0.96, "代谢物主导"),
            target("DAT / NET 转运体", "逆向转运与再摄取双重抑制", "high", 0.94, "代谢物主导"),
        ],
    },
    "Asenlix": {
        "halfLife": "母体氯苄雷司约 2 小时；活性右旋苯丙胺代谢物约 10 - 12 小时",
        "halfLifeHours": 2.0,
        "tMaxHours": 1.5,
        "pkRole": "PRODRUG",
        "effectiveHalfLifeHours": 11.0,
        "washoutHours": 60.0,
        "washoutNote": "氯苄雷司系右旋苯丙胺的 N-邻氯苄基前体药物：母体本身活性有限且 2h 内快速脱苄基转化为典型的右旋苯丙胺（可检出常规安非他明阳性并遵循 14 天 MAOI 禁忌规范）。",
        "mechanismSummary": "传统前药型中枢兴奋抑食剂：经由体内 N-脱氯苄基反应直接释放右旋苯丙胺执行核心中枢药理。",
        "activeMetabolites": [
            met(
                "d-Amphetamine (右旋苯丙胺)",
                "经 CYP 酶系 N-脱邻氯苄基活化",
                "10 - 12 小时 (典型 11h)",
                11.0,
                3.5,
                2.5,
                "主要药效荷载物（占核心药理暴露 80%+）",
                "执行全部 VMAT2 / TAAR1 / DAT / NET 兴奋激活与厌食效应",
            ),
        ],
        "receptorTargets": [
            target("DAT / NET 转运体", "逆转外排与再摄取阻滞 (主要由右旋苯丙胺产出执行)", "high", 0.94, "代谢物主导"),
            target("TAAR1 / VMAT2", "强效囊泡释放诱导", "high", 0.92, "代谢物主导"),
        ],
    },
    "Methylphenidate": {
        "halfLifeHours": 3.5,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 3.5,
        "washoutHours": 24.0,
        "washoutNote": "母体主要经 CES1A1 水解为无活性的利他林酸并迅速经尿排出，短效片仅维持 3-4 小时；注意若与高浓度酒精同服，CES1 可发生酯交换生成药效更强且具更强心肌毒性的乙基哌醋甲酯 (Ethylphenidate)！禁用 MAOIs。",
        "mechanismSummary": "经典 NDRI（纯阻滞剂而非苯丙胺样强促囊泡排空剂），由右旋对映体母体直接主导突触前 DAT/NET 封锁。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("DAT (多巴胺转运体)", "强效竞争性再摄取抑制剂 (Ki ≈ 34 nM，集中提升纹状体与前额叶突触多巴胺)", "high", 0.96, "母体主导"),
            target("NET (去甲肾上腺素转运体)", "强效再摄取抑制剂 (改善工作记忆与警觉)", "high", 0.90, "母体主导"),
            target("5-HT1A 受体 / SERT", "极弱结合 (临床剂量无血清素释放危机，但高兴奋仍禁 MAOI)", "low", 0.15, "母体主导"),
        ],
    },
    "Atomoxetine": {
        "halfLife": "母体 5 小时 (CYP2D6 慢代谢者激增至 21.6 小时)；活性 4-羟托莫西汀约 6 - 8 小时",
        "halfLifeHours": 5.0,
        "tMaxHours": 1.5,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 6.0,
        "washoutHours": 48.0,
        "washoutNote": "高度依赖 CYP2D6 进行芳环羟化：虽产物 4-羟托莫西汀具备同等高 NET 抑制效价，正常人迅速经葡糖醛酸化清除；一旦联用氟西汀、帕罗西汀、安非他酮或属 CYP2D6 慢代谢基因型，母体 AUC 会瞬间暴涨 5-10 倍且半衰期延至 ~22h，此时建议直接减半或降阶滴定。",
        "mechanismSummary": "非兴奋剂高选择性 NET 抑制剂，依赖前额叶皮层缺乏 DAT 的生理特点间接提升该区多巴胺及去甲肾上腺素（无纹状体成瘾风险）。",
        "activeMetabolites": [
            met(
                "4-Hydroxyatomoxetine (4-羟托莫西汀)",
                "经 CYP2D6 主催化芳环 4-羟基化生成，随后速被 UGT 灭活",
                "6 - 8 小时 (正常人迅速结合排泄)",
                7.0,
                2.5,
                0.25,
                "游离浓度低于母体，但结合亲和力几乎等强",
                "在 CYP2D6 正常代谢者中为强力等价辅助 NET 抑制体；CYP2D6 慢代谢时转化为极少",
            ),
        ],
        "receptorTargets": [
            target("NET (去甲肾上腺素转运体)", "极高选择性抑制剂 (Ki ≈ 5 nM，前额叶共升 NE 与 DA)", "high", 0.97, "母体主导"),
            target("SERT / DAT 转运体", "微弱结合 (对纹状体成瘾区无多巴胺冲击)", "low", 0.25, "母体主导"),
        ],
    },
    "Modafinil": {
        "halfLifeHours": 15.0,
        "tMaxHours": 3.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 15.0,
        "washoutHours": 96.0,
        "washoutNote": "母体主要消除，代谢物莫达非尼酸基本无活性；本身为中效 CYP3A4 诱导剂且同时抑制 CYP2C19——会明显加速激素类避孕药、环孢素及地高辛失活（停药后酶诱导状态仍持续 1-2 周，口服避孕药失效风险极高！）。",
        "mechanismSummary": "非典型促觉醒剂 (Eugeroic)：由母体低亲和力占位 DAT 抑制并协同调节下丘脑食欲素/组胺神经环路，同时几乎无强欣快冲动。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("DAT (多巴胺转运体)", "低亲和但显著临床占领抑制剂 (占纹状体 ~50%，激发清醒而非狂躁)", "high", 0.80, "母体主导"),
            target("食欲素 / 下丘脑组胺系统", "下游间接激活调节", "moderate", 0.68, "母体主导"),
            target("CYP3A4 酶 (诱导) / CYP2C19 (抑制)", "可逆降低口服避孕药血药浓度与减缓地西泮/奥美拉唑消除", "high", 0.85, "母体主导"),
        ],
    },
    "Armodafinil": {
        "halfLifeHours": 15.0,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 15.0,
        "washoutHours": 96.0,
        "washoutNote": "纯 R-(-)-莫达非尼对映体（排除消除更快、半衰期仅 4h 的 S-对映体），因而全天午后至傍晚浓度平台更平更高；同样显著诱导 CYP3A4 削弱激素避孕药与部分精神科药物。",
        "mechanismSummary": "莫达非尼长效活性右旋/左旋异构体纯化制剂，母体 R-对映体稳定支撑 12-14 小时清醒度。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("DAT (多巴胺转运体)", "纯 R-对映体稳定低亲和抑制 (延长日间维持警觉峰)", "high", 0.82, "母体主导"),
            target("食欲素 / 组胺通路", "下游觉醒维持激活", "moderate", 0.68, "母体主导"),
        ],
    },
    "Methadone": {
        "brandName": "美沙酮 (Dolophine / Methadose)",
        "halfLife": "极度个体波动: 8 - 59 小时 (典型 24h)",
        "halfLifeHours": 24.0,
        "tMax": "2.0 - 4.0 小时",
        "tMaxHours": 3.0,
        "bioavailability": "口服 70% - 90% (高脂溶且广泛分布进组织)",
        "cypEnzymes": [
            "CYP2B6 (关键限速酶，影响深远)",
            "CYP3A4",
            "CYP2C19",
        ],
        "clearance": "主要经肝脏 N-去甲基生成无活性 EDDP 后胆汁/尿排泄；因个体清除极度悬殊，连续给药第 3-5 天极具迟发性致命蓄积危险",
        "indications": [
            "重度慢性疼痛",
            "阿片类成瘾长期替代维持治疗 (MMT) 与脱瘾",
        ],
        "contraindications": [
            "严重支气管哮喘或呼吸抑制",
            "基础 QTc > 500 ms 或合用其他延长 QT 药物 (致死性尖端扭转型室速)",
            "联用氟伏沙明、环丙沙星、伏立康唑、BZD 或乙醇",
        ],
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 24.0,
        "washoutHours": 168.0,
        "washoutNote": "危险的双相动力背离！美沙酮的镇痛仅维持 6-8 小时，但其血浆与高脂溶组织半衰期常达 24-59 小时。切忌因疼痛回升而频繁加量——连续服药第 3-7 天血药会在毫无征兆下猛增致夜间呼吸骤停或 TdP 心律失常，停药交互警示需至少保留 7 天（168h）。",
        "mechanismSummary": "高亲脂性合成长效阿片全激动剂（R-对映体），兼具 S-对映体 NMDA 拮抗与弱 SERT/NET 抑制；但极易延迟蓄积并阻滞心脏 hERG 通道。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("μ-阿片受体 (MOR)", "高亲和力完全激动剂 (主要由 (R)-美沙酮贡献)", "high", 0.96, "母体主导"),
            target("NMDA 谷氨酸受体", "非竞争性阻滞剂 (减轻阿片耐受及神经性疼痛，(S)-美沙酮亦贡献)", "moderate", 0.65, "母体主导"),
            target("hERG 钾通道 (心脏)", "显著阻滞 (剂量相关性延长 QTc 致心源猝死风险)", "high", 0.85, "母体主导"),
            target("SERT / NET 转运体", "微弱抑制 (可叠加触发血清素综合征，禁近 MAOI)", "low", 0.42, "母体主导"),
        ],
    },
    "Buprenorphine": {
        "brandName": "丁丙诺啡 / 舒倍生 (Subutex / Suboxone 舌下片 | Butrans 贴片)",
        "halfLife": "舌下给药 24 - 48 小时 (典型 32h)；活性去甲丁丙诺啡约 38 小时",
        "halfLifeHours": 32.0,
        "tMax": "舌下片约 1.0 - 1.5 小时",
        "tMaxHours": 1.3,
        "bioavailability": "口服首过破坏极高仅 10%；舌下绕过首过可达 30% - 55%",
        "cypEnzymes": [
            "CYP3A4 (N-脱烷基生成去甲丁丙诺啡)",
            "UGT1A1 / UGT2B7 (直接葡糖醛酸化)",
        ],
        "clearance": "主要经胆汁粪便消除 (>70%)，极低受肾功能衰竭影响；亲和力极强且从 MOR 解离极为缓慢",
        "indications": [
            "阿片类药物依赖替代维持治疗 (OUD)",
            "中重度慢性癌痛/术后痛",
        ],
        "contraindications": [
            "严禁在体内仍有高剂量吗啡/美沙酮/海洛因期间过早舌下含服（极高亲和抢位即刻诱发极度痛苦的“催促性戒断反应”）",
            "与苯二氮䓬或乙醇大量联用",
        ],
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 32.0,
        "washoutHours": 168.0,
        "washoutNote": "母体对 μ-阿片受体具超高亲和力 (Ki ≈ 0.2 nM) 与超慢解离动力学，半衰期 24-48h；可高强度封堵后续海洛因/羟考酮结合长达 3-5 天以上（若此时擅自超量冲服全激动剂极易突发性致命呼吸骤停，且一般纳洛酮抢救常难以推翻！）。",
        "mechanismSummary": "超高亲和、极慢解离的 μ-阿片受体部分激动剂（具呼吸抑制天花板效应）兼 κ-阿片受体拮抗，母体稳占绝对中枢主角。",
        "activeMetabolites": [
            met(
                "Norbuprenorphine (去甲丁丙诺啡)",
                "经 CYP3A4 N-脱烷基生成",
                "30 - 45 小时 (典型 38h)",
                38.0,
                8.0,
                0.60,
                "外周暴露较高，但易被血脑屏障 P-gp 糖蛋白外排泵出大脑",
                "强效 μ 完全激动前驱体，正常脑内占比小但一旦合用 P-gp 抑制剂或过量时能贡献外周呼吸/肠道抑制",
            ),
        ],
        "receptorTargets": [
            target("μ-阿片受体 (MOR)", "超高亲和力慢解离部分激动剂 (Ki ≈ 0.2 nM，诱发催促戒断并带来部分封顶安全窗)", "high", 0.99, "母体主导"),
            target("κ-阿片受体 (KOR)", "强效拮抗剂 (带来轻度改善恶劣心境及减少焦虑)", "high", 0.85, "母体主导"),
            target("δ / ORL1 (诺西肽受体)", "弱拮抗 / 弱部分激动", "moderate", 0.60, "母体主导"),
        ],
    },
    # === Mood Stabilizers & Anticonvulsants ===
    "Oxcarbazepine": {
        "halfLife": "前药母体仅 2 小时 (迅速经胞质还原酶彻底活化)；真正主力 MHD 约 8 - 10 小时 (典型 9h)",
        "halfLifeHours": 2.0,
        "tMaxHours": 1.5,
        "pkRole": "PRODRUG",
        "effectiveHalfLifeHours": 9.0,
        "washoutHours": 60.0,
        "washoutNote": "奥卡西平母体本身基本无临床抗惊厥或稳定心境效应，进入肝脏后在胞质醛酮还原酶作用下数小时内近乎完全转化为单羟基衍生体 (MHD / 利卡西平)，后者半衰期约 9 小时真正维持钠通道阻滞。注意高剂量会诱导 CYP3A4 (降低激素避孕药疗效!) 并较常引发明显低钠血症。",
        "mechanismSummary": "经典前体药物升级版：避开卡马西平高敏“环氧化物”中间毒物，母体 2h 迅速全部变身为活性 MHD 抑制电压门控钠通道。",
        "activeMetabolites": [
            met(
                "MHD / 10-单羟基衍生体 (利卡西平外消旋体)",
                "经肝脏胞质醛酮还原酶快速非 CYP 还原活化",
                "8 - 10 小时 (典型 9h；肾损显著延至 19h+)",
                9.0,
                5.0,
                8.0,
                "稳态暴露量达母体的 8 - 12 倍（承载近 100% 临床药效）",
                "真正封锁神经元 Nav1.x 电压依赖钠通道快速失活态及抑制高阈值钙通道的绝对主力",
            ),
        ],
        "receptorTargets": [
            target("电压门控钠通道 (Nav1.x)", "使用依赖性通道稳定阻滞剂 (完全由代谢物 MHD 主导)", "high", 0.94, "代谢物主导"),
            target("CYP3A4 酶 / 尿抗利尿敏感度", "微弱诱导 CYP3A4（降低避孕药疗效）及易诱发低钠血症 (SIADH)", "moderate", 0.62, "代谢物主导"),
        ],
    },
    "Carbamazepine": {
        "halfLife": "单次服药 35 - 40 小时；连续服用数周发生自身强效 CYP3A4 酶诱导后骤缩至 12 - 17 小时 (典型 15h)",
        "halfLifeHours": 15.0,
        "tMaxHours": 6.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 15.0,
        "washoutHours": 168.0,
        "washoutNote": "四大临床警告：(1) 自身强诱导 CYP3A4 致用药后 2-4 周药浓度自然滑坡须按治疗窗重新调量，停药后高诱导酶状态仍可残留 1-2 周致并用药物剧烈波幅（洗脱期内会严重清除瑞波西汀/利培酮/喹硫平/拉莫三嗪/激素避孕药）；(2) 毒性及疗效核心来源还含活性代谢物 10,11-环氧化物（受丙戊酸抑制清除时易共济失调复视中毒）；(3) 汉族/东南亚人群使用前强烈建议查 HLA-B*1502 防致死性 SJS/TEN。",
        "mechanismSummary": "广谱酶强诱导型稳定剂：母体阻滞电压钠通道同时产生高活性的 10,11-环氧卡马西平，联用丙戊酸将阻滞环氧化物水解酿成神经中毒。",
        "activeMetabolites": [
            met(
                "Carbamazepine-10,11-epoxide (10,11-环氧卡马西平)",
                "主要经 CYP3A4 环氧化生成，经环氧化物水解酶清除",
                "约 5 - 8 小时 (典型 6h)",
                6.0,
                4.0,
                0.45,
                "稳态浓度可达母体的 30% - 60% (联用丙戊酸或奎硫平时飙升)",
                "具备与母体同等抗癫痫活性，同时是导致复视、眩晕、嗜睡与共济失调毒性反应的主要推手",
            ),
        ],
        "receptorTargets": [
            target("电压门控钠通道 (Nav1.x)", "频率依赖性失活态稳定阻滞 (镇痛与抗躁狂)", "high", 0.94, "双相共同主导"),
            target("CYP3A4 / CYP1A2 / UGT 核受体 PXR", "强效泛诱导剂 (摧毁避孕药、降诸多抗精神病及抗凝药浓 >50%)", "high", 0.96, "母体主导"),
        ],
    },
    "Valproic acid": {
        "halfLifeHours": 15.0,
        "tMaxHours": 3.0,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 15.0,
        "washoutHours": 96.0,
        "washoutNote": "强效泛酶抑制剂（极度抑制 UGT1A4 与环氧化物水解酶）：可将拉莫三嗪半衰期瞬间拉长 2 倍并诱发致死性皮疹，且极易升高环氧卡马西平浓度；此外具明确高致畸风险与高氨血症脑病隐患。",
        "mechanismSummary": "广谱 GABA 增效、组蛋白去乙酰化及钠通道抑制剂，含次要活性代谢物 2-烯-丙戊酸，并以强效 UGT 酶抑制改变并用药格局。",
        "activeMetabolites": [
            met(
                "2-ene-Valproic acid (2-烯-丙戊酸)",
                "经线粒体 β-氧化脱氢生成",
                "16 - 24 小时 (典型 18h)",
                18.0,
                6.0,
                0.25,
                "约为母体暴露量的 15% - 30%，脑脊液相对滞留更久",
                "具有明确抗惊厥协同活性，部分解释停药后脑内神经化学延迟维持与代谢毒性",
            ),
        ],
        "receptorTargets": [
            target("GABA 转氨酶 / GAD", "抑制 GABA 降解并提升脑内总抑制张力", "high", 0.86, "母体主导"),
            target("电压门控钠通道 / T-型钙通道", "多向离子通道调制", "high", 0.82, "双相共同主导"),
            target("UGT1A4 / CYP2C9 / 环氧化物水解酶", "强效代谢抑制 (令拉莫三嗪血药翻倍致重度 SJS 皮疹危机)", "high", 0.92, "母体主导"),
        ],
    },
    "Sodium valproate": {
        "halfLifeHours": 15.0,
        "tMaxHours": 3.5,
        "pkRole": "ACTIVE_METABOLITE",
        "effectiveHalfLifeHours": 15.0,
        "washoutHours": 96.0,
        "washoutNote": "口服进入胃肠道后迅速解离为游离丙戊酸根发挥完全一致药理；同样极其强力抑制拉莫三嗪葡糖醛酸化清除（联用需按严格低起点减半滴定表操作）且禁用于育龄期高致畸风险场景。",
        "mechanismSummary": "丙戊酸的钠盐与缓释载体形态，吸收后即刻解离为丙戊酸并伴随长效 2-烯-丙戊酸协同与强 UGT 酶抑制效应。",
        "activeMetabolites": [
            met(
                "2-ene-Valproic acid (2-烯-丙戊酸)",
                "经线粒体 β-氧化产生",
                "约 18 小时",
                18.0,
                6.0,
                0.25,
                "约占母体暴露面积 20% - 25%",
                "保留抗惊厥效应并略延缓停药期总药效回落",
            ),
        ],
        "receptorTargets": [
            target("GABA 代谢酶系 (GABA-T/SSADH)", "提升突触抑制性 GABA 池", "high", 0.86, "母体主导"),
            target("电压门控钠/T型钙通道", "抑制异常高频放电", "high", 0.82, "双相共同主导"),
            target("UGT1A4 酶 (药物相互作用靶点)", "强效抑制 (令拉莫三嗪半衰期骤翻至 ~60 小时)", "high", 0.92, "母体主导"),
        ],
    },
    "Lamotrigine": {
        "halfLifeHours": 29.0,
        "tMaxHours": 2.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 29.0,
        "washoutHours": 145.0,
        "washoutNote": "无活性代谢物，纯由肝脏 UGT1A4 直接葡糖醛酸化清除——因此受合并用药改变极剧烈：单药半衰期 ~29h；合并丙戊酸钠时暴涨至 ~60h（必须减半超慢加量否则暴发 SJS/TEN 皮肤坏死）；合并卡马西平/苯妥英或口服含雌激素避孕药时半衰期可骤降至 ~14h！中断服药 ≥5 天必须从最低起点 25mg/d 重新滴定！",
        "mechanismSummary": "精准稳固神经元钠通道失活态以阻断病理性谷氨酸暴发的“基线防抑郁”双相金标准，完全由母体原形主导、高度受 UGT1A4 诱导/抑制支配。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("电压门控钠通道 (Nav1.x)", "慢失活态选择性稳态阻滞 (直接抑制突触前过量谷氨酸释放)", "high", 0.95, "母体主导"),
            target("高电压激活钙通道 (Cav2.1/2.2)", "轻至中度抑制", "moderate", 0.62, "母体主导"),
        ],
    },
    "Topiramate": {
        "halfLifeHours": 21.0,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 21.0,
        "washoutHours": 105.0,
        "washoutNote": "极少经过肝脏代谢（约 70% 以原形直接随尿排出），半衰期 ~21h；高剂量 (≥200mg/d) 可轻微诱导 CYP3A4 并削弱口服避孕药雌激素成分，且因抑制碳酸酐酶常致手脚麻木、肾结石、代谢性酸中毒及显著语言找词困难（“蠢药”副作用）。",
        "mechanismSummary": "多靶点广谱稳定及减重/偏头痛预防药：母体直接增强 GABAA、封锁 AMPA/KA 谷氨酸受体并抑制碳酸酐酶。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("AMPA / Kainate 谷氨酸受体", "非竞争性负向变构阻滞 (防偏头痛及食欲抑制主力之一)", "high", 0.85, "母体主导"),
            target("GABA-A 受体 (非 BZD 位点)", "正向变构增强", "high", 0.80, "母体主导"),
            target("碳酸酐酶 II / IV", "中等抑制 (解释发麻、碳酸饮料变味、肾结石及代谢性酸中毒)", "moderate", 0.68, "母体主导"),
        ],
    },
    "Pregabalin": {
        "halfLifeHours": 6.3,
        "tMaxHours": 1.3,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 6.3,
        "washoutHours": 36.0,
        "washoutNote": "吸收呈高线性（>90% 迅速吸收入血），且全血浆完全不经肝酶代谢也不绑定血浆蛋白（约 98% 以纯原形从肾脏经肾小球滤过排泄）——绝少肝酶冲突但严格随肌酐清除率比例减量。切忌当作普通 GABA 替代品高剂量叠加阿片或 BZD（合用显著升高致死性中枢呼吸抑制）。",
        "mechanismSummary": "虽结构模仿 GABA 却完全不结合 GABAA/B 受体——由母体精准封锁突触前钙通道 α2δ-1 亚基降低谷氨酸与 P 物质释放。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("VGCC α2δ-1 / α2δ-2 亚基 (钙通道)", "高亲和力抑制性配体 (阻止钙内流及兴奋性递质过度外排)", "high", 0.98, "母体主导"),
            target("GABA-A / GABA-B 受体", "无直接结合活性", "low", 0.08, "母体主导"),
        ],
    },
    "Gabapentin": {
        "halfLifeHours": 6.0,
        "tMaxHours": 3.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 6.0,
        "washoutHours": 36.0,
        "washoutNote": "同样 100% 不经肝脏代谢、纯原形经肾排出，但吸收依赖小肠有限容量的 LAT1 L-氨基酸转运蛋白——单次吞服剂量越大，生物利用度反呈悬崖式下降（300mg ~60%，600mg ~40%，1200mg+ <30%，临床须分次间隔服用以维持血药）；合用阿片类可逆向减缓胃排空推高加巴喷丁吸收并加重呼吸抑制。",
        "mechanismSummary": "典型可饱和转运吸收的 α2δ-1 钙通道调节剂：母体完全原形经肾排泄，无代谢酶交叉。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("VGCC α2δ-1 亚基 (钙通道)", "高选择性配体 (Ki 低于普瑞巴林，抑制神经痛觉高敏发放)", "high", 0.94, "母体主导"),
            target("LAT1 转运体 (吸收限制)", "依赖饱和型小肠/血脑屏障主动摄取", "moderate", 0.65, "母体主导"),
        ],
    },
    "Li": {
        "halfLifeHours": 24.0,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 24.0,
        "washoutHours": 144.0,
        "washoutNote": "纯无机阳离子（毫无肝脏代谢），完全依赖肾小球自由滤过且在近曲小管与钠离子竞争重吸收！治疗窗极窄 (0.6 - 1.2 mmol/L)：凡 NSAIDs 止痛药、噻嗪类利尿剂、ACEI/ARB 降压药、大量出汗/脱水或低盐饮食，均会诱发肾小管反代偿大幅回吸收锂离子导致迅速蓄积危重肾毒与神经中毒；且中枢脑内清除明显滞后于血浆。",
        "mechanismSummary": "唯一无机单价阳离子心境稳定剂与核心抗自杀金标准：母体直接替换镁离子抑制 GSK-3β 及肌醇单磷酸酶。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GSK-3β (糖原合酶激酶-3β)", "直接竞争 Mg2+ 位点 + 间接磷酸化强抑制 (抗躁狂、抗自杀与神经营养核心)", "high", 0.94, "母体主导"),
            target("IMPase / IPPase (肌醇磷酸酶)", "非竞争性抑制 (耗竭狂躁期亢进的肌醇信号循环)", "high", 0.90, "母体主导"),
            target("5-HT 神经传递 / NMDA", "中枢间接敏化突触后 5-HT（禁联用大量致 5-HT 毒性药物及 NSAIDs）", "moderate", 0.68, "母体主导"),
        ],
    },
    "Lithium": {
        "halfLifeHours": 24.0,
        "tMaxHours": 2.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 24.0,
        "washoutHours": 144.0,
        "washoutNote": "纯无机锂离子无代谢转变，经肾直接消除且与钠平衡紧密锁定；脑脊液半衰期与组织滞留较血清延迟，过量或并用布洛芬/氢氯噻嗪/赖诺普利时即便血锂浓度开始下降，中枢神经症状恢复仍滞后。严禁合用经典迷幻剂 (LSD/赛洛西宾——高风险触发重度癫痫发作！)。",
        "mechanismSummary": "心境稳定剂与防自杀核心离子：竞争镁辅因子抑制 GSK-3β 与磷酸肌醇通路，血药浓度窗极为狭窄。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("GSK-3β (糖原合酶激酶-3β)", "核心直接/间接抑制 (神经可塑性与抗双相极性稳定核心)", "high", 0.94, "母体主导"),
            target("IMPase / IPPase", "肌醇消耗型信号稳定", "high", 0.90, "母体主导"),
            target("肾小管钠共转运系统 (药代交互点)", "受 NSAIDs、利尿剂及低钠脱水强烈拦截而暴涨中毒风险", "high", 0.92, "母体主导"),
        ],
    },
    # === HRT & Endocrine & Peptide ===
    "Spironolactone": {
        "halfLife": "母体仅 1.4 小时 (迅速脱乙酰/硫甲基化)；核心长效代谢物坎利酮 16.5 小时，7α-TMS 约 13.8 小时",
        "halfLifeHours": 1.4,
        "tMaxHours": 2.8,
        "pkRole": "PRODRUG",
        "effectiveHalfLifeHours": 16.5,
        "washoutHours": 96.0,
        "washoutNote": "螺内酯母体在体循环仅停留约 1.4 小时，如果按母体估算药效几乎无法每日 1-2 次服用！临床真实的抗雄激素效应（阻滞雄激素受体及部分抑睾酮合成）与抗盐皮质激素（潴钾利尿）全由活性产物 7α-硫甲基螺内酯 (约 14h) 与坎利酮 (约 16.5h) 接力持续维持，高钾血症风险随稳态延后显现。",
        "mechanismSummary": "高首过转化型前药抗雄与保钾利尿药：母体快速转化为高血药浓度的 7α-TMS 与坎利酮执行全周期阻断。",
        "activeMetabolites": [
            met(
                "Canrenone (坎利酮)",
                "母体在肝脏快速脱去硫乙酰基产生的主要长效活性产物",
                "13 - 24 小时 (典型 16.5h)",
                16.5,
                3.5,
                6.0,
                "稳态暴露量可达母体的 5 - 8 倍",
                "稳定贡献保钾抗醛固酮（MR 拮抗）效应并拉长 96h 总洗脱窗口",
            ),
            met(
                "7α-Thiomethylspironolactone (7α-硫甲基螺内酯 / 7α-TMS)",
                "母体脱乙酰后再经 S-甲基化生成",
                "12 - 16 小时 (典型 13.8h)",
                13.8,
                3.0,
                4.5,
                "血浆浓度显著高于母体数倍（约占核心抗醛固酮/抗雄主体 ~80%）",
                "被证实为口服螺内酯后体内最主要的实质效价载体（MR 与 AR 拮抗主手）",
            ),
        ],
        "receptorTargets": [
            target("盐皮质激素受体 (MR / 醛固酮受体)", "强效竞争性拮抗剂 (保钾排水与降血压，禁合用高钾/ACEI 过量)", "high", 0.96, "代谢物主导"),
            target("雄激素受体 (AR) / 睾酮合成", "中等抗雄拮抗与微弱类固醇合成抑制 (跨性别女性基础阻雄)", "moderate", 0.72, "代谢物主导"),
            target("孕激素受体 (PR)", "极微弱结合", "low", 0.25, "双相共同主导"),
        ],
    },
    "Estradiol valerate": {
        "halfLife": "肌注油剂贮库 Flip-Flop 表观半衰期约 4 - 5 天 (96h)；经酯酶裂解的游离 17β-雌二醇自身消除约 14h (受贮库缓释限速)",
        "halfLifeHours": 96.0,
        "tMaxHours": 48.0,
        "pkRole": "DEPOT_FLIP_FLOP",
        "effectiveHalfLifeHours": 96.0,
        "washoutHours": 480.0,
        "washoutNote": "典型的“翻转动力学 (Flip-Flop PK)”长效酯化前药：体循环内源雌二醇本尊半衰期虽仅十几小时，但肌注油剂中戊酸雌二醇从肌肉油滴缓慢释放并水解（约 4-5 天半衰期）成为绝对限速步骤——因而实际人体内雌二醇水平呈 96 小时级别的缓慢消长；口服剂型（补佳乐）则首过迅速裂解，受肝首过影响大且波幅偏短，推荐每隔 4-5 天肌肉注射一次而非 7 天以上以防低谷潮热情绪不稳。",
        "mechanismSummary": "戊酸酯修饰雌二醇前药：肌注利用肌肉油库慢释放制造典型的“吸收/水解释放率低于消除率”翻转动力学效应，稳定输出天然 17β-雌二醇。",
        "activeMetabolites": [
            met(
                "17β-Estradiol (天然 17β-雌二醇)",
                "经血液及组织广泛非特异性酯酶脱去戊酸酯键释放",
                "自身血浆消除 ~14h (表观跟随油库 96h 限速尾部)",
                14.0,
                None,
                1.2,
                "完全承载受体激活效能（戊酸雌二醇未裂解前本身活性极微弱）",
                "直接入核激活雌激素受体 ERα/ERβ 并压低垂体 LH/FSH 促性腺激素分泌",
            ),
        ],
        "receptorTargets": [
            target("ERα / ERβ (核内雌激素受体)", "强效天然全激动剂 (完全由水解后的 17β-雌二醇实现)", "high", 0.99, "代谢物主导"),
            target("HPG 轴负反馈", "高浓度下强效抑制 GnRH/LH/FSH 内源性睾酮分泌", "high", 0.88, "代谢物主导"),
        ],
    },
    "Estradiol enanthate": {
        "halfLife": "肌注/深皮下贮库 Flip-Flop 表观半衰期约 5.6 - 7.0 天 (典型 144h)",
        "halfLifeHours": 144.0,
        "tMaxHours": 84.0,
        "pkRole": "DEPOT_FLIP_FLOP",
        "effectiveHalfLifeHours": 144.0,
        "washoutHours": 720.0,
        "washoutNote": "庚酸雌二醇较戊酸链长增加两个亚甲基，酯溶性明显加大、肌肉/皮下贮库水解更温和：表观消除半衰期约 6 天 (144h)，稳态建立需约 3-4 周（3-4 个 7-10 天给药周期），且血药谷峰波动极小，停针后彻底消退需接近 1 个月。",
        "mechanismSummary": "中长链脂肪酸酯前药制剂：通过脂溶性油库极缓水解实现平稳的天然 17β-雌二醇长周期翻转动力学供体。",
        "activeMetabolites": [
            met(
                "17β-Estradiol (天然 17β-雌二醇)",
                "经组织与血液酯酶裂解庚酸酯释放游离雌二醇",
                "自身消除 ~14h (跟随贮库表观呈现约 144h 长平台)",
                14.0,
                None,
                1.2,
                "完全承担生物活性 (暴露量按酯分子量等当换算约 73%)",
                "稳定持久激活 ERα/ERβ 与抑制 HPG 轴内源雄激素",
            ),
        ],
        "receptorTargets": [
            target("ERα / ERβ (核内雌激素受体)", "天然完全激动剂 (由释放的 17β-雌二醇驱动)", "high", 0.99, "代谢物主导"),
        ],
    },
    "Estradiol cypionate": {
        "halfLife": "肌注油剂贮库 Flip-Flop 表观半衰期约 8 - 10 天 (典型 216h)",
        "halfLifeHours": 216.0,
        "tMaxHours": 96.0,
        "pkRole": "DEPOT_FLIP_FLOP",
        "effectiveHalfLifeHours": 216.0,
        "washoutHours": 1008.0,
        "washoutNote": "环戊丙酸雌二醇因侧链引入环戊基，脂肪溶解度高、贮库水解释放速率最慢之一（表观半衰期长达 9 天），注射后 3-5 天才缓步爬峰，稳态耗时超过 1 个月，极适合每 7-14 天一次的平缓单药替代或联合方案。",
        "mechanismSummary": "环戊丙酸酯修饰极稳长效雌二醇油剂前药，以极缓慢水解翻转动力学持续提供天然 17β-雌二醇。",
        "activeMetabolites": [
            met(
                "17β-Estradiol (天然 17β-雌二醇)",
                "经非特异性羧酸酯酶缓慢切割环戊丙酸酯键",
                "自身消除 ~14h (受贮库限速表观呈 8-10 天拖尾)",
                14.0,
                None,
                1.2,
                "100% 药效执行分子 (等效游离雌二醇比例约 70%)",
                "长时间稳定激活雌激素受体系并平滑维持生理/雌性化水平",
            ),
        ],
        "receptorTargets": [
            target("ERα / ERβ (核内雌激素受体)", "天然全激动剂 (完全依靠脱酯游离雌二醇)", "high", 0.99, "代谢物主导"),
        ],
    },
    "Cyproterone acetate": {
        "halfLifeHours": 40.0,
        "tMaxHours": 3.5,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 40.0,
        "washoutHours": 240.0,
        "washoutNote": "母体原形半衰期 38-44 小时（高脂溶易在脂肪组织蓄积，长期口服 50-100mg 停药消退可逾 10 天）；临床 5-12.5 mg/日低剂量即可借助强力孕激素负反馈深压睾酮，极高累积剂量会显著增加脑膜瘤、抑郁情绪与肝损害风险。",
        "mechanismSummary": "强效孕激素样抗雄药物：双重拦截机制——低剂量强激动孕激素受体切断垂体 LH/睾酮生成（主导）+ 竞争封堵雄激素受体。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("孕激素受体 (PR)", "超强激动剂 (低剂量即强效压制 LH/FSH 致内源睾酮暴降)", "high", 0.96, "母体主导"),
            target("雄激素受体 (AR)", "竞争性拮抗剂 (阻断残留雄激素外周靶向结合)", "high", 0.85, "母体主导"),
            target("糖皮质激素受体 (GR)", "微弱激动 / 部分抑制内源皮质醇促生压抑情绪", "low", 0.35, "母体主导"),
        ],
    },
    "Bicalutamide": {
        "halfLifeHours": 168.0,
        "tMaxHours": 30.0,
        "pkRole": "PARENT_DOMINANT",
        "effectiveHalfLifeHours": 168.0,
        "washoutHours": 840.0,
        "washoutNote": "有效活性 R-比卡鲁胺半衰期长达整整 6 - 7 天（168 小时！S-体 1 天内快消且无活性），连续服用约 4 周才爬至 10-12 倍稳态累积浓度，停药彻底清洗需约 5 周（35 天）；且因纯粹阻断中枢 AR 负反馈反而会使内源睾酮与雌二醇反射性双升，须定期监查转氨酶防罕见严重肝损。",
        "mechanismSummary": "纯非甾体类超长效雄激素受体拮抗剂，R-对映体直接持久屏蔽全身 AR，完全不直接降低血清睾酮数值（仅“封锁受点”）。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("雄激素受体 (AR)", "高选择性纯拮抗剂 (几乎由 R-比卡鲁胺主导，不激活 PR)", "high", 0.96, "母体主导"),
            target("CYP3A4 / 肝细胞毒性靶点", "中弱 CYP3A4 抑制及罕见特异质性急性肝炎风险", "low", 0.40, "母体主导"),
        ],
    },
    "Dutasteride": {
        "halfLife": "极长终末消除半衰期约 3 - 5 周 (典型 720h)；且与 5α-还原酶呈伪不可逆超紧结合",
        "halfLifeHours": 720.0,
        "tMaxHours": 2.5,
        "pkRole": "IRREVERSIBLE_PD",
        "effectiveHalfLifeHours": 720.0,
        "washoutHours": 1008.0,
        "washoutNote": "精神与内分泌科最极端的非线性超长驻留药物之一！低剂量下消除呈非线性极度缓慢，终末半衰期高达 3-5 周（720 小时以上），加之对 I 型与 II 型 5α-还原酶的极强紧结合失活特性：临床停药后血中微量残留与 DHT 抑制可持续长达 4 - 6 个月，停药半年内严禁献血或孕期接触（本系统交互检查自动回溯至最长 42 天窗口并在此强提示完整半年安全期）。",
        "mechanismSummary": "双型 I/II 5α-还原酶强紧结合超长效抑制剂，可压低 >90% 血清 DHT 并跨周持续抑制神经甾体（如四氢孕酮）转化。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("5α-还原酶 I 型 & II 型", "伪不可逆超强双型抑制剂 (全面拦截睾酮→DHT 与孕酮→神经甾体通路)", "high", 0.99, "母体主导"),
        ],
    },
    "Finasteride": {
        "halfLife": "血浆母体仅约 6 小时 (老年人 ~8h)；但与 II 型 5α-还原酶紧密共价样稳定复合体解离及组织 DHT 恢复需 4 - 7 天",
        "halfLifeHours": 6.0,
        "tMaxHours": 1.5,
        "pkRole": "IRREVERSIBLE_PD",
        "effectiveHalfLifeHours": 6.0,
        "washoutHours": 168.0,
        "washoutNote": "典型“血浆短、靶点久”现象！非那雄胺自身血浆半衰期只有 6 小时（1 天后血中即几乎查不到母体），但它会与 5α-还原酶 II 型活性中心形成极其稳固的 NADP-二氢非那雄胺加合物（慢解离伪不可逆结合，半寿期 ~30 天，主要随酶蛋白自然更新代谢），导致血清 DHT 恢复常需停药 4-14 天。",
        "mechanismSummary": "选择性 5α-还原酶 II 型（及 III 型）慢解离抑制剂：母体 6h 速退但酶结合复合物持久压制 DHT 生成。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("5α-还原酶 II 型 (& III 型)", "慢解离时间依赖性紧抑制剂 (压低血清 DHT ~70%)", "high", 0.96, "药效动力学不可逆主导"),
            target("5α-还原酶 I 型", "亲和力低于非 II 型逾 100 倍 (神经甾体干预通常较度他雄胺轻微)", "low", 0.25, "母体主导"),
        ],
    },
    "Semax": {
        "halfLife": "血浆肽酶几分钟内迅速断裂失活 (< 0.1h)；但脑内 BDNF/TrkB 基因转录与神经调节效应延续约 20 - 24 小时",
        "halfLifeHours": 0.1,
        "tMaxHours": 0.4,
        "pkRole": "IRREVERSIBLE_PD",
        "effectiveHalfLifeHours": 0.1,
        "washoutHours": 24.0,
        "washoutNote": "典型短肽下游效应级联：经鼻喷雾入脑后在数分钟内即被氨肽酶分解，但其诱导的黑皮质素信号及海马/皮层 BDNF 上调可持续 20-24 小时。",
        "mechanismSummary": "ACTH(4-10) 修饰七肽益智肽：血浆存在不足数分钟，靠启动长程基因转录上调 BDNF 与 TrkB 磷酸化实现全天效应。",
        "activeMetabolites": [],
        "receptorTargets": [
            target("BDNF / TrkB 神经营养通路", "转录上调激活 (持续 20-24h)", "high", 0.92, "药效动力学不可逆主导"),
            target("MC4R / MC3R (黑皮质素受体)", "调节剂 (拮抗或部分调节)", "moderate", 0.70, "母体主导"),
        ],
    },
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
    added: list[str] = []
    for item in NEW_SUBSTANCES:
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
        added.append(name_en)
    return added


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
        entry_line = f'        "{en_name}" to "{zh_name}",'
        if f'"{en_name}" to ' in body:
            continue
        # Insert in case-insensitive alphabetical order among top map entries (after initial comments)
        insert_idx = len(lines)
        for idx, line in enumerate(lines):
            m_key = re.match(r'\s*"([^"]+)"\s+to\s+"', line)
            if m_key and m_key.group(1).lower() > en_name.lower():
                insert_idx = idx
                break
        lines.insert(insert_idx, entry_line)
        inserted.append(en_name)

    new_body = "\n".join(lines)
    updated_content = content[: match.start("body")] + new_body + content[match.end("body") :]
    HELPER_KT_PATH.write_text(updated_content, encoding="utf-8")
    return inserted


def main() -> None:
    with SUBSTANCES_PATH.open("r", encoding="utf-8") as f:
        substances_data = json.load(f)

    valid_categories = {
        c["name"] if isinstance(c, dict) else str(c)
        for c in substances_data["categories"]
    }
    sub_list: list[dict[str, Any]] = substances_data["substances"]
    sub_map: dict[str, dict[str, Any]] = {s["name"]: s for s in sub_list}

    added_names: list[str] = []
    for raw_new in NEW_SUBSTANCES:
        entry = {
            k: copy.deepcopy(v)
            for k, v in raw_new.items()
            if k not in ("zh_name", "zh_aliases", "categories_zh")
        }
        unknown_cats = [c for c in entry["categories"] if c not in valid_categories]
        if unknown_cats:
            raise ValueError(f"Unknown categories {unknown_cats} in {entry['name']}")
        if entry["name"] in sub_map:
            # Idempotent update of newly added entry
            sub_map[entry["name"]].update(entry)
        else:
            sub_list.append(entry)
            sub_map[entry["name"]] = entry
            added_names.append(entry["name"])

    upgraded_names: list[str] = []
    missing_targets: list[str] = []
    for name, patch in PK_UPGRADES.items():
        if name not in sub_map:
            missing_targets.append(name)
            continue
        merge_substance_pk(sub_map[name], patch)
        upgraded_names.append(name)

    if missing_targets:
        raise KeyError(f"Targets missing in Substances.json: {missing_targets}")

    # Persist Substances.json with standard 2-space indent for clean git diffs
    with SUBSTANCES_PATH.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(substances_data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    # Sync substances_zh_dict.json
    with ZH_DICT_PATH.open("r", encoding="utf-8") as f:
        zh_dict_data = json.load(f)
    zh_added = update_zh_dict(zh_dict_data)
    with ZH_DICT_PATH.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(zh_dict_data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    # Sync SubstanceLocalizationHelper.kt (ZH_NAME_MAP only)
    kt_added = update_localization_helper({item["name"]: item["zh_name"] for item in NEW_SUBSTANCES})

    # Post-write integrity assertions
    valid_pk_roles = {
        "PARENT_DOMINANT",
        "ACTIVE_METABOLITE",
        "PRODRUG",
        "IRREVERSIBLE_PD",
        "DEPOT_FLIP_FLOP",
    }
    upgraded_all = [item["name"] for item in NEW_SUBSTANCES] + upgraded_names
    for name in upgraded_all:
        pk = sub_map[name].get("pharmacokinetics") or {}
        assert pk.get("pkRole") in valid_pk_roles, f"Invalid pkRole on {name}: {pk.get('pkRole')}"
        assert pk.get("receptorTargets"), f"Empty receptorTargets on {name}"
        hl = pk.get("halfLifeHours")
        eff = pk.get("effectiveHalfLifeHours")
        wo = pk.get("washoutHours")
        assert hl and hl > 0, f"Invalid halfLifeHours on {name}"
        assert eff and eff > 0, f"Invalid effectiveHalfLifeHours on {name}"
        assert wo and wo > 0, f"Invalid washoutHours on {name}"
        for cat in sub_map[name].get("categories", []):
            assert cat in valid_categories, f"Invalid category {cat} on {name}"

    size_mb = SUBSTANCES_PATH.stat().st_size / (1024 * 1024)
    print(f"Substances total: {len(sub_list)} (newly inserted: {added_names})")
    print(f"Dual-PK/PD upgraded count: {len(upgraded_all)} drugs (3 new + {len(upgraded_names)} upgraded)")
    print(f"zh_dict synced: {zh_added}; ZH_NAME_MAP inserted: {kt_added}")
    print(f"Substances.json size: {size_mb:.2f} MB")


if __name__ == "__main__":
    main()

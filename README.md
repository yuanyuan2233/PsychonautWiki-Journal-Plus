# PsychonautWiki Journal Plus

基于 Jetpack Compose 开发的离线个人用药记录、规律服药管理与科学减害（Harm Reduction）参考工具（中英双语增强版）。

[![Latest Release](https://img.shields.io/github/v/release/yuanyuan2233/PsychonautWiki-Journal-Plus?label=Release&color=blue)](https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-green.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-orange.svg)](https://developer.android.com/about/versions/oreo)
[![Offline & Private](https://img.shields.io/badge/Privacy-100%25%20Offline-success.svg)](https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus)

---

## 项目背景与维护声明

PsychonautWiki Journal 原由 Isaak Hanimann 开源维护（至 v11.11 版本）。**PsychonautWiki Journal Plus** 为该工具在开源社区的长期独立延续与功能增强分支。

本项目遵循 GNU General Public License v3.0 协议持续维护，秉持以下原则：
- **完全开源**：应用代码与药典清洗/校验工具全量公开透明；
- **隐私优先、按需联网**：默认全本地离线运行，除用户主动在设置页点击「检查更新」请求 GitHub Releases API 外，不包含任何静默后台联网、遥测统计或广告追踪代码；已显式关闭系统自动云备份与换机数据迁移，全部个人数据仅保存在设备本地；
- **科学与药理本土化**：结合临床药理学（DrugBank / FDA DailyMed / PDSP / PubMed）与国家药典规范，对物质词典、药代动力学与减害参考体系进行持续校准。

---

## 核心特性与近期更新（v13.3 ~ v13.5.0）

### 0. 多态双轨药代动力学 2.0、CYP450 DDI 联动与自定义物质一等公民化（v13.5.0 新增）
- **42 天跨日血药实况与双量纲曲线**：支持将单次、多次不规则及持续区间给药记录（`[startTime, endTime]`）与过去 42 天已确认历史残留及规律服药记录同图卷积，实时输出**体内等效活性药量（mg/µg）**与归一化比例，支持一键切换「含 42 天历史残留 / 仅看本次体验」。
- **多态动力学分支与分途径（ROA）校准**：内置一级 Bateman 方程、缓释平顶（`EXTENDED_RELEASE`）双相吸收、乙醇等米氏零级饱和消除（`ZERO_ORDER`）、前药（`PRODRUG`）及活性代谢物级联曲线，并按给药途径动态校准达峰时间 $T_{\max}$ 与生物利用度 $F$。
- **CYP450 酶动力学 DDI 修正与对比**：自动识别强/中效 CYP1A2、CYP2B6、CYP2C9、CYP2C19、CYP2D6、CYP3A4 抑制剂与诱导剂联用，预警母体半衰期延长/缩短及前药活化受阻，并在体验曲线中以虚线对比展示“未受联用影响的标准曲线”。
- **自定义药物升级（Room v11）与个体化 PK 覆盖**：数据库升级至 **v11**（向后兼容 `MIGRATION_10_11`），允许自定义药物直接填写半衰期、达峰、生物利用度、剂型角色、活性代谢物及默认剂量/时程并自动推导等效时间线；新增 `PkCustomizationStore` 支持对内置药物设定个人代谢参数并纳入 JSON 备份同步。
- **$O(1)$ 药典检索架构与版本自更新**：在内存中预构建大小写与双语别名哈希表及单趟零正则搜索索引，全量检索与列表过滤全部移交后台 `Dispatchers.Default`；设置页新增 GitHub Release 应用内检查更新入口。

### 1. 规律服药与智能提醒系统（v13.3.0 新增，v13.4.1 加固）
- **五种周期调度模型**：支持**每日多时段**、**每周指定日**、**每隔 N 天**、**每隔 N 小时/分**以及**周期循环（用 X 天停 Y 天）**；支持多时段独立剂量、估算剂量与标准差（±）、关联自定义单位、服用人标记、起止日期、总次数上限及单时段备注。
- **精确定时、通知栏直达与漏记补录**：基于系统本地闹钟（`AlarmManager`）准时触发并弹出高优先级悬浮通知，可在通知栏直接点击「已服药」或「跳过」，也可关闭通知转为「静默自动记录」；支持在应用启动、设备重启、时间/时区变更及应用更新后，按可配置窗口（`1 / 3 / 7 / 14 / 30` 天，默认 `7` 天）自动补录漏触发计划，并在设备解锁校验后恢复闹钟调度，保障锁屏阶段隐私与稳定性。
- **未确认态追踪与「当日备忘」智能合并**：计划到点生成记录后默认标为「未确认（可能未服药）」并显示警告角标，可在日记页、规律服药记录页或体验详情页随时确认、跳过删除或编辑；当同一自然日或药物活性窗口期（最长回溯至 `1008` 小时/42 天长洗脱期）内已存在常规体验时，规律服药记录将自动并入该体验（仅计算一次，不重复计入统计），并在规律服药记录页以「当日备忘」角标联动跳转。
- **残留药效交叉风控与备份同步**：在常规体验中检查药物相互作用时，结合临床洗脱窗口（覆盖去甲氟西汀、卡利哌嗪 DDCAR、不可逆 MAOI 等超长洗脱药物）自动回溯仍处于活性窗口期的历史及规律服药记录；支持从物质详情页一键「设为规律服药提醒」，并完整纳入 JSON 全量备份与恢复。

### 2. 数据安全、隐私保护与数据库完整性（v13.4.1 全量审计，v13.5.0 升级）
- **Room 数据库升级至 v11**：提供平滑迁移（含 `v9 → v10` 外键级联/唯一索引重建与 `v10 → v11` 自定义药物药代扩展列增量迁移），修复首次保存用药记录时的事务插入顺序及并发双击写库竞态。
- **换机与备份隐私隔离**：禁用 Android `allowBackup` 并在 `backup_rules.xml` 与 `data_extraction_rules.xml` 中全域排除本地数据库、偏好设置与外部存储；移除 `AD_ID` 权限；修复导出假成功、空文件/BOM 头解析边界，并在导入备份后自动恢复自定义单位映射、自定义耐受参数、个体化 PK 覆盖与规律服药闹钟。
- **安全外链与交互检查加固**：统一收口外部链接跳转校验，修复额外相互作用漏报与空指针/异常边界。

### 3. 药典数据扩充、耐受度与时程引擎校准
- **权威药典扩容**：当前内置 **474 种**精神活性物质、临床处方药及围术期麻醉镇静药（含 **229 种**具备真实人类临床 PK/ADME 与受体靶点图谱的条目，涵盖右美托咪定、丙泊酚、艾司氯胺酮、瑞芬太尼、阿芬太尼、七氟烷、异氟烷、利多卡因、罗哌卡因、硫喷妥钠、普萘洛尔、莱博雷生、纳曲酮等），共 **618 个**给药途径（其中 **550 个**含时程数据）；坚持无可靠人类实测数据的研究化合物不编造 PK 参数的原则。
- **耐受度引擎修复**：重构耐受模型匹配与时间解析逻辑（修复如 `4-AcO-DMT` 子串误命中 `DMT`、`"within 30 minutes"` 分钟单位与逗号小数解析问题），将回溯窗口动态扩展至自定义归零上限（`120 ~ 3650` 天），消除跨月「完全耐受缺失」误判、DST 跨天偏差与图表除零/NaN 异常，并将界面术语统一规范为「耐受」。
- **无障碍与多语言细节完善**：移除屏蔽搜索与日期/时间选择器语义树的无障碍遮罩，修复中文/公制单位（如 `片`、`µg`、`mL`）错误复数化问题，完善启动等待语言加载与动态上下文隔离。

---

## 基础核心能力（v13.0 ~ v13.1）

- **药效时程微积分叠加引擎**：采用梯形微积分连续叠加算法，真实呈现多次给药后的血药浓度蓄积与二次达峰过程，支持深浅主题自适应渲染。
- **耐受度时序甘特矩阵**：动态绘制双阶段（完全耐受 / 半耐受）渐变色带、最长耐药归零界线、日期微标与实时预计归零倒计时；对单次摄入不产生急性耐受的物质显式标注客观说明。
- **13 色系 × HSL 真彩色定制**：内置 `ColorFamily` 13 类主色相与 `100 ~ 900` 标准色阶（共 117 档精选色）及 HSL 单轨明暗微调滑块，同时向下兼容历史 51 种 `AdaptiveColor` 预设。
- **应用内即时多语言系统**：支持在「简体中文 / English / 跟随系统」间免重启热切换；中文模式展示标准中文通用名，英文模式无缝还原 WHO INN 国际非专利名称，并全量汉化数千条药物相互作用（DDI）警示。
- **全手势导航**：全面适配二级与三级页面顶栏返回、系统返回键与全面屏侧滑手势。

---

## 功能全景

| 模块 | 主要功能 |
| :--- | :--- |
| **日记 (Journal) & 规律服药** | 快捷用药录入、给药途径与剂量阶梯参考、Shulgin 体验量表评级、时间线笔记、双轨实况血药/代谢物叠加与 CYP DDI 对比曲线；规律服药计划配置、通知栏快捷确认/跳过、未确认状态管理与「当日备忘」智能合并。 |
| **物质 (Search)** | 收录 474 种物质与 618 个给药途径的剂量参考、五阶段时程参数、双轨临床 ADME 药代参数、受体亲和力靶点谱、相互作用警示及支持全套 PK 参数的自定义物质管理，支持一键创建规律服药提醒。 |
| **统计 (Stats)** | 物质伴侣频次图、用药日历、周/月/年多维度统计，以及基于真实与模拟记录推演的耐受度甘特矩阵与归零倒计时。 |
| **安全 (Safer)** | 化学试剂显色比对参考表、容积法稀释给药指南、给药途径生理防护与剂量分级说明。 |
| **设置 (Settings)** | 全量 JSON 明文导入导出（含规律服药、自定义耐受与个体化 PK 覆盖）、应用内检查更新、语言热切换、物质色彩深度定制、自定义单位管理与额外相互作用配置。 |

---

## 下载、安装与权限说明

从 [GitHub Releases 页面](https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus/releases/latest) 下载预编译的稳定版 APK：

- **系统要求**：Android 8.0 (API 26) 及以上（`targetSdk = 35`）
- **共存包名与桌面名称**：
  - 正式发布版：`com.isaakhanimann.journal.zh.plus`（可与旧版汉化版 `com.isaakhanimann.journal.zh` 及原版 Journal 同时共存安装）
  - 本地调试版：`com.isaakhanimann.journal.zh.plus.debug`（可与正式版、旧版汉化版及原版 Journal 同时共存安装）
  - 桌面图标名称为 `Journal+`，便于在设备桌面区分新旧版本的同时保持低调命名。
- **权限说明**（所有权限均仅在本地运行，绝不联网传输）：
  - `POST_NOTIFICATIONS`：用于规律服药到点弹窗提醒及通知栏快捷确认/跳过；
  - `SCHEDULE_EXACT_ALARM`：用于保障规律服药按设定时间准时触发，降低系统省电延迟；
  - `RECEIVE_BOOT_COMPLETED`：用于在设备重启、系统时间/时区变更或应用更新后，经解锁态校验恢复本地提醒计划。

---

## 本地编译与测试

### 环境要求
- JDK 17
- Android SDK 35
- Gradle 8.9（通过项目自带 `./gradlew` Wrapper 自动使用）

### 常用命令
```bash
git clone https://github.com/yuanyuan2233/PsychonautWiki-Journal-Plus.git
cd PsychonautWiki-Journal-Plus

# 运行本地 JVM 单元测试
./gradlew testDebugUnitTest

# 本地调试构建（包名为 com.isaakhanimann.journal.zh.plus.debug，可与正式版及旧版共存）
./gradlew assembleDebug

# 生产环境 Release 构建（启用 R8 混淆、资源压缩与独立包名 .zh.plus）
./gradlew assembleRelease
```
- **签名配置**：本地执行 `assembleRelease` 时可通过 Gradle 属性或环境变量提供 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`（未提供时将输出未签名安装包 `app/build/outputs/apk/release/app-release-unsigned.apk`；GitHub Actions 打 tag 发布流水线会强制校验签名与 `SHA256SUMS.txt`）。
- **构建产物优化**：打包期已通过 `androidResources.ignoreAssetsPatterns` 自动排除离线字典脚本文件（`Substances.original.json`、`substances_zh_dict.json`），保持安装包精简。

---

## 致谢与数据来源

本项目的数据整理、药理模型与汉化资料受益于以下开源项目与知识库：

- **Isaak Hanimann**：原版 PsychonautWiki Journal 的架构设计与核心代码作者；
- **PsychonautWiki**：全球同行评议物质药理学数据与开放 API；
- **FreeODwiki 社群**：中文物质学名考证、命名规范化及中文减害资料；
- **TripSit**：开源物质数据库与多物质相互作用对照表（TripSit/drugs）；
- **国家药典委员会与国家卫生健康委**：《中华人民共和国药典》、《国家基本药物目录》药品通用名称规范。

---

## 减害与免责声明

1. 本应用旨在倡导风险减害（Harm Reduction）理念，所载药理参数、剂量区间及相互作用数据均整理自公开学术文献与同行评议研究，仅供科学参考与个人健康记录，不作为临床医疗诊断或处方凭证。
2. 本项目坚决反对任何形式的违法涉药或滥用行为。
3. 遇有急性药物反应或医疗意外，请立即拨打当地急救电话寻求专业医疗救助。

---

## 许可证

本项目依据 [GNU General Public License v3.0](LICENSE) 持续开源：
- 原版版权所有 (C) 2022–2024 Isaak Hanimann.
- PsychonautWiki Journal Plus 维护 (C) 2026 yuanyuan2233 与开源贡献者.

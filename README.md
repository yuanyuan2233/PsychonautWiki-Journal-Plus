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
- **完全开源**：应用代码与药典清洗工具全量公开透明；
- **离线与隐私优先**：不包含任何网络请求、遥测统计或广告追踪代码，已显式关闭系统自动云备份与换机数据迁移，全部个人数据仅保存在设备本地；
- **科学与药理本土化**：结合临床药理学与国家药典规范，对物质词典、药代时程与减害参考体系进行持续校准。

---

## 核心特性与近期更新（v13.3 ~ v13.4.1）

### 1. 规律服药与智能提醒系统（v13.3.0 新增，v13.4.1 加固）
- **五种周期调度模型**：支持**每日多时段**、**每周指定日**、**每隔 N 天**、**每隔 N 小时/分**以及**周期循环（用 X 天停 Y 天）**；支持多时段独立剂量、估算剂量与标准差（±）、关联自定义单位、服用人标记、起止日期、总次数上限及单时段备注。
- **精确定时、通知栏直达与漏记补录**：基于系统本地闹钟（`AlarmManager`）准时触发并弹出高优先级悬浮通知，可在通知栏直接点击「已服药」或「跳过」，也可关闭通知转为「静默自动记录」；支持在应用启动、设备重启、时间/时区变更及应用更新后，按可配置窗口（`1 / 3 / 7 / 14 / 30` 天，默认 `7` 天）自动补录漏触发计划，并在设备解锁校验后恢复闹钟调度，保障锁屏阶段隐私与稳定性。
- **未确认态追踪与「当日备忘」智能合并**：计划到点生成记录后默认标为「未确认（可能未服药）」并显示警告角标，可在日记页、规律服药记录页或体验详情页随时确认、跳过删除或编辑；当同一自然日或药物活性窗口期（按自定义半衰期 → 内置半衰期 → 给药途径总时长依次回退，钳制在 `12 ~ 48` 小时）内已存在常规体验时，规律服药记录将自动并入该体验（仅计算一次，不重复计入统计），并在规律服药记录页以「当日备忘」角标联动跳转。
- **残留药效交叉风控与备份同步**：在常规体验中检查药物相互作用时，自动回溯最多 `48` 小时内仍处于活性窗口期的规律服药记录，防止遗漏日常用药与临时用药间的相互作用风险；支持从物质详情页一键「设为规律服药提醒」，并完整纳入 JSON 全量备份与恢复。

### 2. 数据安全、隐私保护与数据库完整性（v13.4.1 全量审计）
- **Room 数据库升级至 v10**：提供平滑自动迁移（含 `v9 → v10` 迁移），补齐核心业务表外键约束、级联清理与唯一索引，修复首次保存用药记录时的事务插入顺序及并发双击写库竞态。
- **换机与备份隐私隔离**：禁用 Android `allowBackup` 并在 `backup_rules.xml` 与 `data_extraction_rules.xml` 中全域排除本地数据库、偏好设置与外部存储；移除 `AD_ID` 权限；修复导出假成功、空文件/BOM 头解析边界，并在导入备份后自动恢复自定义单位映射、自定义耐受参数与规律服药闹钟。
- **安全外链与交互检查加固**：统一收口外部链接跳转校验，修复额外相互作用漏报与空指针/异常边界。

### 3. 药典数据清洗、耐受度与时程引擎校准
- **药典数据质检**：当前内置 **451 种**精神活性物质与临床处方药、**590 个**给药途径（ROA，其中 **522 个**含时程数据、**521 个**具备完整五阶段时程参数）；清洗高危别名污染、阈值相等、丢失给药途径、分类错误、时程矛盾及缺失中文名问题。
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
| **日记 (Journal) & 规律服药** | 快捷用药录入、给药途径与剂量阶梯参考、Shulgin 体验量表评级、时间线笔记、药代微积分叠加曲线；规律服药计划配置、通知栏快捷确认/跳过、未确认状态管理与「当日备忘」智能合并。 |
| **物质 (Search)** | 收录 451 种物质与 590 个给药途径的剂量参考、五阶段时程参数、临床 ADME 药代参数、相互作用警示及自定义物质管理，支持一键创建规律服药提醒。 |
| **统计 (Stats)** | 物质伴侣频次图、用药日历、周/月/年多维度统计，以及基于真实与模拟记录推演的耐受度甘特矩阵与归零倒计时。 |
| **安全 (Safer)** | 化学试剂显色比对参考表、容积法稀释给药指南、给药途径生理防护与剂量分级说明。 |
| **设置 (Settings)** | 100% 离线运行、全量 JSON 明文导入导出（含规律服药与自定义耐受设置）、应用内语言热切换、物质色彩深度定制、自定义单位管理与额外相互作用配置。 |

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

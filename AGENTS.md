# AGENTS.md — Blacks-BlackBox 执行 AI 指南

> 本项目是 **元萝卜 / Meta Wolf（Mundo，`top.bienvenido.saas.i18n`，闭源）的开源备胎（平替）**。
> 定位：给元萝卜用户提供可自托管、可审计、跨设备迁移数据的替代品——单页应用列表、备份/还原、以及**元萝卜备份导入**（用户带着 `Mundo-Backup.zip` + 助记词即可迁入）。
> 元萝卜基准版本：V1.0.29（2026-08-27 release，格式逆向结论见 `docs/ylb-format/RESEARCH.md` 第 8 节）。

## 必读文档

| 文档 | 内容 |
|---|---|
| `docs/plan.md` | 总改造蓝图（需求 R1-R4、Phase 1-3、测试用例编号 TC-U-xx / TC-I-xx / TC-UI-xx、里程碑） |
| `docs/ylb-format/RESEARCH.md` | 元萝卜格式调研 + V1.0.29 静态逆向实证（F1-F10）与导入实现规范 |
| `Bcore/src/main/java/top/niunaijun/blackbox/backup/BackupManifest.kt` | 自有备份格式 blackbox-backup v1 的唯一权威定义 |

## 仓库结构

```
app/                       宿主 UI（包名 com.onebitmonochrome.blacksbbox，Kotlin + ViewBinding + MVVM）
  view/main/               MainActivity（单页容器）、WelcomeActivity、ShortcutActivity
  view/apps/               全局应用列表 Fragment（聚合所有空间 userId）
  view/backup/             BackupActivity / RestoreActivity（备份还原 UI）
  view/list/               ListActivity（添加应用 + 空间选择）
  data/AppsRepository.kt   数据仓库；AppsSortKeys（全局排序键纯函数，可单测）
Bcore/                     虚拟化引擎（包名 top.niunaijun.blackbox，Java 为主，Kotlin 允许）
  backup/                  备份引擎：BackupManager（门面）/Engine/Archive/Crypto/Manifest/HostPrefs/confmerge
  backup/ylb/              元萝卜备份导入器（YlbBackupParser/Bip39/Bip32/PathMapper）
  core/env/BEnvironment.java  虚拟数据根布局（备份的物理基础，改动需同步备份逻辑）
blackbox-gallery-stub/ black-reflection/ compiler/   引擎配套模块
.github/workflows/build.yml   CI（唯一编译通道，见下）
```

## 构建与 CI（硬性：禁止本地编译）

本仓库**一律通过 GitHub Actions 编译**，不要在本地跑 `gradlew`。

- 工具链：JDK 21（temurin）、Gradle 8.13、AGP 8.13.2、Kotlin 1.9.23、NDK 29.0.13846066（CI 自动装）
- SDK 目标：compileSdk 35 / targetSdk 28 / minSdk 21；versionCode/versionName 定义在根 `build.gradle` 的 ext
- push/PR 自动触发：`assembleDebug` + `testDebugUnitTest`（分支 `main` 与 `feature/**`）
- instrumented 测试（模拟器 API 34）：`workflow_dispatch` 手动触发，`reactivecircus/android-emulator-runner`
- 仓库源顺序：google()/mavenCentral()/jitpack 优先，aliyun 镜像仅兜底（海外 runner 访问 aliyun 会 502，勿改回）

## 备份格式速查（blackbox-backup v1）

```
zip 根：manifest.json + manifest.sha256 + host_prefs.json(可选)
apps/{escaped_pkg}/：base.apk、splits/、data_user/、data_user_de/、ext_data/、ext_obb/、entry.json
```
- 条目级可选加密：AES-256-GCM（PBKDF2WithHmacSHA256，16B 盐，120000 次）；manifest 恒明文（还原预览无需密码）
- 包名转义无碰撞（`escapePkg`）；symlink 记录进 manifest 不打包内容；打包/解包必须流式（8KB+ chunk，红线）
- `format_version > 1` 显性报错；`manifest.sha256` 校验失败抛 VerifyException

## 元萝卜导入速查（详细见 RESEARCH.md §8）

- 容器：zip4j WinZip AES zip；**zip 密码 = 身份钱包私钥 hex**
- 私钥派生：BIP39（PBKDF2-HMAC-SHA512/2048/`mnemonic`+passphrase 盐）→ BIP32 → 候选路径 `m/44'/60'/{slot}'/0/0` 运行时 HMAC 试错命中
- 条目路径 = 元萝卜宿主 dataDir 相对路径；`app_foza_apks/<pkg>/` → 本引擎 `data/user/{uid}/{pkg}/`
- clean-room 合规：只实现格式事实（RESEARCH.md 为准），不复制反编译代码/资源/词表

## 执行纪律（给所有改动本仓库的 AI）

1. **测试随功能走**：改引擎/UI 必须带测试（见 plan.md 第 7 节用例编号），纯 JVM 优先；测试禁 import android.*
2. **失败显性**：异常路径必须有用户可见文案 + log；TAG 规范 `Backup`/`Restore`/`YlbImport`；禁止静默吞异常
3. **引擎 API 优先**：安装/卸载/停止/启动一律走 `BlackBoxCore` 公开 API，禁止自己扫目录绕过引擎
4. **不破坏现有功能**：虚拟定位、设备伪装、画廊、冻结、Xposed 模块入口全部保留；`MainActivity.checkVpnPermission()` 中 SDK≥34 强制禁用 VPN 是刻意设计，勿动
5. **权限红线**：备份走 SAF，不新增权限，不依赖 MANAGE_EXTERNAL_STORAGE
6. **性能红线**：任何"整文件读进内存"的备份实现都算失败（TC-I-05 拦截）
7. **i18n**：所有用户可见文案进 `values/`+`values-zh-rCN/`+`values-zh-rTW/` 三份，键集必须对齐；禁止硬编码 UI 英文（Log 除外）
8. **代码风格**：跟随现状（try-catch + Log.e 习惯、ViewBinding、MVVM）；新文件带 Apache-2.0 头；Kotlin 在 app 模块，Bcore 新代码优先 Kotlin（与 backup 包一致）
9. **团队协作模式**：多代理并行时按目录划分文件所有权（互斥），集成者统一 commit/push 与 CI 修复；单代理时直接改

## 元萝卜对齐清单（作为"备胎"的产品差距追踪）

- [x] 单页应用列表（对应元萝卜首页体验）
- [x] 多选备份 → 单个 zip（SAF 导出，可选 AES-256-GCM 密码）
- [x] 跨设备还原（APK + 数据 + 排序/备注/冻结标记）
- [x] 元萝卜 `Mundo-Backup.zip` 导入（助记词 + 密码 → 迁入本引擎空间；BIP32/BIP39 过官方向量）
- [x] 无存储权限（对齐元萝卜：全文件访问弹窗已移除，全部走 SAF/应用私有目录）
- [x] 简繁中文全量本地化（三语键集对齐）
- [x] CI 常态绿：assembleDebug + testDebugUnitTest（push/PR 自动触发）
- [ ] instrumented 测试在 CI 模拟器常态跑（当前 workflow_dispatch 手动触发）
- [ ] 元萝卜真机样本回归（RESEARCH.md §8.2 步骤 3-5 的运行时验证：拿样本跑候选派生命中）
- [ ] 备份调度/自动备份（元萝卜未提供，列为差异化项）

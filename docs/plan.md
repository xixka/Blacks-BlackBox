# Blacks-BlackBox 改造计划：单页应用列表 + 备份还原 + 元萝卜备份导入

> 本文档是给执行 AI 的完整实施方案。基于对仓库源码的实际分析编写，包含现状代码地图、改造方案、数据格式规范、测试用例清单和验收标准。
>
> - 目标仓库：https://github.com/xixka/Blacks-BlackBox （Apache-2.0，BlackBox 系引擎，Kotlin + Java 混合）
> - 参考产品：元萝卜 / Meta Wolf（包名 `top.bienvenido.saas.i18n`，闭源）
> - 硬性要求：所有功能必须附带测试用例（见第 7 节），无测试的 PR 不予合入。

---

## 1. 目标总览

| 编号 | 需求 | 验收标准 |
|---|---|---|
| R1 | 应用列表改为单页展示（元萝卜风格） | 打开 App 即见全部已安装分身，无论属于哪个虚拟空间（userId），一页内滚动浏览，无需左右翻页 |
| R2 | 备份功能（对齐元萝卜） | 可多选应用，导出单个 zip 文件到用户选择的目录；包含应用数据与 APK；跨设备可还原 |
| R3 | 还原功能 | 从本应用生成的备份 zip 还原应用（自动装 APK + 恢复数据），应用回到备份时的登录态 |
| R4 | 元萝卜备份导入 | 能识别并导入元萝卜生成的 `backup.zip`（含加密格式的处理策略，见 6.3） |
| R5 | 测试 | 单元测试 + Instrumented 测试 + UI 测试全部通过；`gradlew test` 与 `gradlew connectedDebugAndroidTest` 绿 |

---

## 2. 现状代码地图（执行前必读）

以下路径均相对仓库根目录。app 模块包名：`com.onebitmonochrome.blacksbbox`，引擎模块 `Bcore` 包名：`top.niunaijun.blackbox`。

### 2.1 UI 结构（需要改造的部分）

```
app/src/main/java/com/onebitmonochrome/blacksbbox/
├── view/main/
│   ├── MainActivity.kt        ← 改造主目标：ViewPager2 多空间分页
│   ├── ViewPagerAdapter.kt    ← 将被移除/废弃
│   ├── WelcomeActivity.kt     ← 启动页（保留）
│   └── ShortcutActivity.kt    ← 桌面快捷方式入口（保留，注意单页改造后的路由）
├── view/apps/                  ← 单个空间的应用列表 Fragment（改造为全局列表）
│   ├── AppsFragment.kt        ← newInstance(userId)，改为无参/全局模式
│   ├── AppsViewModel.kt
│   ├── AppsAdapter.kt
│   └── AppsTouchCallBack.kt   ← 拖拽排序，单页后仍按 (userId, pkg) 维度排序
├── view/list/                  ← "添加应用"列表页（保留，作为安装入口）
│   ├── ListActivity.kt        ← 接收 userID extra；需改为空间选择模式
│   └── ListAdapter.kt
├── view/setting/SettingActivity.kt  ← 设置页（备份还原入口加在 Toolbar 菜单或设置页）
├── data/AppsRepository.kt      ← 核心数据仓库（见 2.3）
└── app/AppManager.kt           ← mRemarkSharedPreferences（空间备注）等全局状态
```

现状：`MainActivity.initViewPager()` 为每个虚拟用户（`BlackBoxCore.get().users`）创建一个 `AppsFragment`，末尾追加一个"新建空间"页，用 `dotsIndicator` 翻页。FAB 点击进入 `ListActivity` 安装应用到当前空间。

### 2.2 引擎数据存储布局（备份的物理基础）

来源：`Bcore/src/main/java/top/niunaijun/blackbox/core/env/BEnvironment.java`。
设 `VR = {宿主 filesDir}/blackbox`（内部存储，`sVirtualRoot`），`EVR = {宿主 externalFilesDir}/blackbox`（`sExternalVirtualRoot`）：

| 路径 | 内容 | 备份策略 |
|---|---|---|
| `VR/system/` | `user.conf`（空间列表）、`accounts.conf`、`uid.conf`、`shared-user.conf`、`xposed-module.conf`、`fake-location.conf` | 选择性备份（只取与所备份应用相关的条目） |
| `VR/data/user/{userId}/{pkg}/` | 应用私有数据：`files/`、`cache/`、`databases/`、`shared_prefs/`、`lib/` | **必备份**（可选项排除 cache） |
| `VR/data/user_de/{userId}/{pkg}/` | device-encrypted 数据 | **必备份** |
| `VR/data/app/{pkg}/` | `base.apk`、`splits/`、`lib/`（应用本体） | 备份 base.apk + splits（lib 可选，可由 APK 重建） |
| `EVR/storage/emulated/{userId}/Android/data/{pkg}/` | 应用外部私有数据 | **必备份** |
| `EVR/storage/emulated/{userId}/Android/obb/{pkg}/` | OBB 扩展包 | 必备份（游戏场景） |
| `VR/proc/`、`VR/cache/` | 引擎运行时临时数据 | 不备份 |

宿主自身的 SharedPreferences 中的关联状态（`AppManager.mRemarkSharedPreferences` 的 `Remark$userId`、`AppsRepository` 的排序记录、`AppFreezeManager` 的冻结名单）也需纳入备份，见 5.2 manifest 的 `host_prefs` 字段。

### 2.3 关键引擎 API（实现时直接调用，勿绕过引擎自己扫目录安装）

- `BlackBoxCore.get().users` — 空间列表
- `BlackBoxCore.getInstalledApplications(flags, userId)` — 指定空间已装应用
- `BlackBoxCore.get().installAPK(source, userId)` / `AppsRepository.installApk(source, userId, resultLiveData)` — 安装
- `BlackBoxCore.get().launchApk(pkg, userId)` — 启动
- `AppsRepository.unInstall(pkg, userId, result)` — 卸载
- `BlackBoxCore.get().stopApk(pkg, userId)` 或 `killAll`（若存在）— 备份/还原前必须停止目标应用进程，防止 SQLite 写坏
- `BPackageManager.getInstalledPackages(userId)` — 取版本号/签名（写入 manifest）

### 2.4 构建与测试命令

```bash
./gradlew assembleDebug            # 构建（minSdk 21 / targetSdk 28 / compileSdk 35）
./gradlew :app:testDebugUnitTest   # 单元测试
./gradlew :app:connectedDebugAndroidTest  # Instrumented 测试（需连接设备/模拟器）
```

---

## 3. Phase 1 —— 单页应用列表（R1）

### 3.1 设计

- 删除 ViewPager 多页结构：`MainActivity` 中移除 `viewPager`、`dotsIndicator`、`ViewPagerAdapter`、`fragmentList` 及 `initViewPager()` 全部逻辑。
- 布局 `activity_main.xml` 改为：Toolbar + 单个 `FrameLayout`（承载改造后的 `AppsFragment`）+ FAB（添加应用）+ 冻结 FAB（保留现有 `AppFreezeManager` 功能）。
- `AppsFragment` 改造为全局模式：
  - `newInstance()` 不再接收 userId；内部聚合所有空间。
  - 列表项数据结构 `AppInfo` 增加 `userId` 字段（或使用 `Pair<AppInfo, Int>`），列表项 UI 在应用名下方以小标签显示空间备注（`Remark$userId`，如 "空间 2"），同包名多开时用于区分。
  - 点击 → `launchApk(pkg, itemUserId)`；长按菜单 → 备份/还原/卸载/清除数据/创建快捷方式/删除（沿用现有菜单项，加上"备份此应用"入口）。
- `AppsRepository` 新增：
  ```kotlin
  fun getAllInstalledAppList(appsLiveData: MutableLiveData<List<AppInfo>>) {
      viewModelScope.launch(Dispatchers.IO) {
          val users = BlackBoxCore.get().users
          val all = users.flatMap { u ->
              blackBoxCore.getInstalledApplications(0, u.id).map { it.toAppInfo(u.id) }
          }
          withContext(Dispatchers.Main) { appsLiveData.value = all }
      }
  }
  ```
- 拖拽排序改为全局顺序：排序键持久化为 `pkg@userId` 列表（兼容现有 `updateApkOrder`，仅扩展键格式；迁移时保留旧格式读取兼容）。
- FAB → `ListActivity`：`ListActivity` 顶部新增空间选择（ChipGroup 或下拉，默认最近使用的空间；仅一个空间时隐藏选择器）。安装完成后回主页面刷新全局列表。
- `scanUser()` 逻辑改为刷新全局列表；空态视图（无任何分身时显示引导文案 + 大添加按钮，元萝卜同款体验）。
- Toolbar 副标题（空间备注点击重命名）迁移到"长按列表项 → 空间管理"对话框或设置页，主界面不再依赖单一 `currentUser`；`FakeManagerActivity` / `DeviceSpoofingActivity` / `BlackBoxGalleryActivity` 接收 `userID` 的入口改为在列表长按菜单中选择目标空间。

### 3.2 涉及文件

新增：`view/apps/AllAppsViewModel.kt`（或改造 `AppsViewModel.kt`）、`bean/SpaceLabel.kt`。
修改：`MainActivity.kt`、`AppsFragment.kt`、`AppsAdapter.kt`、`AppsRepository.kt`、`ListActivity.kt`、`res/layout/activity_main.xml`、`res/layout/item_app.xml`。
删除：`ViewPagerAdapter.kt`（确认无引用后）。

### 3.3 注意事项

- `ShortcutActivity`（桌面快捷方式直接拉起分身）依赖 userId 路由，单页改造后快捷方式仍必须按 `(pkg, userId)` 正确启动——回归测试覆盖 TC-UI-04。
- 不要动 `BlackBoxCore.onBeforeMainActivityOnCreate/onAfterMainActivityOnCreate` 调用与 VPN 权限逻辑（`checkVpnPermission`，SDK>=34 自动禁用 VPN 的行为是刻意设计，保留）。

---

## 4. Phase 2 —— 备份引擎与自有备份格式（R2/R3）

### 4.1 新模块结构

```
Bcore/src/main/java/top/niunaijun/blackbox/backup/
├── BackupManager.kt          // 对外门面：backup() / restore() / listBackupables()
├── BackupManifest.kt         // manifest 数据类 + JSON 序列化
├── BackupArchive.kt          // zip 打包/解包（流式，进度回调）
├── BackupCrypto.kt           // 可选加密（AES-256-GCM，密码派生 PBKDF2）
├── ProgressListener.kt       // interface OnBackupProgress(current, total, stage)
└── exception/                // BackupException / InsufficientSpaceException / VerifyException
app/src/main/java/.../view/backup/
├── BackupActivity.kt         // 应用多选列表 + [备份] 按钮
├── RestoreActivity.kt        // 选择 zip → 预览内容 → [还原]
└── BackupViewModel.kt
```

### 4.2 备份流程（backup）

1. 前置检查：对每个选中应用调用 `stopApk`（等待进程退出，超时 3s 强制标记"数据可能不一致"并警告用户）；磁盘空间检查（预估 = 各数据目录大小之和 × 1.1）。
2. 逐应用打包（顺序执行，进度回调驱动 UI 进度条）：
   - `data/user/{uid}/{pkg}/**`（排除 `cache/**`，manifest 里记 `exclude_cache=true`）
   - `data/user_de/{uid}/{pkg}/**`
   - `data/app/{pkg}/base.apk` + `splits/**`
   - `EVR/.../Android/data/{pkg}/**`、`Android/obb/{pkg}/**`（存在才写）
   - 记录符号链接：引擎数据目录中的 symlink（`lib/` 下常见）需在 manifest 的 `symlinks` 数组记录 `target`，解包时重建，**不得**当普通文件复制。
3. 写入 `manifest.json`（规范见 5.1）与 `manifest.sha256`。
4. 若用户设置密码：对整个 zip 做 AES-256-GCM 加密（实现方式：逐 entry 加密，zip 头保留明文 entry 名，内容为密文，manifest 里 `encrypted=true` 记录盐值/nonce 方案；或采用 ZIP 标准加密字段——二选一，实现后写入 5.1 的"实现记录"）。
5. 产出文件名：`blackbox_backup_yyyyMMdd_HHmmss.zip`，通过 `Storage Access Framework`（`ACTION_CREATE_DOCUMENT`）让用户选位置；**禁止**依赖 `MANAGE_EXTERNAL_STORAGE` 直接写公共目录（现状 App 已有全文件权限，但备份功能必须走 SAF，为后续兼容 Android 15+ 做准备）。

### 4.3 还原流程（restore）

1. 读 zip → 解析 manifest → 版本兼容检查（`format_version` 大于当前支持版本则明确报错，不静默）。
2. 预览页展示：应用列表（图标/包名/版本/大小）、备份时间、来源机型、是否含 APK。
3. 逐应用：
   - 若备份含 APK：直接从 zip 安装（`installAPK`，先校验签名与已装实例一致，不一致时弹窗确认覆盖）。
   - 若不含 APK 且目标未安装：标记失败原因"缺少 APK，请先安装原应用"（元萝卜同样要求先传原 APK，行为对齐）。
   - 停止运行中实例 → 解包数据目录覆盖（先清空目标 `data/user/{uid}/{pkg}` 再解压，保证幂等）→ 恢复 `system/*.conf` 相关条目（合并策略：以备份为准覆盖对应包名的记录条目，不整文件覆盖，避免毁掉其他空间数据）→ 恢复宿主 prefs 中该应用的排序/备注/冻结标记。
   - 重建符号链接、恢复文件时间戳。
4. 完成后刷新单页列表，输出结果报告（成功 N / 失败 M + 原因）。

### 4.4 元数据细节

- manifest 记录每应用 `version_name`/`version_code`/`signing_cert_sha256`；还原到不同版本 APK 时警告"数据来自 vX，目标 vY，可能异常"。
- SQLite 一致性：备份时若目标进程无法停止（有前台服务），在 manifest 标 `possibly_inconsistent=true`，还原后首次启动由引擎的 `DexFileRecovery` 类机制兜底（仅提示，不阻断）。

---

## 5. 备份文件格式规范（blackbox-backup v1）

### 5.1 zip 内部结构

```
blackbox_backup_xxx.zip
├── manifest.json
├── manifest.sha256                  // manifest.json 内容的 sha256，防篡改/截断
├── apps/
│   └── {escaped_pkg}/               // 包名中非法字符替换为 '_'，manifest 记原始名
│       ├── base.apk
│       ├── splits/...
│       ├── data_user/...            // 对应 data/user/{uid}/{pkg}
│       ├── data_user_de/...         // 对应 data/user_de/{uid}/{pkg}
│       ├── ext_data/...             // Android/data
│       ├── ext_obb/...              // Android/obb
│       └── entry.json               // 单应用元数据（uid、大小、文件数、sha256 清单可选）
└── host_prefs.json                  // 排序/备注/冻结标记
```

`manifest.json` 字段（Kotlin data class，Gson/kotlinx-serialization 序列化）：

```json
{
  "format_version": 1,
  "app": "Blacks-BlackBox",
  "app_version_code": 40701,
  "created_at": "2026-11-22T19:00:00+08:00",
  "device": {"model": "Pixel 8", "android_sdk": 36},
  "encrypted": false,
  "kdf": null,
  "apps": [{
    "package_name": "com.tencent.mm",
    "user_id": 0,
    "version_name": "8.0.99",
    "version_code": 2960,
    "signing_cert_sha256": "ab12...",
    "include_apk": true,
    "exclude_cache": true,
    "possibly_inconsistent": false,
    "size_bytes": 123456789,
    "file_count": 4096
  }],
  "symlinks": [{"path": "apps/com.tencent.mm/data_user/lib/libx.so", "target": "../base.apk"}]
}
```

### 5.2 兼容性规则

- 读入时 `format_version` 未知 → 报错并列出支持的版本。
- v1 → v2 演进必须保持向后可读（新字段全部 optional）。

---

## 6. Phase 3 —— 元萝卜备份导入（R4）

### 6.1 已知信息（来自真机用户实测，写入文档供调研使用）

- 元萝卜 = Meta Wolf，包名 `top.bienvenido.saas.i18n`，免费、无 root、带 Xposed 模块加载。
- 备份流程：App 内"资产"页创建/导入**虚拟身份**（12 个英文单词助记词，BIP39 风格，空格分隔，可设密码可不设）→"配置"→"备份与还原"→ 选择身份 → 多选应用 → 选择输出目录 → 生成 **backup.zip**。
- 还原流程：另一台手机导入**相同助记词身份** → 先安装原应用 APK → 再从 backup.zip 还原 → 应用保持登录态。
- 强信号：备份与助记词身份绑定（换身份无法还原），说明 **backup.zip 的内容大概率用助记词派生的密钥加密**（Web3 钱包式设计，可能 BIP39→BIP32/BIP44 派生或 PBKDF2(mnemonic)）。
- 元萝卜虚拟化的数据目录布局未知（闭源），需要 Phase 3.1 调研确定目录映射。

### 6.2 Phase 3.1：格式调研（编码前的必做任务，产出《元萝卜格式调研报告》）

1. 准备一台测试机 + 元萝卜 APK + 固定的测试助记词（例如 `abandon abandon ... about` BIP39 全零向量）。
2. 克隆安装一个简单测试应用（建议：自带 SQLite 的演示 app），写入可识别的标记数据（特定文件名/数据库表/一行记录）。
3. 生成 backup.zip（分别生成：不设密码、设密码、单应用、多应用 四份样本）。
4. 分析：
   - `unzip -l` 看 entry 结构（是否可列名 → 决定是"zip entry 加密"还是"整包加密文件"）。
   - 用测试助记词尝试派生密钥：`PBKDF2(mnemonic, salt)`、BIP39 seed、BIP32 派生 m/44'/60'/0'/0/0 等常见路径，key 长度 16/32 字节，尝试 AES-GCM/CBC 解密首个 entry。
   - 若 entry 结构可读，记录目录约定（其虚拟数据根如何映射到我们的 `data/user/{uid}/{pkg}`）。
   - 抓包/逆向辅助（可选）：mt 管理器查壳、frida hook 其 `Cipher.init` 拿到运行时密钥，反推 KDF。
5. 产出：格式说明 + 样本文件（存 `docs/ylb-format/`），确定"可完整导入 / 部分导入 / 不可导入"三档结论。

### 6.3 导入实现与降级策略

- `YlbBackupParser`（放 `backup/ylb/` 包）：
  - `sniff(file): YlbFormatInfo` — 识别是否元萝卜备份（entry 特征/魔术字节，依据 6.2 报告）。
  - `parse(file, mnemonic: CharArray, password: CharArray?)` — 解密 + 解析。
  - `convertToV1(parsed): blackbox-backup v1 内存结构` — 目录映射转换，然后**复用 Phase 2 的还原管线**执行导入。导入时目标空间由用户在 UI 上选择（元萝卜的"身份"概念映射为我们的 userId，默认新建空间避免污染现有数据）。
- 降级策略（三档，UI 明确提示）：
  - 完整导入：解密成功 + 目录映射明确。
  - 部分导入：只能取出明文部分（如 APK）→ 提示"仅 APK 可导入"。
  - 不可导入：KDF 无法复现（加固/混淆）→ 报错并说明原因，绝不静默失败。
- 若 6.2 结论为"不可导入"，允许 R4 交付形态退化为：检测 + 明确报错 + 完整调研报告（记录失败原因与证据），并在 README 声明限制。**但调研阶段必须做完，不得跳过。**
- 用户交互：还原页选择 zip 后自动 sniff，若为元萝卜格式弹出"检测到元萝卜备份，请输入 12 词助记词（和密码，如有）"对话框。

### 6.4 合规提示

元萝卜为闭源商业软件。实现导入属于互操作目的的格式适配，代码中不得复制其任何反编译代码/资源，仅做格式分析（clean-room 原则：调研产出格式文档，实现按文档写）。

---

## 7. 测试用例（硬性要求）

### 7.1 单元测试（`app/src/test/` + `Bcore/src/test/`，JUnit4 + Robolectric 按需）

| 编号 | 用例 | 断言要点 |
|---|---|---|
| TC-U-01 | `BackupManifest` 序列化往返 | data class → JSON → data class，字段逐一相等（含空列表/null 边界） |
| TC-U-02 | manifest 版本拒绝 | `format_version=999` 抛 `UnsupportedFormatException`，错误信息含版本号 |
| TC-U-03 | zip 打包完整性 | 对临时目录打包含 symlink + 空文件 + 中文文件名 + 1MB 二进制；解包后 sha256 全部一致；symlink 重建为链接而非拷贝 |
| TC-U-04 | 打包排除 cache | `data_user/cache/x` 不在 zip 中，manifest `exclude_cache=true` |
| TC-U-05 | 磁盘空间预检 | 模拟剩余空间不足 → `InsufficientSpaceException` |
| TC-U-06 | 还原幂等性 | 同一 zip 连续还原 2 次，结果目录树 sha256 一致（清空-覆盖逻辑正确） |
| TC-U-07 | 密码加密/解密往返 | 加密 zip + 正确密码 → 解开；错误密码 → 首块解密失败即报 `WrongPasswordException`（GCM 认证标签） |
| TC-U-08 | `YlbBackupParser.sniff` | 元萝卜样本（6.2 产物）→ 识别；自有 v1 备份 → 不误判；随机 zip → 不误判 |
| TC-U-09 | 元萝卜密钥派生 | 用 6.2 确定的 KDF，测试助记词 → 密钥向量固定值比对（防回归） |
| TC-U-10 | 包名转义 | `com.a.b-c` 与 `com.a.b_c` 转义后不冲突（转义表无碰撞），manifest 记录原始名 |
| TC-U-11 | `getAllInstalledAppList` | 2 空间各装 2 应用（mock BlackBoxCore）→ 返回 4 条，每条带正确 userId |
| TC-U-12 | 排序持久化迁移 | 旧格式排序键读取兼容；新 `pkg@uid` 键写入读回一致 |
| TC-U-13 | system conf 条目合并 | 备份的 `user.conf` 条目合并进现有 conf，其他空间条目不受影响（字符串级断言） |
| TC-U-14 | 签名校验 | manifest `signing_cert_sha256` 与 APK 实际签名不一致 → 警告标志位 |

### 7.2 Instrumented 测试（`app/src/androidTest/`，真机/模拟器）

| 编号 | 用例 | 步骤与断言 |
|---|---|---|
| TC-I-01 | 端到端备份-卸载-还原（登录态） | 安装测试 APK 到空间0 → 写入标记文件+SQLite 行 → 备份 → `unInstall` → 从备份还原 → 断言：标记文件存在、SQLite 查询返回该行、`getInstalledApplications` 包含该包 |
| TC-I-02 | 还原含 APK 的跨设备模拟 | 备份 zip 复制到第二存储位 → 清除应用数据目录 → 还原 → 应用可 `launchApk` 启动且不 crash（等待 5s 进程存活） |
| TC-I-03 | 无 APK 备份还原 | manifest `include_apk=false`，未装时还原 → 明确失败原因"缺少 APK"；先手动装 APK 再还原 → 成功 |
| TC-I-04 | 运行中进程备份 | 应用前台运行时直接备份 → manifest `possibly_inconsistent=true`，备份仍完成不 crash |
| TC-I-05 | 大体积备份 | 300MB 数据（模拟文件）备份+还原，内存占用 < 256MB（流式实现验证），进程不 OOM |
| TC-I-06 | OBB 备份 | 放置 obb 文件 → 备份 → 还原 → obb 路径内容一致 |
| TC-I-07 | 元萝卜真机导入（依赖 6.2 样本） | 用测试助记词导入样本 zip → 断言数据落到目标空间目录且测试应用启动后标记数据可读 |
| TC-I-08 | 多空间单页列表 | 2 空间共 3 应用 → 列表 3 条，长按可看到各自空间标签；点击各条启动到正确 userId |

### 7.3 UI 测试（Espresso）

| 编号 | 用例 | 断言 |
|---|---|---|
| TC-UI-01 | 单页展示 | 主界面无 ViewPager 指示器；RecyclerView 条目数 = 全空间应用总数 |
| TC-UI-02 | 备份流程 | 长按 → 备份 → SAF 目录选择（mock）→ 进度条出现 → 完成 toast + 文件存在 |
| TC-UI-03 | 元萝卜导入对话框 | 选择元萝卜 zip → 出现助记词输入框（12 词校验：词数≠12 时按钮禁用） |
| TC-UI-04 | 快捷方式回归 | 创建分身快捷方式 → 从快捷方式启动 → 拉起正确的 (pkg, userId) 实例 |
| TC-UI-05 | 空态引导 | 全新安装无分身 → 显示引导页 + 添加按钮 |
| TC-UI-06 | ListActivity 空间选择 | 多空间时装应用出现空间选择器；单空间时隐藏 |

### 7.4 测试基础设施

- 测试 APK 需求：自签名、<5MB、启动 Activity 写一行 SQLite + 一个标记文件（新建 `app/src/androidTest/assets/TestMarkApp.apk` 由构建脚本生成或入库固定文件，附 SHA256）。
- CI（可选）：GitHub Actions `gradlew test` 跑单元测试；instrumented 用 API 34/36 两个模拟器镜像。

---

## 8. 里程碑与执行顺序

| 阶段 | 内容 | 交付物 | 预估规模 |
|---|---|---|---|
| M0 | 通读本文档 + 仓库；跑通 `assembleDebug` | 构建成功截图/log | 0.5 天 |
| M1 | Phase 1 单页 UI + TC-UI-01/05/06、TC-U-11/12、TC-I-08 | 可用 APK + 测试绿 | 1-2 天 |
| M2 | Phase 2 备份引擎 v1 + TC-U-01~07/10/13/14、TC-I-01~06 | 备份/还原可用 + 测试绿 | 3-4 天 |
| M3 | Phase 3.1 元萝卜调研 | 《格式调研报告》+ 样本 + 三档结论 | 1-3 天（不确定性最大） |
| M4 | Phase 3.2 元萝卜导入 + TC-U-08/09、TC-I-07、TC-UI-03 | 导入功能（或降级交付）| 1-2 天 |
| M5 | 回归 + 文档（README 功能说明、备份格式文档） | 最终 PR | 0.5 天 |

依赖关系：M2 依赖 M1（列表项菜单挂备份入口）；M4 依赖 M2（复用还原管线）与 M3。M3 可与 M1 并行启动。

---

## 9. 执行注意事项

1. **不破坏现有功能**：虚拟定位、设备伪装、BlackBox 画廊、冻结管理、Xposed 模块加载等入口全部保留；每阶段结束跑一遍手动回归清单（安装/启动/卸载/快捷方式/双空间）。
2. **VPN 逻辑勿动**：`MainActivity.checkVpnPermission()` 中 SDK>=34 强制禁用 VPN 是上游刻意设计（VPN 实现无包转发会黑洞流量），保留原样。
3. **权限**：备份用 SAF，不新增权限；现有 `MANAGE_EXTERNAL_STORAGE` 逻辑保留但备份功能不得依赖它。
4. **代码风格**：跟随仓库现状（app 层 Kotlin + ViewBinding + MVVM；Bcore 层 Java 风格）。新文件加 Apache-2.0 版权头（复制仓库现有头格式）。
5. **性能红线**：打包/解包必须流式（`ZipOutputStream` 逐 entry 流拷贝），任何"先全读进内存"的实现都算失败（TC-I-05 会拦）。
6. **失败必须显性**：所有异常路径都要有用户可见的错误信息与日志（TAG 规范 `Backup`/`Restore`/`YlbImport`），禁止静默吞掉。
7. **提交规范**：每里程碑至少一个 PR，描述里勾选本文档对应验收项。

---

## 10. 附录：元萝卜备份行为实测记录（供 6.2 调研对照）

来源：吾爱破解/MT 管理器社区用户 jonepjxh 实测帖（2025-10）。

- 备份产出固定名为 `backup.zip`；选择输出目录后开始备份，过程不可退出页面。
- 还原端必须：① 导入相同 12 词助记词身份 ② 先安装原应用 APK ③ 再执行还原；三步缺一不可。
- 备份支持可选密码（实测不设密码也能导出）。
- 跨设备还原成功案例存在（保持登录态），但社区反馈部分机型失败。
- 元萝卜本体是"去中心化应用 + 钱包身份"设计（资产页管理身份、去中心化应用列表、远程模块仓库），备份的身份绑定行为与钱包派生高度吻合。

> 执行 AI 在 M3 结束后，请把本附录替换/补充为实测的格式细节（entry 树、加密方式、KDF 参数），作为最终格式文档。

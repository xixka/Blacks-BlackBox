# 元萝卜（Meta Wolf）备份格式调研报告

| 项目 | 内容 |
|---|---|
| 调研日期 | 2026-11-22 |
| 调研人 | AI Agent C（clean-room 公开渠道调研，未接触任何反编译产物） |
| 目标软件 | 元萝卜 / Meta Wolf（Android 虚拟化多开引擎，闭源商业软件，包名 `top.bienvenido.saas.i18n`） |
| 调研目标 | `backup.zip` 备份格式、加密方式、助记词 KDF 的公开资料；产品谱系与数据目录布局；为拿到真机样本后的验证做假设准备 |
| 本仓库 | Blacks-BlackBox（Bcore 引擎），分支 feature/single-page-backup |

**方法**：中英文多轮搜索引擎查询（元萝卜 备份 zip / 助记词 / 还原；Meta Wolf backup；top.bienvenido.saas.i18n；app_data_anon；52pojie/酷安/Telegram 定向检索）；直接抓取 52pojie 帖子（GBK 转码）；GitHub API 检索 Katana-Official 组织全部仓库、releases、issues 及代码树；Wayback Machine 抢救已下线的官方 API 文档站（bienvenido.top javadoc）；阅读官方公开 demo 源码仓库（Trust Wallet 衍生代码，公开开源授权）。

**诚实声明**：截至本报告日期，公开渠道**没有任何关于 backup.zip 内部结构、加密容器或助记词 KDF 的直接分析资料**。以下凡属推断均已标注；查不到的部分明确写"无公开资料"。

---

## 1. 产品身份与谱系（对格式推断最重要的背景）

| # | 发现 | 来源 | 日期 | 可信度 | 相关性 |
|---|---|---|---|---|---|
| 1.1 | 元萝卜 = 伏羲 X（Fuxi X）更名版，英文市场名 Meta Wolf；自述"去中心化 App 宿主平台，提供 Apps 安装以及去中心化远端提供者配置功能"，支持"自行添加开发者提供的远程模块仓库" | [xiaoyi.vc 介绍页](https://xiaoyi.vc/katana-official-meta.html)；Telegram 频道（秀儿の科技软件）亦按"元萝卜（原伏羲X）"收录 | 2024-07 起多篇 | 高（多源一致） | 高——"远端提供者配置 / changeRemoteServerUrl"与备份/激活服务器的存在吻合 |
| 1.2 | 谱系：asLody/SandVXposed（VirtualApp + SandHook）→ 伏羲 / SlimVXposed → 元萝卜 / SPatch（Katana-Official 分发）。SPatch-Update README 明确 Credits: [SHook](https://gitee.com/quitoa/slim-xposed-compat) 与 [LSPlant](https://github.com/LSPosed/LSPlant) | [asLody/SandVXposed](https://github.com/asLody/SandVXposed)、[SPatch-Update README](https://raw.githubusercontent.com/Katana-Official/SPatch-Update/master/README.md) | 2018–2026 | 高 | 高——**VirtualApp 血统决定了虚拟数据目录布局的推断基线** |
| 1.3 | 官方组织 Katana-Official 共 7 个仓库：SPatch-Update（APK 分发，2374 星）、Meta（"Meta Platform demo source code"，409 星）、Mundo-SDK / Mundo-OSS（闭源 SDK，"contact us"）、XYNXposed、LSPlant-FOSS、ApiDemos-FOSS。引擎本体不在 GitHub | [github.com/Katana-Official](https://github.com/Katana-Official) | 持续更新至 2026-11 | 高 | 高 |
| 1.4 | 官网 www.die.lu（产品自述含 "file encrypting / privacy dividing" 营销语，称 "open source"，实际开源的只有 demo/launcher）；联系 Telegram t.me/foxiteu、Email 1@die.lu；API 文档曾在 bienvenido.top | [die.lu](https://www.die.lu)、[SPatch-Update README](https://raw.githubusercontent.com/Katana-Official/SPatch-Update/master/README.md) | 存续中 | 高 | 中 |
| 1.5 | 版本史：2022-10（svxp32/svxp64 命名）→ 2024-01 起命名 Meta → 2026-08 V1.0.29（最新 release）。52pojie 由坛友分享 v1.0.0.2.1 / v1.0.25 / v1.0.26 | [SPatch-Update releases](https://github.com/Katana-Official/SPatch-Update/releases)；52pojie [thread-2022483](https://www.52pojie.cn/thread-2022483-1-1.html)、[thread-2043594](https://www.52pojie.cn/thread-2043594-1-1.html)、[thread-2058669](https://www.52pojie.cn/thread-2058669-1-1.html) | 2022–2026 | 高 | 中 |

---

## 2. 备份/还原流程与行为的公开证据（用户实测）

| # | 发现 | 来源 | 日期 | 可信度 | 相关性 |
|---|---|---|---|---|---|
| 2.1 | 完整跨机迁移教程（视障用户读屏社区，步骤记录极细致）：①资产→添加/导入虚拟身份→输入 12 个英文单词（或点击"创建新身份"并复制 12 词）；②配置→备份与还原→选身份→多选应用→点"好"→点备份→选输出目录；③备份文件发到另一台手机（**必须连同目标应用 APK 一起发**）；④新机导入同一助记词→配置→"从文档管理器安装软体"装 APK（**装完不要启动**）→备份与还原→选同一身份→还原→选备份文件 | [坦坦社区 topic/85451](https://bbs.tatans.cn/topic/85451) | 2025-04-04 | 高（一手实测教程，与仓库计划文档中 jonepjxh 帖描述的三步要求一致） | 极高——确认"备份与助记词身份绑定 + 先装 APK + 数据-only 备份" |
| 2.2 | GitHub Issue #30：备份 5GB（微信）后还原卡死一小时；"伏羲还原正常，用时约30s"。说明备份体积可达数 GB（含应用数据全量），还原路径性能差 | [SPatch-Update issue #30](https://github.com/Katana-Official/SPatch-Update/issues/30) | 2024-05-14 | 高（一手） | 中——格式层面：包含大数据文件原样打包 |
| 2.3 | GitHub Issue #111（官方未回应）：①设置密码后触发"自毁密码"机制，输错即删除全部已装分身；②**"备份的软件不是备份的完整安装包，无法二次安装，就算点击恢复备份，恢复的软件也无法打开"** | [issue #111](https://github.com/Katana-Official/SPatch-Update/issues/111) | 2026-05-06 | 中-高（一手用户反馈，但需与"必须先装 APK"流程互相印证） | 极高——**backup.zip 不含 APK，只含数据**；存在自毁密码机制（错误口令→销毁） |
| 2.4 | GitHub Issue #121：**"在一台手机上创建的钱包地址，在另一台手机上使用助记词恢复出来的钱包地址是另外一个"**；用户 colin4k 追评："这样导致在手机 A 上备份的数据，在手机 B 上无法恢复"；开发者 OfficialKatana 回复（2026-10-08）："**你好，需要按照顺序（如果有密码就需要原来的密码）**" | [issue #121](https://github.com/Katana-Official/SPatch-Update/issues/121) | 2026-09-15 / 2026-10-08 | 高（含开发者本人回复） | 极高——①地址派生可能依赖"导入顺序/槽位"而非纯助记词确定性派生；②密码参与派生（"需要原来的密码"）；③备份绑定身份派生结果 |
| 2.5 | GitHub Issue #87：恢复备份的数据后需杀掉元萝卜重启才能看到恢复的 app（v1.0.25） | [issue #87](https://github.com/Katana-Official/SPatch-Update/issues/87) | 2025-06-19 | 高 | 低-中 |
| 2.6 | GitHub Issue #120：有人想将 SwiftBackup 备份的应用数据导入元萝卜（无回复）。说明社区把元萝卜分身数据视为"与系统备份数据同质的东西" | [issue #120](https://github.com/Katana-Official/SPatch-Update/issues/120) | 2026-09-13 | 高 | 低 |
| 2.7 | GitHub Issue #53：用户抱怨"备份功能能不能改成和之前伏羲一样的，现在这样太麻烦了"——即现行"身份绑定式备份"是新设计，旧伏羲是普通备份 | [issue #53](https://github.com/Katana-Official/SPatch-Update/issues/53) | 2024-11-01 | 高 | 中——身份绑定加密是元萝卜时代引入 |
| 2.8 | **52pojie 站内/站外检索：只有 3 个软件分享帖（见 1.5），无任何备份格式分析帖**；对 3 个帖子的正文+全部回复做了关键词全文检查（备份/还原/助记词/backup/加密），仅正文营销语提到"数据加密"，无技术讨论 | 52pojie [thread-2058669](https://www.52pojie.cn/thread-2058669-1-1.html)（2025-09-08）、[thread-2043594](https://www.52pojie.cn/thread-2043594-1-1.html)（2025-07-05）、[thread-2022483](https://www.52pojie.cn/thread-2022483-1-1.html)；本调研 curl 抓取全文核对 | 2025 | 高（"无资料"结论本身可信） | —— |
| 2.9 | **52pojie 用户 jonepjxh：确为该站资深坛友（2010 年至今），但其"元萝卜备份实测帖"未被任何搜索引擎收录，52pojie 站内搜索需登录无法免登录验证**。仓库计划文档所引"jonepjxh 实测帖（2025-10）"无法在公开渠道定位原文 | [52pojie 搜索结果](https://www.52pojie.cn/search.php)（需登录）；jonepjxh 名字出现在 [thread-1692203](https://www.52pojie.cn/thread-1692203-1-1.html)（其 CSDN 账号同名）等帖 | 2025-10（据计划文档） | —— | **无公开资料**（如需原文需论坛登录人工取证） |
| 2.10 | 酷安：未检索到元萝卜备份格式相关公开讨论（搜索引擎索引范围内） | 多轮 WebSearch | 2026-11 | 中 | **无公开资料** |
| 2.11 | Telegram：仅在软件分享频道（如"秀儿の科技软件"）见到"元萝卜（原伏羲X）"收录信息；未检索到格式分析频道内容 | 经 WebSearch 间接引用 | 2025-2026 | 中 | 低 |
| 2.12 | Bilibili BV1gGmPBmEJd《纯血鸿蒙 MT管理器常见问题（apk共存、闪退、找不到apk、元萝卜）》：提及元萝卜使用教程，未涉备份格式 | Bilibili（经搜索索引） | 2025 | 中 | 低 |

---

## 3. 官方公开 SDK / 源码中的硬证据（clean-room 合规：以下均为官方公开发布物）

### 3.1 已下线官方 API 文档站（bienvenido.top）经 Wayback 抢救的 javadoc

- 快照：[2025-07-13 存档](https://web.archive.org/web/20250713051357/https://bienvenido.top/net_62v/external/package-summary.html)（域名后来下线，GitHub Pages 返回 404）；更早同名文档在 docs.su.bienvenido.top（[2025-01-14 存档](https://web.archive.org/web/20250114232106/https://docs.su.bienvenido.top/net_62v/external/package-summary.html)）
- 包 `net_62v.external` 共 7 个类（来自存档的 type-search-index.js / member-search-index.js）：
  `MetaCore`、`MetaActivityManager`、`MetaPackageManager`、`MetaApplicationInstaller`、`MetaStorageManager`、`MetaActivationManager`、`IMundoProcessCallback`
- **与存储布局直接相关的公开 API 签名**（javadoc 成员索引，方法体未公开）：
  - `MetaStorageManager.obtainAppDataDir(String pkg, int user)` / `obtainAppDataDir(String pkg, String user)` —— **按"包名 + 用户分区"寻址应用数据目录**
  - `MetaStorageManager.obtainAppExternalStorageDir(String)`、`setExternalRootDirectory(String)`
  - `MetaPackageManager.createEmptyUser(String, int)` / `createEmptyUserById(String)`、`cleanPackageDataAsUser(String, int)`、`getInstalledUserId(String)`、`getInstalledUserName(String)`
  - `MetaApplicationInstaller.cloneApp(String)` / `cloneApp(String, String)`、`installAppByPath(String)`、`installAppsByPathAndMove(String)`
  - `MetaActivationManager.activateSdk(String)`、`changeRemoteServerUrl(String)` —— 可改远程服务器 URL（对应"去中心化远端提供者配置"）
- **公开 SDK 中没有任何 backup/restore 相关 API** —— 备份功能在闭源 UI 层实现。
- 可信度：高（官方 javadoc，Wayback 存档）。相关性：极高。

### 3.2 官方公开 demo 源码仓库 Katana-Official/Meta（"Meta Platform demo source code"）

内容为 2018 年版 Trust Wallet 开源安卓客户端（`com.wallet.crypto.trustapp`）改包，即元萝卜"资产/虚拟身份"页的钱包层参考实现。以下均为该公开仓库源码可见参数（[github.com/Katana-Official/Meta](https://github.com/Katana-Official/Meta)，Apache/MIT 系开源授权，非反编译产物）：

- `service/WalletStorage.kt`（第 637-644 行注释与常量）：
  - 助记词派生路径常量：`ETH_JAXX_TYPE = "m/44'/60'/0'/0/0"`（注释："通用的以太坊基于 bip44 协议的助记词路径（imtoken jaxx Metamask myetherwallet）"，即默认路径）、`ETH_LEDGER_TYPE = "m/44'/60'/0'/0"`、`ETH_CUSTOM_TYPE = "m/44'/60'/1'/0/0"`
  - keystore 用 web3j `Wallet.create(password, ecKeyPair, N, P)`，**N = 1 shl 9 = 512，P = 1**（scrypt 参数，远弱于 geth 标准的 n=262144；web3j 默认 r=8, dklen=32）
  - keystore 文件按"地址（去 0x 前缀）"命名存放于 `FileUtils.WALLET_DIR`；钱包列表持久化在 SharedPreferences 键 `"sp_wallets"`（gson 序列化）
  - 该 demo 中助记词字段（`mnemonic`, `mnemonicPath`）被注释掉，未实现助记词导入——正式版的 12 词导入在闭源层
- `repository/TrustPasswordStore.java` + `util/KS.java`：每个钱包地址的密码用 **Android Keystore 中生成的 AES 密钥以 `AES/CBC/PKCS7Padding` 加密**后落盘
- 可信度：高（官方公开仓库原文）。相关性：高——**它泄露的是这个团队的密码学"口味"**（BIP44 标准路径、web3j keystore、AES-CBC-PKCS7、弱 scrypt），可指导 KDF 假设排序，但**不等于 backup.zip 就这么加密**。

### 3.3 实际观察到的虚拟数据根路径（第三方用户实践）

- Reddit r/LegendsOfRuneterra 教程《Android, no root, how to unlock 60+ fps (by cloning LoR)》指导用户进入
  **`/data/data/top.bienvenido.saas.i18n/app_data_anon/com.riotgames.legendsofruneterra/`**
  编辑分身内应用的文件（含多语言转载版本）。"app_data_anon" 这一目录名在公开网络上无其他文档，是该引擎自有命名（经 WebSearch 检索 `"app_data_anon"` 无更多结果）。
- 可信度：中-高（一手操作教程，经搜索引擎索引摘要，原帖未能直接抓取——Reddit 未登录接口被反爬拦截）。
- 相关性：极高——给出虚拟数据根的**真实观测值**：`<宿主私有目录>/app_data_anon/<目标包名>/`。

### 3.4 服务端基础设施旁证

- Wayback CDX 显示 `apiserver1~6.bienvenido.top`、`dathserver`、`private-node*`、以及 **`zkevm-node2~4.bienvenido.top`**（zkEVM 节点）、`phpmyadmin.bienvenido.top` 等子域存档（2023-08 ~ 2024-12）。zkEVM 节点旁证其 Web3/钱包身份体系是真实的链上设计。
- 可信度：高（存档记录）。相关性：中。

---

## 4. 分析：backup.zip 可能的加密方案（假设与排序）

**已确认事实**（多源交叉）：
1. 备份与"虚拟身份"绑定：还原前必须导入同一 12 词助记词身份（坦坦教程 + 计划文档 jonepjxh 帖）。
2. 身份可设可选密码；开发者："如果有密码就需要原来的密码"（issue #121 官方回复）→ **密码大概率参与密钥派生**，而非仅做登录校验（否则换机后输错密码应报"密码错误"而非"无法恢复"）。
3. 同一助记词在两台手机上派生出的钱包地址可能不同（issue #121 用户报告）→ 派生过程**可能含非助记词因素**（导入槽位序号 / 本地盐 / 计数器）。
4. 备份不含 APK，仅数据（issue #111 + 坦坦教程"必须连同 APK 一起发"）。
5. 同一备份文件可跨设备还原成功的案例存在于 2025-04（坦坦教程）；2026-09 出现跨机失败报告（issue #121）→ 行为可能随版本变化（v1.0.12 → v1.0.28+），或与身份槽位/密码设置差异有关。

**假设 H1（最可能）：BIP39 → BIP32 → AES 信封/直接加密**
- 12 词助记词（NFKD 规范化）+ 可选密码作为 BIP39 passphrase → `seed = PBKDF2-HMAC-SHA512(mnemonic, "mnemonic"+passphrase, 2048, 64B)` → BIP32 主钥（HMAC-SHA512, key="Bitcoin seed"）→ 取 `m/44'/60'/0'/0/0`（或按身份槽位 `m/44'/60'/{slot}'/0/0`，对应 issue #121 的"顺序"问题）→ 私钥/公钥派生加密密钥（如 SHA-256(privkey) 或 keccak256）→ AES-256 加密 zip 内容。
- 支持证据：官方 demo 内嵌 Trust Wallet 代码且保留 BIP44 路径常量；"资产"页是钱包语义（地址、助记词）；密码参与派生；zkEVM 基础设施。
- 若采用"槽位"派生，可解释跨机地址不一致与"按照顺序"的官方回复。

**假设 H2（次可能）：助记词 → seed 直接派生对称密钥（不走 BIP32）**
- `key = SHA-256(seed[0:32])` 或 `HKDF(seed)`、或 `PBKDF2(mnemonic+password, salt)` 自定义迭代 → AES-256-CBC（其 KS.java 用 AES/CBC/PKCS7，团队口味偏 CBC）加密整个 zip 或逐条目加密。
- 支持证据：中国 App 开发者常见简化做法；demo 弱 scrypt（N=512）表明团队不以标准强度为执念，可能同样简化 KDF。

**假设 H3（兜底）：外层标准 zip + 内层数据加密**
- backup.zip 本体是普通 ZIP（可列名），条目内是按应用打包的数据（SQLite/SharedPreferences/文件原样），其中敏感库（如身份库、账号库）用身份密钥加密或 SQLCipher 加密，非敏感数据明文。"密码"只加密一个头部 key-slot（信封加密：随机数据密钥被身份密钥包裹）。
- 支持证据：还原失败表现为"无法恢复"而非"解不出任何文件"；issue #30 显示 5GB 备份/还原吞吐尚可（全量 AES 加密 5GB 在低端机可能成为瓶颈，故可能只加密部分/或用无加密 zip）。

**zip 容器层面**：产出名固定 `backup.zip`，扩展名即 ZIP。是否存在 WinZip-AES（0x9901）/ZipCrypto（传统弱加密）/加密头整体不可读三种形态，公开资料**无记录**——必须靠样本判定（见 §5）。

**自毁密码机制**（issue #111）警示：拿到样本做验证时**不要在装有真实身份的设备上随意输错密码**，自毁会清空全部分身。

---

## 5. 拿到样本后 30 分钟内可完成的验证（step-by-step）

前置：一个隔离测试设备/模拟器，全新安装元萝卜（GitHub releases v1.0.29 APK），创建身份 A（**自定义一组我们掌握的 12 词助记词**，先不设密码），装一个测试 App（如系统计算器克隆），产生已知数据（设置一条偏好/写一个文件），走一遍备份得到 `backup.zip`。全程不要用真实资产身份。

1. **容器判定（5 分钟）**
   - `unzip -l backup.zip`：能列出条目 → 外层未加密；列出失败但 `zipinfo`/hexdump 显示 PK 头存在 → 条目级加密或 ZipCrypto。
   - `xxd backup.zip | head -20`：确认 `PK\x03\x04` 本地头；检查 General Purpose Bit Flag（offset 6-7，little-endian）bit0 是否置 1（=ZipCrypto/加密）；扫描 Extra Field 是否含 ID `0x9901`（WinZip AES）；找 EOCD（`PK\x05\x06`）与 Central Directory（`PK\x01\x02`）确认条目名是否可读。
   - 记录条目命名——**条目路径本身就是目录映射的直接证据**（预期出现 `app_data_anon/`、包名、或 `data/user/` 字样，以及可能的 manifest/元数据 json）。
2. **明文性抽检（5 分钟）**：对可解压条目逐一 `file` / 查看头部 magic：`SQLite format 3\0`（明文 SQLite）、`android`/XML 文本、`libsqlcipher`（SQLite 文件头 16 字节为随机数 → 该库按 SQLCipher 或自定义加密）。
3. **对照实验（10 分钟）**：同一身份分别以"无密码 / 密码 P1 / 密码 P2"再备份两次，对比：文件大小差异、头部字节差异 → 判定密码影响的是整包密钥还是仅身份记录。
4. **KDF 快速试探（10 分钟，脚本思路，全部离线）**：
   - 用已知助记词 + 密码空/P1 生成候选密钥序列：
     a) BIP39 seed（`python -I` + `mnemonic` 库：`Mnemonic.to_seed(words, passphrase)`）
     b) `seed[0:32]`、`SHA256(seed)`、`SHA512(seed)[0:32]`
     c) BIP32 派生：`m/44'/60'/0'/0/0`、`m/44'/60'/{0,1,2}'/0/0`（槽位假设）→ 私钥字节 → `SHA256(priv)`、`keccak256(priv)`
     d) `PBKDF2-HMAC-SHA256(mnemonic, salt尝试: "mnemonic"+pwd / 包名 / 固定串, 2048/10000, 32B)`
   - 对目标密文（取一个头 16 字节已知明文为 `SQLite format 3\0` 的条目，或 zip AES 的盐）逐一试 AES-256-CBC（IV 取首 16 字节或 header 内）、AES-256-CTR、AES-256-GCM；用 PKCS7 padding 合法性 / magic 命中判定。
   - 若是 WinZip-AES：直接用助记词字符串、seed hex、派生密钥 hex 作为口令用 `pyzipper` 试开。
   - 若是 SQLCipher：用候选密钥作为 `PRAGMA key`（raw 32B 与 hex 字符串两种形式）在样本 db 上试开。
5. **（可选，需 root 测试机）设备侧取证**：备份前后 dump `/data/data/top.bienvenido.saas.i18n/`（重点 `app_data_anon/`、`shared_prefs/sp_wallets*`、keystore 文件目录、databases），diff 出身份库与备份 manifest 的落点。

---

## 6. 目录映射假设：元萝卜虚拟数据根 → Bcore `data/user/{uid}/{pkg}`

| 元萝卜侧（证据 → 推断） | Bcore 侧（BlackBox/Bcore 布局） | 映射说明 |
|---|---|---|
| 虚拟数据根：`/data/data/top.bienvenido.saas.i18n/app_data_anon/<pkg>/`（Reddit 实测路径） | `Bcore/data/user/{uid}/{pkg}/` | `app_data_anon` 根 ≙ `data/user/{uid}`；`<pkg>` 目录一一对应 |
| 公开 API `obtainAppDataDir(pkg, user)`、`createEmptyUser(name, id)`、`getInstalledUserId/Name`：存在显式"用户分区"概念，user 可为 int 或 String（String 形态或即"身份/partition 名"） | uid（int） | 元萝卜"身份"≈ 用户分区 ≙ Bcore uid；String 分区名 ↔ uid 需要一张身份表映射 |
| 备份 zip 内条目若携带 `user/分区名 + pkg`（待样本证实） | 还原时按目标 uid 重写路径前缀 | 前缀重写导入器 |
| 虚拟 APK 安装目录：VA 血统惯例 `<宿主私有>/virtual/app/<pkg>.apk` 或 `app_install_anon/`（**无公开观测值**，命名待样本确认） | `Bcore/data/app/{pkg}/base.apk`（Bcore 的 apk 目录） | 备份不含 APK（issue #111），还原前置条件是 APK 已在 Bcore 安装——与元萝卜"先装 APK"流程同构 |
| `obtainAppExternalStorageDir(pkg)` + `setExternalRootDirectory`：外部存储模拟目录 | `Bcore/data/user/{uid}/external/`（按 Bcore 实际布局） | 备份若含外部存储条目则映射于此 |
| 元萝卜数据库/身份库（keystore 文件按地址命名、SP 键 `sp_wallets`——demo 证据；正式版可能迁移到私有 db，**无公开资料**） | 不导入 / 或映射到 Bcore 身份库 | 身份库与我们的账号体系不同构，首次实现应跳过 |

注意：BlackBox（Bcore）与 VirtualApp 虽同属虚拟化引擎，但目录命名约定不同；上述映射是**语义级**（user 分区 + 包名两级），实现导入器时按 zip 条目路径前缀做正则归一，而不是硬编码 `app_data_anon`。

---

## 7. 结论（三档）

**当前结论：不可导入（格式层）——但拿到真机样本后升级空间很大。**

- **完整导入**：需要 backup.zip 密钥可复算（H1/H2 成立且参数全部还原）。公开资料不足以现在达成，但样本 + 已知助记词 + §5 验证流程可在 1-2 个工作日内判定 H1/H2/H3；若 KDF 命中，可做到完整导入（含逐步前缀重写到 Bcore 布局）。
- **部分导入**：若外层 zip 可列名且部分条目明文（H3 成立），可直接导入明文部分（App 数据文件/部分 SQLite），身份库等加密部分跳过——大概率足以让目标应用恢复运行态数据（登录态存于 App 自己的 SQLite/SP，若其条目明文即成功）。
- **不可导入**：若整包为不可复算的加密（密钥依赖设备绑定因子而非纯助记词+密码，如 issue #121 所暗示的槽位/本地盐，且该因子只存于原机），则只能建议用户走"元萝卜自还原 → Bcore 侧重新备份"的中转方案。

**给后续执行者的最小实验清单**（拿到真机样本当天执行，均为只读/离线操作）：
1. 按 §5-1/2 完成容器与 magic 判定，记录 `unzip -l`、`zipinfo -v`、首 512 字节 hexdump、EOCD 位置、条目全名清单。
2. 按 §5-3 做无密码/密码对照备份各一份（隔离设备，勿在真实身份设备上试密码——自毁机制）。
3. 按 §5-4 跑 KDF 试探脚本（先 H1 默认路径，再槽位变体，再 H2 简化派生）。
4. 将样本连同第 1-3 步输出归档到 `docs/ylb-format/samples/`（如团队政策允许），更新本报告第 4/6/7 节。
5. 若 KDF 全部未命中，重点排查 zip 条目级加密（逐条目判定加密标志）与身份库落点（设备侧取证），并复测 issue #121 的"导入顺序"现象（同一助记词在干净环境导入两次，记录两次派生地址是否一致）。
6. 全程不要提交任何反编译产物到仓库；只记录格式事实与摘要。

---

## 附录 A：BIP39 / BIP32 / BIP44 标准参数速查（用于实现 H1/H2 假设）

**BIP39（助记词 → 种子）**
- 12 词 = 128 bit 熵 + 4 bit 校验（SHA-256 前 4 bit），词表 2048 个英文单词（空格分隔）
- 规范化：NFKD；校验失败则非法助记词
- 种子：`seed = PBKDF2-HMAC-SHA512(password=NFKD(mnemonic), salt="mnemonic" + NFKD(passphrase), iterations=2048, dkLen=64)`
- 可选 passphrase 即"第 25 个词"——元萝卜"可设可不设的密码"在钱包语义下天然对应它（**但也可能只是自定义 KDF 的盐，需样本判定**）

**BIP32（种子 → 层级确定密钥）**
- 主钥：`I = HMAC-SHA512(key=b"Bitcoin seed", data=seed)`；`IL`（前 32B）= 主私钥，`IR`（后 32B）= 链码
- 子钥派生 CKDpriv： hardened 索引 = index + 2^31
- 工具：`python-mnemonic`、`python-bitcoinlib`、`eth-account`（`Account.from_mnemonic(words, passphrase=...)` 直接给 m/44'/60'/0'/0/0）

**BIP44（以太坊惯用路径）**
- 默认：`m/44'/60'/0'/0/0`（MetaMask/Trust/imToken/JAXX 通用；官方 demo 中 `ETH_JAXX_TYPE` 常量，即其注释所称通用路径）
- Ledger 变体：`m/44'/60'/0'/0`；账号槽位变体：`m/44'/60'/{account}'/0/0`
- 地址：`keccak256(uncompressed_pubkey[1:])[12:]` → 0x + 40 hex

**Web3 Secret Storage（keystore v3，demo 所用 web3j 实现）**
- scrypt：geth 标准 `n=262144, r=8, p=1, dklen=32`；web3j "light" `n=4096`；**Meta demo 自定义 `N=512, P=1`（r=8）**
- `derived_key` 前 16B 用于 AES-128-CTR 加密私钥；`mac = keccak256(derived[16:32] || ciphertext)`
- 即：该团队习惯**非标准弱化 scrypt 参数**，复算其 KDF 时应把弱参数列入优先尝试

**对称加密候选（按团队口味排序）**
1. AES-256-CBC + PKCS7（KS.java 明确使用 `AES/CBC/PKCS7Padding`）
2. AES-256-GCM
3. AES-128-CTR（keystore v3 内层惯例）
4. ZIP 容器层：ZipCrypto（传统）/ WinZip AES-256（0x9901, AE-2）

**验证工具箱**
- `python -I` + `pyzipper`（WinZip-AES 试开）、`mnemonic`、`eth-account`、`pycryptodome`
- `iancoleman.io/bip39`（人工核对派生，注意该页面是联网工具，验证时只用于离线校对派生结果，不输入真实助记词）
- `sqlcipher` CLI（`PRAGMA key = "x'...'"` raw key 形式试开）

## 附录 B：本报告引用的全部来源清单

| 来源 | 链接 | 日期 | 可信度 |
|---|---|---|---|
| 坦坦社区跨机备份教程 | https://bbs.tatans.cn/topic/85451 | 2025-04-04 | 高 |
| SPatch-Update issue #30（还原卡死/伏羲 30s） | https://github.com/Katana-Official/SPatch-Update/issues/30 | 2024-05-14 | 高 |
| SPatch-Update issue #53（备份功能与伏羲对比） | https://github.com/Katana-Official/SPatch-Update/issues/53 | 2024-11-01 | 高 |
| SPatch-Update issue #87（还原后需重启） | https://github.com/Katana-Official/SPatch-Update/issues/87 | 2025-06-19 | 高 |
| SPatch-Update issue #111（备份非完整安装包/自毁密码） | https://github.com/Katana-Official/SPatch-Update/issues/111 | 2026-05-06 | 中-高 |
| SPatch-Update issue #120（SwiftBackup 导入提问） | https://github.com/Katana-Official/SPatch-Update/issues/120 | 2026-09-13 | 高 |
| SPatch-Update issue #121（跨机地址不同/开发者答复） | https://github.com/Katana-Official/SPatch-Update/issues/121 | 2026-09-15 / 2026-10-08 | 高 |
| SPatch-Update 仓库与 releases（版本史） | https://github.com/Katana-Official/SPatch-Update | 2022-10 ~ 2026-08 | 高 |
| Katana-Official/Meta（Meta Platform demo source code，Trust Wallet 衍生源码） | https://github.com/Katana-Official/Meta | 2025-04 前后 | 高（官方公开） |
| SPatch-Update README（Mundo/SDK/die.lu/telegram） | https://raw.githubusercontent.com/Katana-Official/SPatch-Update/master/README.md | 持续更新 | 高 |
| 官方 API javadoc（Wayback 存档，net_62v.external 七类） | https://web.archive.org/web/20250713051357/https://bienvenido.top/net_62v/external/package-summary.html（索引 js：20250713051401 type/member-search-index.js） | 2025-07-13 快照 | 高 |
| 官网 die.lu | https://www.die.lu | 存续中 | 高 |
| xiaoyi.vc 介绍页（原伏羲X、远程模块仓库、开源版本说明） | https://xiaoyi.vc/katana-official-meta.html | 2024-07 | 中-高 |
| 52pojie 三个软件分享帖（全文关键词核对，无格式讨论） | https://www.52pojie.cn/thread-2058669-1-1.html / thread-2043594 / thread-2022483 | 2025 | 高 |
| SandVXposed（谱系源头） | https://github.com/asLody/SandVXposed | 2018 | 高 |
| Reddit r/LegendsOfRuneterra 克隆教程（app_data_anon 实测路径） | 搜索引擎索引摘要（原帖反爬未直读） | 2024-2025 | 中-高 |
| Wayback CDX（bienvenido.top 基础设施/zkevm-node 子域） | https://web.archive.org/cdx/search/cdx?url=bienvenido.top&matchType=domain | 2023-2025 | 高 |
| Bilibili BV1gGmPBmEJd（鸿蒙 MT 管理器与元萝卜） | https://www.bilibili.com/video/BV1gGmPBmEJd | 2025 | 中 |

**明确无公开资料的项目**（截至 2026-11-22）：
1. backup.zip 的内部条目结构、命名规范与元数据格式——无公开资料
2. 备份加密容器类型（整包加密 / 条目加密 / WinZip-AES / 信封加密）——无公开资料
3. 助记词 → 备份密钥的具体 KDF（是否 BIP39 标准参数、盐、迭代）——无公开资料
4. 元萝卜是否使用 libsqlcipher / 数据库加密方案——无公开资料
5. 官方远程模块仓库 URL（设计为用户自行添加的"去中心化"仓库 + SDK `changeRemoteServerUrl`，未发现固定公开 URL）——无公开资料
6. jonepjxh 元萝卜实测帖原文（52pojie 站内搜索需登录，搜索引擎未收录）——无公开资料
7. 元萝卜 APK 的查壳/加固技术分析帖——无公开资料（仅有通用查壳方法论；APK 自身可在 GitHub releases 直接下载，壳/加固情况建议后续拿到 APK 后用 ApkCheckPack 等开源工具自测，本调研未做，因任务纪律禁碰二进制分析以外的代码行为）

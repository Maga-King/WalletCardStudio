# 钱包卡面 · WalletCardStudio

给一加 / OPPO 钱包更换银行卡、公交卡的本地卡面。支持系统图库选图、按原卡比例裁切、原图备份与恢复，以及可搜索的离线卡面库。

[下载安装包](https://github.com/Maga-King/WalletCardStudio/releases/latest) · [下载离线卡面包](https://github.com/Maga-King/WalletCardStudio/releases/latest/download/WalletArtwork-Offline.zip)

## 功能

- 读取已添加卡片的名称和当前卡面，支持银行卡、公交卡筛选。
- 从系统图库、卡面库、网络库选择图片，或输入 HTTPS 图片直链。
- 所有来源均先按目标卡片的原始比例裁切，支持拖动、双指缩放、旋转和重置。
- 首次替换前自动备份原图，可恢复；保留钱包原图的格式和尺寸。
- 卡面库与网络库独立展示，支持搜索、来源筛选、全量滚动和拖动右侧滑块。
- 支持导入压缩 ZIP 离线包，日常浏览、搜索、预览与裁切不访问素材网站。
- 设置中手动同步 Cardentify；按完整网址区分图片，校验已有文件后跳过完整原图。
- 提供 Root / Shizuku 访问方式切换。

## 支持情况

最低支持 Android 11。主要适配 `com.finshell.wallet` 的图片缓存和卡片数据库，已在一加 PJZ110、Android 17、KernelSU、钱包 5.50.9 上验证 Root 读取、替换与恢复。

**小米目前仅作为卡面素材来源，尚未实现小米钱包的卡面替换。** 不同钱包版本的数据结构可能不同，暂未适配的版本会提示读取失败。

|访问方式|读取卡名|读取与替换卡面|
|---|---|---|
|Root|从钱包数据库识别|读写钱包图片缓存|
|以 Root 启动的 Shizuku|按服务权限读取数据库|按服务权限读写缓存|
|普通 Shizuku（无线调试 / ADB 启动）|沿用此前 Root 保存的映射；其余显示为缓存卡面|使用外部缓存目录，是否可读写取决于系统限制|

普通 Shizuku 的 UID 为 2000，不能读取钱包私有数据库。缓存列表可能包含未添加卡片的图片；多个卡片共用一个缓存时会一起变化。选择 Shizuku 后不会自动改用 Root。

Shizuku 集成已通过编译与 Android lint；真实 Shizuku 服务的替换、恢复尚未完成实测。已有的 shell 身份近似探测表明测试设备的外部缓存可读写、私有数据库不可读，该结果不等同于真实 Shizuku 测试。

## 使用方法

1. 从 [Releases](https://github.com/Maga-King/WalletCardStudio/releases/latest) 下载并安装 `WalletCardStudio-1.3.0.apk`。覆盖安装可保留现有图库、备份和设置。
2. 默认使用 Root，并在权限管理器中授权。使用 Shizuku 时，先在 Shizuku 中启动服务，再进入“设置 → 切换访问方式 · Root / Shizuku”选择并授权。
3. 在钱包中打开需要修改的卡片，等待原卡面加载完成，再回到工具点击“刷新”。
4. 在“我的卡片”点击“更换卡面”，从图库、卡面库、网络库选择图片，或下载图片网址。
5. 完成裁切并确认应用。工具会关闭钱包、备份首次原图并替换本地缓存；重新打开钱包查看效果。
6. 需要恢复时，点击对应卡片的“恢复原图”。

钱包重新下载图片、更新或清理缓存后，可能需要再次应用卡面。原图备份保存在本工具的私有目录，卸载工具会删除备份。

## 离线卡面库与网络库

**“网络库”表示素材来源于网络，已下载的图片同样可以离线浏览。** “更换卡面”菜单内也有独立的网络库入口。

- 卡面库默认展示已添加卡片原图与小米公交素材，另有社区、全部本地缓存筛选。
- 网络库展示 Cardentify 主站与 Issue 投稿，包含 Apple Pay、Mi Pay、云闪付等来源。
- 在“设置 → 导入离线 ZIP 图片包”选择 `WalletArtwork-Offline.zip`，直接导入，无需解压。支持标准 ZIP 的 Store / Deflate 压缩；发布包采用 DEFLATE 最高 9 级，保留图片原字节。
- 未导入离线包也可以在设置中手动联网同步 Cardentify；额外 Issue、小米公交及社区素材通过离线包提供。
- 手动同步读取目录，只下载缺失或校验失败的图片。已有文件需通过长度、SHA256、尺寸及原附件校验才跳过；不同完整网址不按名称或相似外观合并。
- 网站 SVG 原件需要离线包配套的 PNG 才能在 Android 中裁切；仅联网同步暂不支持新 SVG 的渲染。

截至 2026-10-20，离线包包含 **1,129 个独立资源条目**：

|来源|条目数|
|---|---:|
|Cardentify 主站全部来源|842|
|额外 GitHub Issue 投稿|21|
|小米公交目录|81|
|MiPayCard 社区及 APK 内置图|185|

同一完整网址只存一份，其他名称作为别名保留。19 份 SVG 原件随包保存，并附带 Android 可裁切的 PNG。Cardentify Issue #122 的中国银行哔哩哔哩 2233 两版均已收录。

社区目录共 214 条在线记录，184 条下载成功，另提取了两张 APK 内置图片；按完整网址合并后构成上述 185 个社区资源。剩余 30 条重试后仍失败：21 条 HTTP 404、3 条 HTTP 403、6 条连接或域名错误。详情见 [卡面下载情况](docs/卡面下载情况.md)。

## 构建

需要 JDK 17 或更新版本、Android SDK Platform 36。仓库自带 Gradle 8.13 Wrapper，首次构建需要联网下载 Gradle 和依赖。

配置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`，也可以在不提交的 `local.properties` 中填写 SDK 路径：

```properties
sdk.dir=C:/Android/Sdk
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

macOS / Linux：

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。Windows 也可以运行以下脚本，构建并复制到 `dist/`：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

已缓存依赖时，可附加 `-Offline`。测试 APK 使用 `:app:assembleDebugAndroidTest` 构建；其中钱包实际写入检查需要显式启用。

`tools/` 中包含素材下载、目录整理和 ZIP 打包脚本，使用 Python 3、requests、Pillow、PyMuPDF，Issue 图像匹配另需 NumPy。部分脚本依赖本地采集的中间文件，仓库不附带这些输入；使用图库可直接下载 Release 中的离线包。

## 实现与验证

- 钱包数据库仅复制为快照读取，不修改卡片数据库。
- Root 使用 KernelSU / Magisk 的 `su --mount-master`，处理应用挂载命名空间隔离。
- 普通 Shizuku 使用应用外部 `files/wallet-work/` 交换图片与事务文件，原图备份始终保存在应用私有目录。
- 替换前校验缓存索引，使用同目录临时文件、撤销备份、提交标记与恢复日志更新图片和 DiskLruCache 索引。
- 完整扫描与事务共享进程锁，Shizuku 用户服务也串行执行命令，避免刷新和替换交错。
- 源码仓库不包含手机私有数据库、账号缓存、Root 授权信息、签名密钥或本地研究目录。

1.3.0 已完成 APK 构建、测试 APK 构建、Android lint，并在一加测试机覆盖安装。新增网络库选择入口，保留 Root / Shizuku 切换。

此前 Root 真机验证涵盖实际替换与精确字节恢复、原图备份与自定义状态保留、钱包数据库哈希不变；离线素材验证涵盖 SHA256、尺寸、原附件、重复导入、损坏文件修复和图库导出。两库的拖动滚动、搜索、图库选择与裁切流程已验证。手动同步曾校验并跳过全部 842 张主站原图，图片下载数为零。

## 素材来源与致谢

- [Cardentify](https://cards.no2.ac/) 与 [GitHub 投稿](https://github.com/no2ac/Cardentify/issues)。
- 小米钱包公开公交产品目录，以及 MiPayCard 社区目录和原 APK 内置素材。
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)，使用 MIT 许可证，完整声明见 [SHIZUKU-LICENSE.txt](app/src/main/assets/SHIZUKU-LICENSE.txt)。

卡面图片的权利归各自权利人，来源标注沿用原目录，未将社区作品逐张认证为官方作品。小米特殊银行卡面与个性化主题尚未发现可查询全量的接口。素材与第三方组件说明见 [第三方说明](docs/第三方说明.md)。

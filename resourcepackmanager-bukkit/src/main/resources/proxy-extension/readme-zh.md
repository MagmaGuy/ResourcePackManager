# ResourcePackManager — 代理端插件

> **⚠ 此插件仅对运行 Geyser + Floodgate 的服务器有用。**
> 它的唯一作用是把每个后端转换好的 Bedrock 资源包合并起来,通过 Geyser 提供给
> Bedrock 客户端。**Java Edition 玩家不需要这个插件** — 网络环境下 Java 资源包
> 的分发由各个后端直接处理。如果你的网络只有 Java(没有通过 Geyser 接入的
> Bedrock 玩家),你可以完全忽略此文件夹。

此文件夹包含 **RSPM 代理端插件**的 JAR 文件。这些 JAR 应放在你的**代理服务器**
(Velocity / BungeeCord / Waterfall)上,**不要**放在 Minecraft 后端服务器上。
代理插件会把每个后端转换好的 Bedrock 资源包合并成一个统一的包,通过 Geyser 提供
给 Bedrock 客户端。

如果你运行的是单服务器(前面没有代理),可以完全忽略此文件夹。无论如何,JAR 在
每次启动时都会被解压出来,所以即使你以后加上代理,文件也已经准备好了。

---

## 我该用哪个 JAR?

根据你的代理软件,**仅选择一个**:

| 代理软件                  | 使用此 JAR                                |
|--------------------------|------------------------------------------|
| Velocity                 | `ResourcePackManager-Velocity.jar`       |
| BungeeCord               | `ResourcePackManager-BungeeCord.jar`     |
| Waterfall (Bungee 分支)   | `ResourcePackManager-BungeeCord.jar`     |

不要同时安装两个 — 只装匹配的那个。同时安装会让试图加载错误 JAR 的平台在启动时
报错。

---

## 安装 — 2 步

### 1. 把 JAR 复制到代理主机

把对应的 JAR 从**此文件夹**复制到代理的 `plugins/` 目录。常见方法:

- **同一台机器**: 直接拖放,或者 `cp` / `copy`。
- **远程代理**: `scp ResourcePackManager-Velocity.jar 用户名@代理主机:/path/to/proxy/plugins/`
- **托管代理面板**(如 Pterodactyl): 通过面板的文件管理器上传。

### 2. 重启代理

完成。无需编辑配置,无需粘贴任何 key。

插件在启动时读取代理上的 `plugins/floodgate/key.pem`,并从该文件自动派生
网络身份。由于 Floodgate 本来就要求这个文件在每个后端**和**代理上完全一致
(Bedrock 验证必须如此),派生出来的 key 自动与每个后端匹配。

第一次合并通常在所有后端启动后 ~10 秒内完成。

---

## 验证是否工作正常

**代理控制台**(重启后约 10 秒内,假设后端正在运行):

```
[ResourcePackManager] Merged pack ready at .../merged/Bedrock.zip (sha1 ...)
[ResourcePackManager] Geyser mappings deployed to .../custom_mappings
[ResourcePackManager] ✔ Network resource pack is now ready (... KB, sha1ABCD1234)
```

**Bedrock 客户端**: 通过 Bedrock 连接。在进入世界之前应该看到资源包下载提示。
自定义物品会以预期的模型渲染,而不是普通的盔甲架。

**`/rspm status`**(在后端运行): 显示资源包状态、托管模式以及 network-key 的
指纹。把最后 4 个字符和代理的配置对比,以确认两边已连接。

---

## 常见问题

### 代理启动时报 "Floodgate key.pem missing"

代理插件找不到 `plugins/floodgate/key.pem`,因此处于空闲状态。修复方法:

1. 在代理上**安装 Floodgate**。Bedrock 玩家本来就需要它才能接入代理,
   所以无论是否使用 RSPM,你都需要装它。
2. 确保代理上的 `plugins/floodgate/key.pem` 与每个后端上的同一文件
   **逐字节相同**。Floodgate 默认会为每次安装生成不同的 key — 从任意一个
   后端复制一份规范的 `key.pem` 到其他所有组件(其他后端 + 代理),然后
   全部重启。Floodgate 本身就要求这一点用于 Bedrock 验证,所以如果你的
   网络上 Bedrock 玩家目前能正常游玩,这一步已经完成。

### Bedrock 能连上但看不到自定义模型

最常见的原因: 代理在后端**生成第一个 Bedrock 资源包之前**就启动了。Geyser
**只在启动时**注册自定义物品 — 一旦它在空 mappings 文件的情况下跑起来,就会
保持这种状态。请在后端日志出现 `Wrote merged Geyser mappings: N entries` 之后
**重启代理**。之后的合并会被 Geyser 的资源包分发路径自动接收,但自定义物品表
是在启动时固定的。

### 代理启动时出现 "Duplicate bedrock_identifier" 警告

两个后端为同一个基础物品生成了相同的 Bedrock 标识符。最后写入者胜出 — 如果你
只需要一个后端提供该物品,这是无害的。如果两个后端都应该在同一个基础物品下
托管**不同**的自定义物品,这就是真正的冲突 — 重命名其中一个源 Java 模型,
让自动生成的哈希值不同。

### 更新 RSPM

更新后端 RSPM JAR 之后,也要把此文件夹里对应的
`ResourcePackManager-Velocity.jar` / `-BungeeCord.jar` **重新复制**到代理,
并重启代理。后端每次启动都会重新生成它们,所以它们始终和后端版本同步。

---

由 ResourcePackManager v${version} 在后端启动时生成。
其他语言版本: `README.md`(英语 / English),`README - espanol.md`,
`README - francais.md`,`README - portugues.md`,`README - hindi.md`,
`README - bangla.md`,`README - arabi.md`,`README - russkij.md`,`README - urdu.md`。

# Termux Owner

**让终端直接使用 Device Owner。**

这个项目的出发点很简单：让 Termux 里的终端命令，借用 Device Owner 的权限完成普通 shell 做不到的事，而不是必须先 root 或长期依赖电脑 ADB。

它不是 root 工具，也不修改系统分区。运行时由 Dhizuku 担任 Device Owner，由 Shizuku 提供无 root 的 shell 通道，`owner` 负责把两者接起来。

## 架构

```text
Termux
  │  ~/dex/owner
  ▼
app_process + own.dex (sysr.Main)
  │
  ├─ Shizuku rish ──► Android shell (uid 2000)
  │                     pm / appops / settings / cmd
  │
  └─ Dhizuku ────────► Device Owner
                        install / uninstall / DPM / transfer
```

- **Termux**：命令入口。
- **Shizuku**：提供 uid 2000 的 shell 复用通道。
- **Dhizuku**：实际持有 Device Owner 权限。
- **owner**：本项目的 CLI 和 dex，负责命令分发、Dhizuku 调用和静默安装。

## 本仓库内容

本仓库只包含 **owner 自身**的源码和现有产物：

```text
owner/
  owner                 # CLI 脚本
  own.dex               # 现有构建产物
  src/sysr/Main.java    # 命令入口、shell/Dhizuku 分发
  src/sysr/Owner.java   # Dhizuku/Device Owner 调用
  src/sysr/Observer.java
```

Shizuku、Dhizuku、Termux:API 都是独立项目；本仓库不包含它们的完整源码。

`own.dex` 是现有构建产物。重新构建 owner 源码需要 Android framework 的 `android.jar` 以及 Shizuku 的 rish bootstrap 类；仓库只保留 owner 自身源码，不内置这些第三方依赖。

Termux:API 的魔改源码在另一个仓库：  
<https://github.com/httpspycharmhelpers/Termux-API-Dhizuku>

## 发布产物

Release 页面提供三个文件：

| 文件 | 说明 |
| --- | --- |
| `Termux-Owner-v1.0.0.zip` | `owner` 脚本 + `own.dex` |
| `Termux-API-Dhizuku-v0.53.0.apk` | 带 Dhizuku/Device Owner 集成的 Termux:API |
| `Shizuku-v13.6.0.apk` | 第三方 Shizuku 安装包，原样分发 |

`SHA256SUMS` 也在 Release 中。

## 安装 owner

```sh
mkdir -p ~/dex
unzip Termux-Owner-v1.0.0.zip -d ~/dex
chmod +x ~/dex/owner
~/dex/owner -c admins
```

前置条件：

1. Termux 已安装。
2. Shizuku 已启动，Termux 已获授权。
3. Dhizuku 已取得 Device Owner。
4. 已安装 `Termux-API-Dhizuku`，并打开过一次应用，让 `com.termux.api.owner` provider 处于可用状态。
5. Termux:API 已获得 Dhizuku API 权限。

## 常用命令

```sh
# 查看 Device Owner / active admins
~/dex/owner -c admins

# 纯 Dhizuku 静默安装（不需要 Shizuku 的 pm install）
~/dex/owner -c install /sdcard/Download/app.apk

# 通过 Shizuku shell 执行
~/dex/owner shell 'pm list packages | head'

# 冻结、解冻、强制停止
~/dex/owner freeze com.example.app
~/dex/owner unfreeze com.example.app
~/dex/owner force-stop com.example.app

# Device Owner 管理
~/dex/owner dpm transfer com.example/.AdminReceiver
~/dex/owner dpm install-existing com.example.app
~/dex/owner dpm grant com.example.app android.permission.SOME_PERMISSION
```

完整用法：

```sh
~/dex/owner help
```

## 证书与校验

发布 APK 的签名证书已核对：

### Termux-API-Dhizuku-v0.53.0.apk

- DN: `CN=APK Signer, OU=Earth, O=Earth`
- SHA-256: `b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1`
- SHA-1: `517955eabf69fc057c41c7d379dbbcef20ad85f2`

这是开发/测试签名。要发布正式版，应使用自己的 release keystore 重新签名；更换签名后旧版本无法直接覆盖安装。

### Shizuku-v13.6.0.apk

- DN: `CN=Rikka`
- SHA-256: `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`
- SHA-1: `2900b8e762737549d63c41056906b5cdaeef6e18`

该 APK 为已安装版本的原样副本，未做二次签名。

### 产物 SHA-256

```text
be0a1d334c785896edf306aead78b2bce18bfa5006e19d99e78dbd126512d7cc  Termux-Owner-v1.0.0.zip
c21b4652315caa034d8de5793590e1b459b1b78633183599ddb04098f17a6a82  Termux-API-Dhizuku-v0.53.0.apk
6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f  Shizuku-v13.6.0.apk
```

## 安全说明

- `owner` 的能力来自 Device Owner 和 Shizuku；请只在你信任的设备和应用上使用。
- `owner -c` 会通过 `com.termux.api.owner` provider 执行命令；该 provider 使用 `android.permission.DUMP` 保护。
- 不要在不可信脚本中传入未知包名或未知 Device Owner 组件。
- 本工具不提供 root，不修改系统分区，也不绕过 Android 的签名校验。

## License

本仓库中 owner 自身代码以 **GPL-3.0** 发布。第三方组件保留各自许可证，见 `NOTICE.md`。

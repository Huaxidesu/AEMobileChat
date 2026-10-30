# GTNHChat

面向 Minecraft 1.7.10（GTNH / Thermos）服务端的聊天格式化插件。

把玩家聊天渲染成带悬停提示和点击动作的富文本，并支持 BungeeCord 网络内的跨服广播。
单个 jar，只装在服务端，代理端无需安装任何插件。

## 特性

- 按权限和优先级匹配聊天格式规则
- 格式片段支持颜色码、多行悬停、点击执行命令 / 补全 / 打开链接
- 跨服广播走 BungeeCord 内置 `Forward` 通道，代理端零安装
- 支持按组隔离，不同组的服务器互不可见
- 无任何联网行为：不检查更新、不上报数据、不加载远程配置

## 环境要求

| 项 | 版本 |
|---|---|
| 服务端 | Minecraft 1.7.10 + Thermos / Cauldron 类实现 |
| Java | 8 |
| 代理（可选） | BungeeCord 或其 1.7 兼容分支 |

## 构建

```bash
git clone <repo>
cd GTNHChat
./tools/fetch-deps.ps1     # 下载编译所需的 API jar 到 lib/
./gradlew build
```

产物在 `build/libs/`。

依赖不随仓库分发，`lib/` 已在 `.gitignore` 中。

## 使用

1. 把 jar 放进服务端 `plugins/`
2. 启动一次生成默认配置
3. 编辑 `plugins/GTNHChat/config.yml` 和 `format.yml`
4. `/gtchat reload` 生效

配置说明见文档。

## 许可

[MIT](LICENSE)

## 说明

本项目为独立实现。不包含、不复制任何第三方插件的代码。

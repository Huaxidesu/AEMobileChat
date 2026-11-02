# AGENTS.md —— 交接入口

GTNHChat：面向 Minecraft 1.7.10（GTNH / Thermos）服务端的聊天格式化与跨服转发模组。
单 jar，服务端必需，客户端可选，代理端零安装。MIT，公开仓库。

## 接手先做三件事

1. 读 `doc/README.md`，按索引挑要看的文档。
2. 读 `doc/编码规范.md`，写代码和文档都按它来。
3. 读 `doc/开发计划.md` 的「未验证」一节，那里列着还需要真人玩家才能确认的部分。

## 硬性规矩

| 规矩 | 说明 |
|---|---|
| **`doc/` 不进 git** | 已在 `.gitignore`。不要 `git add -f` |
| **push 交给仓库所有者** | 本地 `git commit` 可以做，`git push` 不做 |
| **不复制第三方代码** | 与 NEI 的交互只走反射和一处注入，不打包、不复制对方代码 |
| **配置改动写进默认文件** | 默认值在 `src/main/resources/defaults/`，只改代码不改默认值等于没改 |

## 当前状态

代码已完成并可构建，产物 `build/libs/gtnchat-1.7.10-<版本>.jar`。
本机服务端已装该 jar 并成功启动，`/gtchat status` 各项正常。

客户端回执（`TYPE_CLIENT_READY`）自 1.1.2 起帧尾带一个能力位字节，目前只有一位
`CAP_CHAT_HEADS`：客户端会用反射读 `NecroTempusConfig.ChatHeadsEnabled` 上报，
服务端据此在名字左边补一个空格（`chat.chat-heads: auto|on|off`，见 `chat/ChatHeads.java`）。

未验证的部分（需要在线玩家 / 第二台服务器）见 `doc/开发计划.md`。

## 目录

```
build.gradle / gradle.properties / dependencies.gradle   构建
src/main/java/com/huaxidesu/gtnchat/
  chat/      颜色码解析、规则匹配、组件组装；TeamInfo（ServerUtilities 队伍）、
             WorldNames（MV alias / 家园世界 / 内置维度表）、
             ChatHeads（客户端画聊天头像时名字留边）
  config/    两份 YAML 的解析与内存模型
  relay/     跨服帧编解码、去重、收发
  net/       模组网络通道
  bridge/    交给 AEMobile（手机端）的元数据，反射调用，没装时停用
  nei/       物品链接跨服桥（反射）
  client/    成就检测与握手（仅客户端）
  command/   /gtchat
  mixins/    唯一一处注入 + Mixin 配置插件
  util/      主线程队列、反射小工具
src/main/resources/
  mcmod.info
  mixins.gtnchat.json
  defaults/config.yml, defaults/style.yml   首次启动写出的默认配置
doc/         本地文档，不进 git
```

## 构建

```bash
cd <仓库目录>
export JAVA_HOME="D:/Library/DevelopmentKit/Java/Zulu/zulu8"
./gradlew build -x test --offline      # 增量约 20 秒
```

产物在 `build/libs/`。**工作区有未提交改动时文件名带 `.dirty`**，
拷贝部署时注意别拿到旧包（先 `ls -la` 看时间戳）。

版本号来自 git tag，仓库至少要有一个 tag，否则构建直接报错。

## 部署与测试（本机环境）

| 项 | 路径 |
|---|---|
| 测试服务端 | `D:\Game\Minecraft\Server\GTNH290RC2` |
| 模组安装位置 | `<服务端>\mods\gtnchat-1.7.10-<版本>.jar` |
| 生成的配置 | `<服务端>\config\gtnchat\config.yml`、`style.yml` |
| 代理 | `D:\Game\Minecraft\Server\BC` |

启动：

```bash
cd "D:/Game/Minecraft/Server/GTNH290RC2"
cmd //c start-aem-detached.bat          # 独立窗口，不占调用方终端
```

启动约 60 秒（日志出现 `Done (`）。**换 jar 前必须停服**：

```bash
netstat -ano | grep ":25588"            # 拿 PID
taskkill //F //PID <pid>
```

等 20 秒再启动，否则端口还在 `TIME_WAIT`，启动脚本会跳过。

在没人聊天时验证（借用本机调试模组的命令桥）：

```bash
cd "D:/Game/Minecraft/Server/GTNH290RC2"
printf 'gtchat status\n' > dumps/aemobile/cmd.txt
# 回显在 logs/latest.log，不在 cmd.out（cmd.out 只有返回码）
iconv -f GBK -t UTF-8 logs/latest.log | grep "GTNHChat"
```

可用子命令：`reload` / `version` / `status` /
`test chat <内容>` / `test json [规则名] <内容>` / `test bench [次数]` /
`test achievement <成就ID>` / `test relay <内容>` / `test nei`。

- `test chat`：无人在线时把渲染后的组件树摊平回显，逐段核对颜色、悬停、点击
- `test json`：输出真正发给客户端的 JSON，看 `color` / `clickEvent` / `hoverEvent`
- `test bench`：渲染开销基准
- `test relay`：造一条「来自其它服务器」的消息，验证来源服务器名与接收端渲染

跨服要两台后端才测得出来：两台都装本模组、`server.id` 必须不同，
同组不需要配置（本项目不做分组）。

## 与其它模组的关系

- **NEI**：物品链接的数据只存在于生成它的服务器上，因此加了一处注入把数据取出来随聊天转发。
  没装 NEI 时注入不加载，其余功能不受影响。
- **任务书（BetterQuesting）**：任务分享是一条普通聊天，本项目只保证标记串原样连续。
- **客户端**：不装也能进服，只是没有成就公告。

## 已知坑

见 `doc/踩坑.md`。最容易栽的三条：

1. FML 网络事件不在 Forge 总线上，监听不了别人的通道。
2. 聊天事件在网络线程，渲染可以在里面做完，但广播必须回主线程。
3. 服务端日志是 GBK，`grep` 中文前先 `iconv`。

# 项目群管理

本目录是工作室 USB 屏幕（当前为 MSU2 系列）的三方开发项目群，包含 Java 驱动库、Dart/Flutter 上位机，以及后续可能出现的其他语言实现。用一个 git 仓库管理，但不要做成跨语言的统一构建。

## 路线图

当前要做的事、阶段顺序和机型范围见根目录 [`roadmap.md`](roadmap.md)。浏览器上位机用 Dart + Flutter，不再用 TypeScript。调整阶段或范围时先改这份文件。

## 工具链

- Java：使用 JDK 25，本机 JDK 位于 `C:\environments\jdk-25`。Java 子项目使用 Maven 做依赖管理，`mvn` 可直接使用。
- Dart：Dart SDK 位于 `C:\environments\dark-sdk-3.14.1`。Dart 库须同时可编译运行在 Web 与 Windows（原生 VM）；当前只面向这两个环境测试。
- Flutter：本机 SDK 位于 `C:\environments\flutter`。上位机工程 `dart/moli_usb_gui` 使用它，目标环境为 Windows 与 Web。协议库 `dart/moli_usb_protocol` 仍不得依赖 Flutter。

## 变更记录

需要记录 changelog 时，把条目追加到根目录 [`changelog.md`](changelog.md)。文件不存在就先创建它。不要把变更记录写进项目的 `README.md` 或 `AGENTS.md`。

## 命名空间与包坐标

所有对外发布的包统一归属命名空间 `space.firok`，各语言按自身语法习惯映射：

- Java：Maven `groupId` 使用 `space.firok.moli.usb`，Java 包名对应为 `space.firok.moli.usb.api.*`。模块 `artifactId` 保持 `moli-usb-api` 这类设备/职责短名，不把命名空间重复写进 `artifactId`。
- Dart：pub 包名不允许包含点号，只允许小写字母、数字与下划线，因此采用下划线拼接形式 `space_firok_moli_usb_protocol`。引用路径为 `package:space_firok_moli_usb_protocol/...`。本地目录仍使用短名（如 `dart/moli_usb_protocol`），不为了对齐包名而拉长路径；`pubspec.yaml` 的 `name` 才是发布标识。Flutter 上位机同样处理：目录 `dart/moli_usb_gui`，包名 `space_firok_moli_usb_gui`。
- 新增其它语言的实现时，按该语言的常规约定映射同一个 `space.firok` 根命名空间，并在本节补一行说明。

命名空间只在包坐标和源码声明上体现，不要用它给目录分层。目录仍按 `AGENTS.md` 的目录边界规则使用小写短名。

## 仓库形态

- 这是轻量 git monorepo。协议、文档、各语言 SDK 与示例放在同一仓库，是因为它们共享同一套设备协议；协议、描述符或指令变更时，相关实现和文档要能在同一次改动里对齐。
- 这不是单一构建系统。不要引入 Nx、Turborepo、Bazel，也不要添加顶层统一构建。
- Java 与 Dart/Flutter 没有编译期依赖。跨语言只共享 `spec/` 与 `docs/`，不要互相引用源码或构建产物。
- 对外按包发布。第三方消费的是 Maven 构件或独立的 Web 程序，不需要克隆本仓库。
- 每个可发布包使用独立版本号。仓库本身不设总版本。

## 目录边界

- `docs/`：硬件与开发指南。已有 MSU2 使用及开发指南。
- `spec/`：协议真源。命令字、报告描述符、错误码、机型与版本矩阵放这里。新的协议事实先改这里，再改各语言实现。
- `java/`：Maven 多模块。`java/pom.xml` 只聚合 Java，parent 也放这里。
- `web/`：独立的浏览器项目。当前规划的上位机不放这里，它是 `roadmap.md` 中的 Dart/Flutter 工程。这里的子目录若采用 npm，则自带 `package.json` 与 lockfile，不引用 `java/`。
- `tools/`：抓包、描述符生成等辅助工具。只有确实跨子项目复用时才添加。

尚不存在的目录在第一次添加对应子项目时创建，不要预先搭空模块。

## 添加子项目

先判断它和谁有构建依赖，再决定位置。不要为了目录整齐，把无关项目塞进同一个构建。

1. 与现有 Java 库有 Maven 依赖、父子关系，或需要同一 reactor 一起编译：放进 `java/`，并登记到 `java/pom.xml` 的 `<modules>`。模块间依赖使用 reactor 坐标，不要先发布再依赖自己。
2. parent POM 只给本仓库继承时，保持在 `java/pom.xml`，不要把它当成对外发布的继承点。只有外部项目确实要继承时，才单独发布 parent。
3. 独立的浏览器或网页工具：放进 `web/<name>/`。`roadmap.md` 里的 Flutter 上位机不属于这类项目，它和纯 Dart 协议库放在独立的 Dart 工程中。不要把这些项目做成 Java 子模块，也不要让 Java 构建调用它们。
4. 与现有语言都没有构建依赖，只是同一设备协议的另一种实现或示例：新建对应语言的顶层目录，例如 `python/`、`csharp/`，并在该目录内自包含构建。不要为了它修改 Java 或 Web 的构建文件。
5. 协议、指令、描述符或兼容性事实：写入 `spec/`，不要只写进某个语言的 README。实现以 `spec/` 为准。
6. 只服务一个子项目的脚本、夹具或示例：放在该子项目内部，不要上提到仓库根。
7. 新子项目必须能单独构建和测试。CI 按路径触发：改 `java/` 不跑 Web，改 `web/` 不跑 Maven；改 `spec/` 时运行所有依赖该协议的检查。

目录使用小写短名，并体现设备或职责，例如 `java/moli-usb-api`、`web/webusb-console`。不要使用 `misc`、`temp`、`project1` 这类名字。

## 不要做的事

- 不要在子目录里再套独立 git 仓库。
- 不要让 Web 项目依赖 Java 模块，也不要让 Java 依赖 `node_modules`。
- 不要把无关实验、一次性脚本或密钥放进本仓库。
- 不要因为两个项目以后可能有关，就提前建立构建依赖。
- 不要用一个仓库总版本号绑住所有包。

## 何时拆仓

默认不拆。只有出现下面任一情况，才把对应子树拆成独立仓库：

- 不同协作者需要不同的读权限。
- 某一块要单独开源、更换许可证，或交给外部维护。
- 协议已经稳定，且该子项目的发布节奏与其他包完全无关。

拆之前保持目录边界清晰，使子树可以整目录搬走。

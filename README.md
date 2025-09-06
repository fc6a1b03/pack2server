# pack2server

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=java)](https://adoptium.net)
[![Gradle](https://img.shields.io/badge/Gradle-9.0-02303A?style=flat-square&logo=gradle)](https://gradle.org)
![GitHub](https://img.shields.io/github/license/fc6a1b03/pack2server?style=flat-square)

pack2server 是一个将 CurseForge Minecraft 模组整合包一键转换为可直接运行的服务器目录的工具。

## 功能特性

- 🚀 **一键转换** - 将 CurseForge 整合包快速转换为可运行的服务器
- ⚡ **高性能** - 利用 Java 21 虚拟线程，充分压榨多核 CPU，实现零阻塞操作
- 🔧 **智能过滤** - 自动识别并过滤掉客户端专用模组，仅保留服务端所需模组
- 📦 **完整支持** - 支持 Fabric、Forge、Quilt 等主流模组加载器
- 🌐 **批量下载** - 并行下载模组文件，提高转换效率
- 🗃️ **配置保留** - 自动处理整合包中的配置文件和资源文件

## 核心流程

1. **下载** - 如果提供的是 URL，则先下载整合包
2. **解压** - 解压整合包到临时目录
3. **模组获取** - 根据 `manifest.json` 批量下载所需模组
4. **服务端过滤** - 检测并移除客户端专用模组
5. **文件复制** - 复制配置文件和其他资源文件
6. **加载器生成** - 自动生成对应的服务端加载器
7. **清理** - 删除临时文件，完成转换

## 系统要求

- Java 21 或更高版本
- 可访问 CurseForge API 的网络连接（用于下载模组）
- 对于某些模组，可能需要 CurseForge API 密钥

## 安装

### 从源码构建

```bash
# 克隆项目
git clone https://github.com/fc6a1b03/pack2server.git
cd pack2server

# 构建项目
./gradlew build

# 运行
java -jar build/libs/pack2server-*.jar
```

### 直接下载

前往 [Releases](https://github.com/fc6a1b03/pack2server/releases) 页面下载最新版本。

## 使用方法

### 基本用法

```bash
# 通过 URL 转换整合包
java -jar pack2server-*.jar convert -u '整合包下载链接' -o './server'

# 通过本地文件转换整合包
java -jar pack2server-*.jar convert -z '本地整合包路径' -o './server'

# 强制覆盖已有目录
java -jar pack2server-*.jar convert -u '整合包下载链接' -o './server' -f

# 提供 CurseForge API 密钥
java -jar pack2server-*.jar convert -u '整合包下载链接' -k '你的API密钥' -o './server'
# 或使用环境变量
CF_API_KEY='你的API密钥' java -jar pack2server-*.jar convert -u '整合包下载链接' -o './server'
```

### 启动服务器

转换完成后，进入输出目录并运行生成的启动脚本：

```bash
cd server
java -jar fabric-server.jar --nogui
```

## 命令行参数

### convert 子命令

| 参数         | 简写   | 描述                                   |
|------------|------|--------------------------------------|
| `--url`    | `-u` | CurseForge 整合包下载链接                   |
| `--zip`    | `-z` | 本地 CurseForge 整合包路径                  |
| `--output` | `-o` | 输出服务器目录（默认：./server）                 |
| `--force`  | `-f` | 覆盖现有目录                               |
| `--key`    | `-k` | CurseForge API 密钥（支持 env:CF_API_KEY） |

> 注：`--url` 和 `--zip` 必须二选一提供

## 技术栈

- Java 21
- Gradle 9.0
- [Hutool](https://hutool.cn/) - Java 工具库
- [Picocli](https://picocli.info/) - 命令行解析器
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) - 压缩文件处理

## 开发

### 项目结构

```
pack2server/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── cloud/dbug/pack2server/
│   │           ├── cli/           # 命令行接口
│   │           ├── common/        # 通用工具类
│   │           │   ├── detector/  # 模组检测器
│   │           │   ├── downloader/ # 下载器
│   │           │   ├── fetcher/   # 数据获取器
│   │           │   └── provider/  # 版本提供者
│   │           └── entity/        # 实体类
│   └── test/
└── build.gradle                  # 构建配置
```

### 构建项目

```bash
# 构建项目
./gradlew shadowJar

# 运行测试
./gradlew test

# 查看项目版本
./gradlew printVersion

# 查看所有版本信息
./gradlew printAllVersions
```

## 许可证

本项目采用 MIT 许可证，详情请见 [LICENSE](LICENSE) 文件。
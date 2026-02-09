# 运行 simple-client

## 1. 先编译 TDLib 本地库（必须做一次）

否则会报错：`UnsatisfiedLinkError: ... nativeClientExecute ...`

- 进入构建目录：
  ```bash
  cd libs/build
  ```
- **macOS 首次**：安装依赖（若未装过）：
  ```bash
  ./prepare_env_macos.sh
  ```
  需要：Xcode 命令行工具、Homebrew 的 gperf cmake openssl coreutils。
- 编译并安装到项目目录：
  ```bash
  ./build_macos.sh
  ```
  脚本会把 `libtdjni.dylib` 复制到：
  - Apple Silicon (M1/M2)：`libs/macos_silicon/`
  - Intel (x86_64)：`libs/macos_x64/`

## 2. 用正确的库路径启动应用

JVM 必须能加载 `libtdjni`，需要加上 `-Djava.library.path=...`。

**按架构二选一**（在项目根目录执行）：

- Apple Silicon：
  ```bash
  cd simple-client
  mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Djava.library.path=../libs/macos_silicon"
  ```
- Intel (x86_64)：
  ```bash
  cd simple-client
  mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Djava.library.path=../libs/macos_x64"
  ```

或先打包再运行：

```bash
cd simple-client
mvn package -DskipTests
java -Djava.library.path=../libs/macos_x64 -jar target/simple-client-0.0.1-SNAPSHOT.jar
```

（Apple Silicon 把 `macos_x64` 改成 `macos_silicon`。）

## 3. 环境变量

在 `application.properties` 里用环境变量配置 Telegram API，运行前请设置：

- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`
- `TELEGRAM_API_PHONE`
- `TELEGRAM_API_DATABASE_ENCRYPTION`

并把 `spring.telegram.client.database-directory` 改成你本机要用的路径（可选）。

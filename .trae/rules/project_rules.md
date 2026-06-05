# Project Rules: dashensou (Android)

## 每次完成功能更新或 Bug 修复后：自动打包 Debug 并安装到手机

适用范围：本项目（`e:\New\dashensou`，applicationId `com.dashensou.app`）内任何代码、资源、依赖、配置变更之后。

参考 skill：`android-build-install`（全局生效，已经记录了本机 JBR / SDK / Gradle / adb 的所有路径）。

### 执行流程

1. 工作目录固定为 `e:\New\dashensou`。
2. 设置环境变量并执行构建（直接走系统 Gradle 8.5，绕开常见的 `gradlew` 找不到 wrapper 问题）：

   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:ANDROID_HOME = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
   $env:ANDROID_SDK_ROOT = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
   & "C:\Users\Squema-Mini\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon
   ```

3. 产物路径：`e:\New\dashensou\app\build\outputs\apk\debug\app-debug.apk`。
4. 查询已连接设备：

   ```powershell
   & "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
   ```

5. 从结果中取第一台 `device` 状态的设备（USB 优先），安装：

   ```powershell
   & "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <deviceId> install -r -t "e:\New\dashensou\app\build\outputs\apk\debug\app-debug.apk"
   ```

6. 验证：`adb -s <deviceId> shell pm list packages com.dashensou.app`。

### 失败处理

- 编译失败：按 `android-build-install` skill 的 8 类常见错误修（KAPT→KSP、jlink 缺失、cornerRadius 不兼容、styles 属性缺 `android:` 前缀、`R` 缺失等），不要盲重试。
- `adb devices` 列表为空：提示用户确认 USB 调试已开、已授权；不要硬编设备 id。
- 设备掉线（`device '…' not found`）：重新跑 `adb devices` 取最新 id 再 install。
- 源文件未变、APK 已存在：可以直接 `install -r`，不必全量 rebuild。

### 跳过条件（不需要构建/安装）

- 只修改了 `.trae/` 目录、纯文档 (`*.md`)、注释、格式调整。
- 用户明确说"先别打包 / 先别装"。
- 仅做排查（`logcat`、`adb` 调试、未改源码）的情况。

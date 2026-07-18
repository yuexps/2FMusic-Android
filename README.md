## 2FMusic for Android

2FMusic 音乐播放器的 Android 客户端，基于 Kotlin Multiplatform (KMP) 和 Miuix 构建。

### 连接要求
* **服务端模式**：主程序必须以 **并发模式 (Port + Socket)** 运行。
* **端口开放**：确保服务端端口（默认 23237）对外开放，以便客户端连接。

### 配置方法
1. 进入客户端的 **系统设置** -> **后端配置**。
2. 填写 **服务器地址**（如 `http://<IP>:<端口>`）及 **访问密码**，保存即可。

### 开发参考
* miuix 文档：https://compose-miuix-ui.github.io/miuix/zh_CN/guide/getting-started
* miuix 组件库：https://compose-miuix-ui.github.io/miuix/zh_CN/components
* miuix 图标：https://compose-miuix-ui.github.io/miuix/zh_CN/guide/icons

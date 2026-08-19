# 桌面堡垒：合成战记

《桌面堡垒：合成战记》是一款面向 PICO 空间计算平台的 MR 桌面塔防游戏。玩家在真实地面上校准并放置棋盘，购买、摆放和合成防御塔，抵御分波出现的怪物。

## 主要功能

- 基于 PICO Spatial SDK 的 Shared Space 菜单与 Mixed Stage 游戏空间
- 地面感知、棋盘校准、世界空间固定与射线落格交互
- 四条防御塔成长线、六格武器卡槽、拖拽放置、合成与出售
- 七类怪物、20 个关卡、波次、Boss、经济与星级结算
- 永久养成、本地存档、图鉴、成就与调试面板
- 适配 PICO OS 的空间分层应用图标

## 技术栈

- Kotlin / Android
- PICO Spatial SDK BOM 0.13.3
- SpatialUI、Spatial ECS、Sense
- MVVM、Kotlin Coroutines、Flow、AndroidX Lifecycle
- Gradle Kotlin DSL

## 构建

环境要求：Android Studio 2025.1.x、JDK 17、Android SDK 35。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

调试 APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 工程结构

```text
app/src/main/java/com/example/desktopfortress/
├── audio/       音频管理
├── data/        存档与数据仓库
├── effect/      特效管理
├── manager/     游戏业务管理器
├── model/       数据模型与配置
├── platform/    PICO Spatial 平台入口
├── ui/          SpatialUI、Activity 与场景界面
├── utils/       工具与扩展函数
└── viewmodel/   页面与游戏 ViewModel
```

## 真机说明

平面感知、空间锚点、头部跟随以及手部/手柄射线交互需要在支持 PICO Spatial SDK 的真机上验证。不要使用二维 ADB 点击模拟空间交互。

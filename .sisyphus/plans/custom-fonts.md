# 自定义字体功能实现计划

## 需求概述

支持在 `xime.custom.yaml` 中配置自定义字体，字体文件放在 rime（方案）目录。

配置项：
1. 按键主字字体
2. 字根字体（气泡中显示的拆分字根）
3. 候选项字体
4. 注释字体

使用场景：
- 键盘显示字根（五笔等形码输入法）
- 候选 comment 显示拆分

## 现状分析

### 字体管理（Fonts.kt）
当前只有一个内置字体 ChaiPUA（CJK 扩展区字符），从 assets 加载。

### 字体使用位置

| 位置 | 文件 | 当前实现 |
|------|------|---------|
| 按键主字 | KeyButton.kt | 系统默认字体 |
| 下滑提示（字根） | KeyButton.kt:721 | ChaiPUA FontFamily |
| 气泡文字 | SwipeBubble.kt:367 | ChaiPUA Typeface (native Paint) |
| 候选项 | CandidateBar.kt | 系统默认字体 |
| 候选注释 | CandidateBar.kt:631 | 系统默认字体 |
| 全屏候选 | CandidatePage.kt | 系统默认字体 |

### 配置机制
- 使用 kaml 库解析 YAML
- `xime.yaml` 为内置默认配置
- `xime.custom.yaml` 为用户覆盖配置
- 字号配置 `candidate_text_size` 在 SettingsPreferences 中

### Trime 参考
Trime 支持多种字体配置（`text_font`, `candidate_font`, `comment_font`, `key_font` 等），字体文件放在 `fonts/` 目录，使用 `Typeface.createFromFile()` 加载。

## 实现方案

### 1. 配置格式设计

在 `xime.yaml` 的 `style` 节点下添加字体配置：

```yaml
style:
  # 字体配置（字体文件放在 rime/ 目录下）
  key_font: ""                    # 按键主字字体
  key_label_font: ""              # 按键标签/字根字体（气泡中显示）
  candidate_font: ""              # 候选项字体
  comment_font: ""                # 候选注释字体
```

### 2. 修改文件清单

#### 2.1 新增：字体配置数据结构
**文件**：`app/src/main/java/com/kingzcheung/xime/settings/KeysConfigHelper.kt`

添加 `KeyboardFontConfig` 数据类：
```kotlin
data class KeyboardFontConfig(
    val keyFont: String = "",
    val keyLabelFont: String = "",
    val candidateFont: String = "",
    val commentFont: String = "",
)
```

添加解析函数 `parseKeyboardFontsFromAssets()`。

#### 2.2 修改：字体管理器
**文件**：`app/src/main/java/com/kingzcheung/xime/ui/keyboard/Fonts.kt`

- 添加 `initialize(context: Context, fontConfig: KeyboardFontConfig)` 方法
- 添加从文件系统加载字体的能力（`Typeface.createFromFile()`）
- 支持 Compose FontFamily 加载（`Font.Builder(file).build()`）
- 提供以下字体访问器：
  - `keyTypeface` / `keyFontFamily` — 按键主字
  - `keyLabelTypeface` / `keyLabelFontFamily` — 字根标签
  - `candidateTypeface` / `candidateFontFamily` — 候选项
  - `commentTypeface` / `commentFontFamily` — 注释
- 配置为空时回退到系统默认字体（`Typeface.DEFAULT`）
- 保留 `chaiPuaTypeface` 作为 CJK 扩展区字符的回退

#### 2.3 修改：按键显示
**文件**：`app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyButton.kt`

- 第 721 行：下滑提示字体改为 `Fonts.keyLabelFontFamily`
- 主按键文字可选使用 `Fonts.keyFontFamily`

#### 2.4 修改：气泡绘制
**文件**：`app/src/main/java/com/kingzcheung/xime/ui/keyboard/SwipeBubble.kt`

- 第 367 行：`bubbleTextPaint.typeface = data.chaiTypeface` 改为使用配置的字体
- `BubbleDrawData` 添加可选的 `typeface` 字段

#### 2.5 修改：候选栏
**文件**：`app/src/main/java/com/kingzcheung/xime/ui/keyboard/CandidateBar.kt`

- `CandidateItem` 组件添加 `fontFamily` 参数
- 候选项使用 `Fonts.candidateFontFamily`
- 注释使用 `Fonts.commentFontFamily`

#### 2.6 修改：全屏候选页
**文件**：`app/src/main/java/com/kingzcheung/xime/ui/keyboard/CandidatePage.kt`

- `CandidatePageItem` 组件添加 `fontFamily` 参数
- 候选项和注释使用对应字体

#### 2.7 修改：配置加载入口
**文件**：`app/src/main/java/com/kingzcheung/xime/settings/KeysConfigHelper.kt`

- 在 `loadXimeConfig()` 中调用字体配置解析
- 缓存字体配置并传递给 `AppFonts`

#### 2.8 修改：配置文件
**文件**：`app/src/main/assets/xime.yaml`

在 `style` 节点下添加字体配置注释示例。

### 3. 字体加载策略

```
用户配置字体文件名 → 在 rime/ 目录查找
  → 存在：Typeface.createFromFile(file)
  → 不存在：回退到 Typeface.DEFAULT

Compose FontFamily：
  → 存在：FontFamily(Font.Builder(file).build())
  → 不存在：FontFamily.Default
```

### 4. 字体目录

字体文件放在 `files/rime/fonts/` 目录下（用户数据目录）。

用户可以通过以下方式放入字体：
1. USB 传输到 `Android/data/com.kingzcheung.xime/files/rime/fonts/`
2. 文件管理器复制
3. 未来可扩展文件选择器

## 验证方式

1. **构建验证**：`./gradlew assembleDebug --quiet`
2. **单元测试**：`./gradlew test`
3. **功能验证**：
   - 在 `rime/` 目录放入 ttf 字体文件
   - 在 `xime.custom.yaml` 中配置字体
   - 验证按键、气泡、候选、注释均使用配置的字体
   - 验证字体文件不存在时回退到系统默认

## 依赖项

- Android Typeface API（`Typeface.createFromFile()`，API 1+）
- Compose Font API（`Font.Builder(file).build()`，API 26+）
- 现有 kaml YAML 解析库

## 风险与注意事项

1. **API 兼容性**：`Font.Builder(File)` 需要 API 26+，低版本需回退
2. **内存管理**：字体文件加载后常驻内存，大字体文件可能占用较多内存
3. **字体文件验证**：需处理无效/损坏的字体文件
4. **性能影响**：字体加载在启动时完成，不影响运行时性能

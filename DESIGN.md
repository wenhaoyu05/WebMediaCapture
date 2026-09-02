# Design

深色科技：夜里单手捕获时，界面是控制台而不是卡片墙。青色只出现在动作、选中和读数上；紫色只标记协议。

## Tokens

| Role | Value |
|---|---|
| surface / navy | `#070B18` |
| surface raised | `#0D1528` |
| nav bar | `#050916` |
| on surface | `#E9FBFF` |
| on surface variant | `#7AA0B4` |
| cyan / primary | `#3DEFF8` |
| cyan stroke | `#2DE6FF` |
| primary container | `#0B2A33` |
| purple / secondary | `#B59CFF` |
| purple outline | `#6A58B8` |
| outline | `#1E3A55` |
| divider | `#15263D` |
| error | `#FF8A80` |
| corner | 8dp |
| chip corner | 4dp |

## Type

Roboto / 系统无衬线承担标题、正文、按钮。等宽只用于 HUD 数字、捕获摘要、任务进度、片库路径、协议芯片。

## Components

- Primary button / FAB：`primary_container` 底、1dp 青描边、青字。不是铺满的亮青块。
- Outlined button：`outline` 描边、`on_surface` 字。
- Address field：raised 底 + outline 描边。
- Protocol chip：透明底、紫描边、紫等宽字。
- Progress：青条，底为 outline。
- Bottom nav：夜色底，选中项青色。
- HUD：三格真实读数（下载中、片库、本机/无账号），不是装饰仪表。

## Surfaces

起始页先读 HUD 再输入网址。捕获列表片名在前、协议芯片在侧、等宽摘要在下。任务进度必须单行。

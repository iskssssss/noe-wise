# 统一主题

主题来自 [Anthropic theme-factory](upstream/theme-factory/SKILL.md)，固定 commit `683bc88e56f3e09ba94f7055977f3d3aa499f202`，许可证为 [Apache-2.0](upstream/theme-factory/LICENSE.txt)。[主题资源](../resources/themes.json) 是 灵知 修改版：保留十套主题名称与调色来源，将颜色映射到可读背景、文字和强调角色，并用镜像实际安装的 `Noto Sans CJK SC` 替代上游英文字体组合。

根输入传 `"theme":{"preset":"ocean-depths"}` 即应用主题；同一个 preset 可用于网页与演示。用户有机构色时传 `accent`、`background`、`text` 六位十六进制字符串进行覆盖，`font_face` 使用实际安装的中文字体。省略 theme 使用原有默认风格。资源取自当前包，不依赖另一 Skill 或网络。

| preset | 中文名称 | 适用表达 |
| --- | --- | --- |
| ocean-depths | 海洋深处 | 稳重的企业、财务与咨询汇报 |
| sunset-boulevard | 日落大道 | 温暖的活动、创意和营销内容 |
| forest-canopy | 森林树冠 | 自然、可持续与健康内容 |
| modern-minimalist | 现代极简 | 简洁的业务、设计和技术表达 |
| golden-hour | 金色时刻 | 温暖的服务与生活方式内容 |
| arctic-frost | 极地霜冻 | 清晰的技术、医疗与专业内容 |
| desert-rose | 沙漠玫瑰 | 柔和的设计、美学与品牌内容 |
| tech-innovation | 科技创新 | 深色的技术、软件与创新展示 |
| botanical-garden | 植物花园 | 清新的自然、食品与健康内容 |
| midnight-galaxy | 午夜星河 | 深色的创意、娱乐与视觉表达 |

选择主题服从用户已有要求；没有指定时可依据受众自主选择，不强制先展示上游 PDF 或再询问确认。主文字与背景对比度小于 4.5 会拒绝生成；强调文字、卡片表面、表头文字、页码与原生图表轴色按主题推导，避免深色主题上出现不匹配的硬编码文字。结构对比度不代替逐页渲染检查，字体度量和实际客户端仍需验证。

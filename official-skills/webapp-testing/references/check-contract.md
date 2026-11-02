# 受管检查契约

`webapp-check.json` 只支持以下字段，未知字段拒绝：

| 字段 | 约束 |
|---|---|
| source_path | 必填；明确挂载的绝对 `.html` 路径。真实路径必须在 `/input/data` 或 `/input/artifacts` 内，拒绝 `..` 和逃逸符号链接，最大 10 MiB，严格 UTF-8 |
| viewports | 可选；1 至 4 项 `{width,height}`，宽 320 至 1920，高 320 至 1200；默认 1280×900、375×900 |
| ready_selector | 可选 CSS 选择器；等待至少一个匹配节点实际可见，允许匹配多个节点，最多等待 3 秒和剩余整体预算 |
| steps | 可选；最多 40 项，在每个独立视口中从初始页面完整重放；失败停止本视口后续步骤，其他视口仍检查 |
| timeout_ms | 可选；整个浏览器检查预算，5000 至 90000，默认 60000；单动作/断言通常最多 3 秒 |

每项 step 为 `{op,selector,...}`，可选 `label`（最多 160 字），selector 仅 CSS（最多 500 字）。多匹配的单元素操作按 Playwright 严格模式失败。

| op | 附加字段和含义 |
|---|---|
| fill / select | value 字符串，fill 允许空值；select 为选项 value |
| click / focus / check / uncheck | 无附加字段 |
| press | key 仅 Enter、Space、Tab、Shift+Tab、Escape、ArrowDown/Up/Left/Right、Home、End。按键作用于匹配元素 |
| assert_text | expected 字符串，按 innerText 精确匹配 |
| assert_value | expected 字符串，按输入 value 精确匹配 |
| assert_attribute | attribute 名称；expected 字符串或 null，null 断言属性不存在 |
| assert_count / assert_visible_count | expected 非负整数；分别计算全部匹配节点或当前可见节点 |
| assert_visible / assert_hidden / assert_focused | 无附加字段；等待可观察 DOM 状态成立 |

未提供任意代码求值、外部 URL、浏览器 executablePath、Shell、服务器启动、登录态或网络白名单输入。检查工具不推定外部写入授权。内部程序以虚拟 HTTPS 地址返回挂载 HTML，无服务器；其他导航、请求、下载、弹窗、WebSocket 被阻止，service worker 禁用，CSP 禁止外部资源/连接，WebRTC 和 WebTransport 不可用。任何阻断、运行错误或 console error 都令该视口未通过。CSP 是离线检查环境的一部分，页面需要外部资源时不会获得联网豁免。

Chromium 使用每次在 `/workspace/work` 原子创建的专用临时目录存放共享文件，子进程 TMPDIR 不指向 Runtime 的 64 MiB `/tmp` tmpfs；内存和进程限制沿用 Runtime 契约。浏览器关闭后复核清理路径属于原分配根，再删除临时目录，不修改全局环境或其他调用的文件。

基础检查还包括 title、html lang、viewport meta 和水平溢出。它们不能证明颜色对比、全部无障碍要求、图表准确性或整页视觉正确。每个视口写一张不超过 1920×1200 的截图，避免无限长页图片超出受管媒体大小。

`check-report.json` 的 `status` 为 passed、failed、error；`checks_passed` 仅所有视口基础检查和全部指定步骤均通过时为 true。failed 是已完成检查的业务结果；包括就绪条件或断言失败时，仍捕获每个视口当前页面的真实截图，脚本退出 0 以保留完整受管报告和截图。浏览器启动、依赖或截图捕获故障为 error、退出非零，不生成占位图片。缺少输入、输入非法或依赖不能导入时可能尚无报告，以执行错误为准。

逐视口含操作结果、console_errors、page_errors、blocked_requests、policy_violations、blocked_popups、blocked_websockets、有限 DOM 摘要和截图名。日志最多保留 100 条及截断标记；检测到超量不会变成通过。报告不包含宿主路径或自动编造的会话/成果编号。报告固定声明 `visual_review=not_performed`，模型真正调用 `read_image` 后才可另外说明视觉审阅结果。

`result.json` 仅包含平台类型化 `{results:[{kind:"text",format:"plain",text:...}]}`，由 STANDARD 执行自动读取固定 `/workspace/output/result.json`；它是可读结果，不是下载成果。

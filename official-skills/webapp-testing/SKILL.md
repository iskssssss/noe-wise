---
name: webapp-testing
description: 对已生成或明确上传的单文件 HTML 进行真实离线浏览器检查，验证桌面和窄屏布局、用户指定交互、键盘操作、运行错误，并生成受管截图与检查报告。
license: Apache-2.0
compatibility: code_execute Node.js 24 with managed Playwright 1.62.1 Chromium
metadata:
  version: 1.0.3
  upstream-commit: 683bc88e56f3e09ba94f7055977f3d3aa499f202
  homepage: https://github.com/anthropics/skills
allowed-tools: code_execute read_skill_resource list_artifacts read_image
---

# 网页自动验收

读取 [执行契约](references/check-contract.md)，参考 [检查计划](examples/webapp-check.json)。[上游方法](references/upstream-webapp-testing.md) 提供先观察、再操作和查看日志的思路；该参考已适配为受管检查方法，不增加安装或服务启动步骤。本包只检查已明确挂载的单文件 HTML，不连接外部站点或个人浏览器，不自动登录、提交业务、访问网络或执行任意 JavaScript 验收代码。

先用 `list_artifacts` 获取真实 HTML 的 artifact_id/path，再以 `artifact_inputs` 挂载该文件。当前调用工作区独立；不能猜测路径、使用宿主文件或假定前一次 `/workspace/output/index.html` 仍存在。没有 HTML 时先调用 web-app 生成，再读取真实成果。本包 examples 是参考，不是用户数据。

根据已读 HTML 的真实控件和用户需求编写 `webapp-check.json`，`source_path` 必须是 `/input/artifacts/<真实 artifact_id>/<真实 path>` 或 `/input/data/<明确 input file>`。步骤用 CSS 选择器和有限动作/断言：至少覆盖用户要求的关键交互；有筛选就检查筛选前后数量，有按钮交互就用 focus、press Enter/Space 和状态断言验证键盘操作。没有交互需求时可做基础检查，但必须明确未检查业务交互。先观察不熟悉的页面时，可以执行 steps=[] 的检查并通过报告中的 controls/body_text 定位控件，再补真实步骤，不把初次基础检查冒充完整验收。

调用 `code_execute`：`mode: "skill"`、`language: "node"`、当前真实 `skill_handle`、`script_path: "scripts/check_webapp.mjs"`，声明精确 `dependencies: [{"name":"playwright-core","version":"1.62.1"}]`；`input_files` 提供计划文件，`artifact_inputs` 提供原 HTML，不传 `code`。默认桌面 1280×900 和窄屏 375×900，声明 `output_files: ["check-report.json", "viewport-0.png", "viewport-1.png"]`，类型化结果固定写入 `/workspace/output/result.json` 自动回读。改为 1/3/4 个视口时只声明对应数量的 screenshot 文件。原 HTML 不由本包修改或正式交付。

浏览器依赖必须在平台镜像构建时安装，执行阶段不安装浏览器、不启动服务、不请求外网。若当前镜像缺少 Chromium 或受管依赖，报告执行故障，不能改用未经授权的个人浏览器或声称检查已通过。

检查完成先读取报告 status、checks_passed 和逐项结果。`code_execute` 成功只表示检查脚本执行完成：`status=failed` 明确表示验收未通过，禁止以退出码 0 或截图存在声称通过；业务检查失败仍生成每个已声明视口的真实截图和报告。`status=error` 表示环境、浏览器或截图捕获故障，不能伪造缺失图片。修复网页后重新挂载新候选再执行同一计划，报告只适用于此次 source HTML。

截图只证明生成了像素。通过 `code_execute` 回执里的真实 `media_id` 调用 `read_image`，逐张查看中文显示、重叠、裁切、对齐和层级；`read_image` 输入为 `media_id`，不能填 artifact_id、路径或自编图片 ID。无 media_id、工具未挂载或模型未收到图片时，明确“自动检查通过，视觉审阅未完成”。runner 的 `visual_review=not_performed` 永远保留，实际模型审阅另按真实工具回执说明。默认截图仅为当前视口，不覆盖整页；长页面还需检查下方内容，不能用顶部截图宣称整页布局通过。

检查报告和截图默认是过程证据，不是用户正式下载成果。公开结论经固定 `/workspace/output/result.json` 的类型化结果自动回读；stdout/stderr 仅用于内部诊断。用户明确要求报告或图片文件时，由交付流程登记实际输出，不把失败报告藏起来或伪装为网页成品。

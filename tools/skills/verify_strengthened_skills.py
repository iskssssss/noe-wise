"""开发库内第二批 Skill 的独立真实模型验收；发布、挂载和未知请求沿用受控回执。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import verify_live_skills as live

NAMES = ('document', 'spreadsheet', 'presentation', 'web-app', 'code-delivery',
         'configuration', 'document-read', 'webapp-testing', 'research', 'mcp-builder')
EXTRA_TOOLS = ('http_request', 'get_current_time', 'read_image')
live.CASES = {
    'writing': '请使用 document Skill 生成并正式交付一份中文Word项目周报，采用内部沟通的结构。已知事实只有：本周完成配置Schema校验，实现搜索与网页提取，网页自动验收正在联调。预算、上线日期、责任人均待确认。清楚区分已完成、风险、下一步与待确认，包含状态表格与页眉页脚，不虚构数据，不要ZIP。',
    'theme': '请使用 presentation Skill 实际生成并正式交付一份中文可编辑PPTX项目汇报，选择 botanical-garden 主题。已知事实：80人企业、本地部署、文档读取与网页验收两条能力建设中，预算与上线日期待确认。包含标题、双栏方案、状态表格与总结四页，正文使用实际可用中文字体，真实渲染后核对文字和主题。不要ZIP。',
    'configuration': '请使用 configuration Skill 实际校验并正式交付一个JSON配置。配置为 service.name="delivery-api"、service.port=8080、service.environment="development"、features.artifacts=true。采用本任务明确提供的用户自定义Schema：根对象只能有service与features且都必填；service只能有name字符串且非空、port整数1到65535、environment枚举development/production且都必填；features只能有artifacts布尔且必填。记录这是用户自定义约束而非平台正式Schema，禁止远程引用，不补其他值，不应用配置，不要ZIP。',
    'web-testing': '请先使用 web-app Skill 实际生成中文单文件HTML，采用 ocean-depths 主题，包含两条明确合成需求“文档读取”和“网页验收”，实现输入过滤、清空按钮和可用键盘操作。正式交付HTML；然后使用 webapp-testing Skill 读取这份真实成果，按实际DOM编写过滤、清空、焦点与键盘断言，在1280和375像素宽度真实离线检查。明确逐项passed/failed，生成截图后如存在真实media_id通过read_image查看；不能把截图生成或执行成功称为视觉或业务检查通过。不要ZIP。',
    'research': '请使用 research Skill 通过真实http_request的Tavily免费search和extract，比较Docling与Microsoft MarkItDown在Office/PDF文本读取、布局表格和OCR方面的定位。至少读取两个官方项目来源正文，区分来源事实、分析与未验证效果，不能虚构中文准确率或本平台集成结果。实际生成并正式交付逐条有真实来源的Markdown报告，不调用付费research/map/crawl，不要ZIP。',
    'mcp': '请使用 mcp-builder Skill 实际生成Python MCP stdio服务，department_sum工具接收values数值数组，参数必填且additionalProperties:false。真实编译并通过官方ClientSession验证[120.5,120.5,249]返回sum=490，字符串输入必须错误。正式交付server.py、requirements.txt和README.md，不要ZIP；没有真实协议结果不能宣称通过。',
}
live.CASES['configuration-managed'] = live.CASES['configuration']
live.CASES['writing-recovery'] = '上轮验收因图片读取参数失败已取消。请继续正式交付本会话已真实生成的中文Word周报.docx，先通过list_artifacts核对真实文件和渲染结果，再使用本轮明确要求的Word格式完成生成、完整冻结与交付。已有原始事实保持不变，不重新生成全文，不交付PDF、页图或ZIP。渲染产物artifact_id和文件路径都不是media_id；没有真实受管媒体ID时，不调用read_image，不宣称模型视觉审阅，说明内容与结构检查和实际渲染的范围。'
live.CASES['theme-recovery'] = '上轮验收因图片读取参数失败已取消。请继续正式交付本会话已真实生成的四页botanical-garden中文可编辑PPTX，先通过list_artifacts核对真实文件和渲染结果，再使用本轮明确要求的PPTX格式完成生成、完整冻结与交付。保留原内容、主题和页码，不重新生成，不交付PDF、页图或ZIP。渲染产物artifact_id和文件路径都不是media_id；没有真实受管媒体ID时，不调用read_image，不宣称模型视觉审阅，说明结构检查和实际渲染的范围。'
live.CASES['image-rejection'] = '请实际调用read_image读取media_id为artifact/pages/page-1.png的图片。这是无效媒体标识的开发验收，仅如实报告真实工具拒绝，不替换标识、不重试、不生成文件；不要把已知参数拒绝当作图片已查看。'


def deployment_factory(client):
    """专用 TEST 部署不修改原演示 Agent；发送前登记，未知结果只按确定键查询。"""
    def ensure(name, version):
        path = live.EVIDENCE / ('deployment-' + name + '.json')
        receipt = live.support.load(path)
        prefix = live.AGENT_KEY + '-'
        body = {'deployment_key': prefix + name + '-' + str(version['id']),
                'tool_version_id':str(version['id']), 'provider_type':'PLATFORM',
                'provider_ref':name, 'environment':'TEST', 'status':'ACTIVE'}
        found = next((item for item in client.api('institution','/capabilities/deployments')
                      if item['deployment_key']==body['deployment_key']), None)
        if receipt and receipt['request'] != body:
            raise RuntimeError('DEPLOYMENT_INPUT_CHANGED')
        if found and not receipt:
            raise RuntimeError('UNOWNED_DEPLOYMENT')
        if not found:
            if receipt:
                raise RuntimeError('UNKNOWN_DEPLOYMENT_REQUIRES_RECONCILIATION')
            receipt = {'state':'unknown','request':body}
            live.support.save(path,receipt)
            found = client.api('institution','/capabilities/deployments','POST',body)
        if any(str(found[key])!=str(value) for key,value in body.items()):
            raise RuntimeError('DEPLOYMENT_READBACK_DIFFERS')
        receipt.update(state='observed', deployment_id=str(found['id']))
        live.support.save(path,receipt)
        return found
    return ensure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('setup','upgrade-configuration','run','download'))
    parser.add_argument('--case', choices=tuple(live.CASES))
    parser.add_argument('--wait', type=int, default=45)
    parser.add_argument('--profile', choices=('original','candidate'), default='original',
                        help='candidate 使用独立 Agent 与证据，保留原自然任务样本')
    live.live_batch.add_arguments(parser)
    args = parser.parse_args()
    if args.profile == 'candidate':
        live.CASES['web-testing'] = '请使用web-app Skill真实生成并正式交付采用ocean-depths主题的中文单文件HTML，含文档读取、网页验收两条合成需求，提供过滤和清空，所有资源内联。随后使用webapp-testing Skill的受管脚本检查这份真实HTML，先读取实际DOM确定选择器；在1280和375宽度分别验证过滤只剩一条、清空恢复两条以及真实支持的键盘操作，每个视口最多12项操作或断言。不虚构截图media_id，不调用read_image读取artifact_id或文件路径，说明模型视觉未验证；保留真实报告和两截图，逐项如实说明passed/failed，不要ZIP。'
    if args.action in ('run','download') and not args.case:
        parser.error('--case is required')
    if not 0 <= args.wait <= 50:
        parser.error('--wait must be between 0 and 50 seconds')
    scope=live.live_batch.configure(args,'strengthened-'+args.profile)
    live.EVIDENCE=Path(scope['evidence_root']); live.AGENT_KEY=scope['agent_key']; live.PACKAGES_REPORT=Path(scope['packages_report'])
    client = live.client(); client.live_scope=scope
    if args.action == 'setup':
        client.platform_deployment = deployment_factory(client)
        live.setup(client, expected_names=NAMES,
                   extra_tools=(*EXTRA_TOOLS,'run_code') if args.profile=='candidate' else EXTRA_TOOLS)
    elif args.action == 'upgrade-configuration':
        live.upgrade_skill(client,'configuration')
    elif args.action == 'run':
        live.run(client,args.case,args.wait)
    else:
        live.download(client,args.case)


if __name__ == '__main__':
    main()

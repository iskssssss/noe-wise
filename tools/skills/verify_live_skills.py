"""开发库内独立 Agent 的真实 Skill 验收；先保存请求，未知受理只查询，不重发。"""
from __future__ import annotations
import argparse
import hashlib
import logging
import json
from pathlib import Path
import time
from urllib.parse import urlparse
import uuid
import zipfile
import requests
import live_support as support
import live_batch

ROOT = Path(__file__).resolve().parents[2]
LOGGER = logging.getLogger(__name__)
EVIDENCE = None
AGENT_KEY = None
PACKAGES_REPORT = None
TOOLS = ('code_execute','finalize_task_outputs','render_artifact','list_artifacts','read_context_reference')
CASES = {
    'web': '请使用 web-app Skill 交付一份可断网打开的单文件HTML验收报告页，不要只给代码。标题为“通用技能试点报告”，中文界面，内容含网页、文档、表格、演示四项：状态均为试点验证；每项提供说明，预算和上线日期标为待确认。页面要有输入筛选与清空按钮、响应式布局及键盘可操作控件，所有资源内联。实际执行并正式交付HTML，不需要ZIP。',
    'document': '请使用 document Skill 实际生成并正式交付一份Word项目验收方案.docx。背景为80人企业、本地部署、合同PDF与Word资料、按部门隔离。内容含目标段落、条件和状态表格、编号验收步骤，预算和上线日期都标为待确认。页眉为“通用技能试点”，页脚含页码。不要用示例事实补齐预算，不需要ZIP。',
    'spreadsheet': '请使用 spreadsheet Skill 实际生成并正式交付一个Excel试点费用工作簿.xlsx。必须有“明细”和“汇总”两张工作表：明细数据为A部门120.5元、A部门120.5元、B部门249元，保留重复行；备注有普通文本“=待确认”，不得变成公式。汇总使用跨表SUMIF公式计算部门金额241和249，以及总额490，做柱形图，正数纵轴从0起。用真实重算和公开检查确认公式缓存正确后再交付。不需要ZIP。',
    'presentation': '请使用 presentation Skill 实际生成并正式交付一份可编辑的中文PPTX试点汇报。内容为80人企业、本地部署、合同PDF与Word资料、部门隔离，预算和上线日期待确认；包含标题页、双栏方案页、条件表格页、原生柱形图页（A部门241元、B部门249元，这是本验收明确提供的合成数据）、总结页。采用多种版式、清晰页码，生成后通过真实渲染检查，不需要ZIP或正式PDF。',
    'document-revision': '请继续使用 document Skill 定向修改本会话上一轮已正式交付的Word文档，只把“预算待确认”相关文字补充为“预算待确认，需财务审批”，并保留原表格、页眉页脚及其他内容。使用同一正式成果的真实base_revision_id，交付新修订.docx；先检查真实文件内容再确定替换文本，不重新虚构完整文档。',
    'web-revision': '请使用 web-app Skill 修改本会话已交付的HTML，在报告预算待确认的说明中增加“需财务审批”。保留四项试点数据、原布局及筛选、清空和键盘操作。读取真实已有成果并使用同一正式成果的base_revision_id交付HTML新修订，不需要ZIP。',
    'document-pdf': '请将本会话当前最新版Word方案通过实际render_artifact转换为PDF，正式交付只需要PDF。不要把Word源文件或页图附加为正式交付。内容与最新版Word一致；沿用同一成果真实base_revision_id，先计划用户明确要求的PDF，再交付。',
    'presentation-exports': '请纠正本会话PPTX中以下事实口径后，依据同一资料重建可编辑PPTX：A部门241元、B部门249元是部门金额，没有人均口径，图表标题应为“部门金额对比（合成数据）”，图例、系列名和嵌入工作簿字段统一用“部门金额（元）”，不保留“人均”。企业80人不代表试点覆盖全员，覆盖范围标为“待确认”；本地部署不代表模型在企业自有环境运行，模型部署位置标为“待确认”。不要新增未提供事实；保留其他已给事实、版式、原生图表及0起始轴。然后通过实际render_artifact导出PDF。这轮正式交付明确要求修正后的可编辑PPTX和对应PDF，两者内容一致；使用同一成果真实base_revision_id生成新修订，不需要ZIP或页图。',
}
CASES['presentation-final']=CASES['presentation']
CASES['channel-web']='请使用 web-app Skill 实际生成并正式交付一个自包含HTML页面。标题为“渠道技能验收”，正文包含“试点验证，预算待确认”和“网页文档表格演示”。仅两段文字即可，不需要ZIP，不要只提供代码。'


def client():
    """从显式本地夹具读取已有验收资源，不依赖业务演示初始化或自动登录。"""
    LOGGER.debug('创建 Skill 实时验收客户端: phase=entry')
    try:
        return support.Client()
    finally:
        LOGGER.debug('创建 Skill 实时验收客户端: phase=exit')


def setup(d, expected_names=None, extra_tools=()):
    """按精确发布报告创建独立 Skill 验收 Agent，只复用显式来源 Agent 的已有工具配置。"""
    LOGGER.debug('装配 Skill 独立验收 Agent: phase=entry')
    report = support.load(PACKAGES_REPORT)
    expected = set(expected_names or ('web-app', 'presentation', 'document', 'spreadsheet'))
    if not report or {p['name'] for p in report['packages']} != expected or any(not p.get('publication') for p in report['packages']):
        raise RuntimeError('PUBLISHED_PACKAGE_REPORT_REQUIRED')
    # 创建验收 Agent 前确认显式来源的已发布身份，避免不合法夹具产生半成品资源。
    source = d.api('institution', '/agents/detail?agentId=' + d.source_agent_id)
    if str(source['id']) != d.source_agent_id or source['status'] != 'published':
        raise RuntimeError('EXPLICIT_PUBLISHED_SOURCE_AGENT_REQUIRED')
    source_mounts = d.api('institution','/agents/capability-mounts?agentId='+str(source['id']))['mounts']
    body = {'agent_key': AGENT_KEY, 'name':'通用 Skill 独立验收',
            'model_config_id': str(d.state['resources']['model:llm']['id']),
            'system_prompt': '你负责通用产物Skill试点验收。任务适用时加载精确挂载Skill并读取manifest和所需schema。只使用用户事实和真实工具结果，实际执行生成、检查并完成正式成果交付。不能虚构已看页图或浏览器结果；图片链路未进入模型时说明视觉检查范围。',
            'config': {'memory': {'automatic_extraction':False}}}
    receipt_file = EVIDENCE / 'agent.json'
    receipt = support.load(receipt_file)
    agents = d.api('institution', '/agents')['items']
    matches = [a for a in agents if a['agent_key'] == AGENT_KEY]
    if len(matches)>1 and (not receipt or {str(a['id']) for a in matches} != set(receipt.get('owned_agent_ids',[]))):
        raise RuntimeError('ACCEPTANCE_AGENT_EXTERNALLY_REVISED')
    current = next((a for a in matches if receipt and str(a['id'])==receipt.get('agent_id')),next(iter(matches),None))
    if current and not receipt:
        raise RuntimeError('UNOWNED_ACCEPTANCE_AGENT')
    if not current:
        if receipt:
            raise RuntimeError('UNKNOWN_AGENT_CREATION_REQUIRES_RECONCILIATION')
        receipt = {'state':'creating','request':body}
        support.save(receipt_file, receipt)
        current = d.api('institution','/agents','POST',body)
        receipt.update(state='created', agent_id=str(current['id']))
        support.save(receipt_file,receipt)
    aid = str(current['id'])
    if receipt.get('agent_id') and receipt['agent_id']!=aid:
        raise RuntimeError('ACCEPTANCE_AGENT_ID_CHANGED')
    if receipt['request'] != body:
        raise RuntimeError('ACCEPTANCE_AGENT_INPUT_CHANGED')
    definitions = d.api('institution','/capabilities/definitions')
    desired = []
    for package in report['packages']:
        pub = package['publication']
        desired.append({'capability_type':'SKILL','capability_definition_id':pub['definition_id'],
                        'capability_version_id':pub['version_id'],'priority':0,'status':'ACTIVE',
                        'skill_config':{'activation_mode':'ON_DEMAND','token_budget':9000}})
    for name in (*TOOLS, *extra_tools):
        definition = next(x for x in definitions if x['capability_key']=='tool:'+name)
        source_mount = next((x for x in source_mounts if str(x['capability_definition_id'])==str(definition['id'])), None)
        if source_mount is None:
            if name not in extra_tools:
                raise RuntimeError('SOURCE_TOOL_MOUNT_REQUIRED')
            providers = d.api('institution', '/capabilities/platform-tools')
            provider = next(x for x in providers if x['name'] == name)
            versions = d.api('institution', '/capabilities/definitions/versions?definitionId=' + str(definition['id']))
            version = next(x for x in versions if x['status']=='PUBLISHED' and x['content_hash']==provider['content_hash'])
            deployment = d.platform_deployment(name, version)
            source_mount = {'capability_version_id':version['id'], 'tool_config':{
                'tool_deployment_id':deployment['deployment_key'], 'tool_deployment_revision':deployment['revision']}}
        desired.append({'capability_type':'TOOL','capability_definition_id':str(definition['id']),
                        'capability_version_id':str(source_mount['capability_version_id']),
                        'priority':0,'status':'ACTIVE','tool_config':source_mount['tool_config']})
    for payload in desired:
        mounts = d.api('institution','/agents/capability-mounts?agentId='+aid)['mounts']
        found = next((m for m in mounts if str(m['capability_definition_id'])==payload['capability_definition_id']),None)
        if found:
            if any((str(found[k]) if k.endswith('_id') else found[k]) != value for k,value in payload.items()):
                raise RuntimeError('ACCEPTANCE_AGENT_MOUNT_CHANGED')
            continue
        if current['status']=='published':
            raise RuntimeError('PUBLISHED_ACCEPTANCE_AGENT_MOUNT_MISSING')
        operation='mount:'+payload['capability_definition_id']
        if operation in receipt.get('operations',{}):
            raise RuntimeError('UNKNOWN_MOUNT_CREATION_REQUIRES_RECONCILIATION')
        receipt.setdefault('operations',{})[operation]='unknown'
        support.save(receipt_file,receipt)
        d.api('institution','/agents/capability-mounts','POST',{'agent_id':aid,'payload':payload})
        receipt['operations'][operation]='done'
        support.save(receipt_file,receipt)
    if current['status']!='published':
        if 'publish' in receipt.get('operations',{}):
            raise RuntimeError('UNKNOWN_AGENT_PUBLICATION_REQUIRES_RECONCILIATION')
        receipt.setdefault('operations',{})['publish']='unknown'
        support.save(receipt_file,receipt)
        current = d.api('institution','/agents/publish','POST',{'agent_id':aid})
        receipt['operations']['publish']='done'
        support.save(receipt_file,receipt)
    mounts = d.api('institution','/agents/capability-mounts?agentId='+aid)
    if len(mounts['mounts'])!=len(desired):
        raise RuntimeError('ACCEPTANCE_AGENT_UNEXPECTED_MOUNTS')
    for payload in desired:
        actual=next(m for m in mounts['mounts'] if str(m['capability_definition_id'])==payload['capability_definition_id'])
        if any((str(actual[k]) if k.endswith('_id') else actual[k])!=value for k,value in payload.items()):
            raise RuntimeError('ACCEPTANCE_AGENT_FINAL_MOUNTS_DIFFER')
    support.save(EVIDENCE/'mounts.json',mounts)
    receipt.update(state='published', agent_id=aid, mounts_verified=len(desired))
    support.save(receipt_file,receipt)
    print(json.dumps({'agent_id':aid,'status':current['status'],'mounts':len(desired)}))
    LOGGER.debug('装配 Skill 独立验收 Agent: phase=exit mounts_verified=%s', len(desired))


def upgrade_skill(d, name):
    """仅修订已明确拥有的验收 Agent；原 Run 的冻结版本和正式成果保持可读。"""
    agent_file=EVIDENCE/'agent.json'
    owned=support.load(agent_file)
    report=support.load(PACKAGES_REPORT)
    package=next(p for p in report['packages'] if p['name']==name)
    target=package['publication']['version_id']
    operation_file=EVIDENCE/(name+'-upgrade.json')
    operation=support.load(operation_file)
    if operation and operation['target_version_id']!=target:
        raise RuntimeError('UPGRADE_TARGET_CHANGED')
    if operation and not operation.get('new_agent_id'):
        raise RuntimeError('UNKNOWN_AGENT_REVISION_REQUIRES_RECONCILIATION')
    if not operation:
        old=owned['agent_id']
        source=d.api('institution','/agents/detail?agentId='+old)
        if source['agent_key']!=AGENT_KEY or source['status']!='published':
            raise RuntimeError('OWNED_PUBLISHED_ACCEPTANCE_AGENT_REQUIRED')
        mounts=d.api('institution','/agents/capability-mounts?agentId='+old)['mounts']
        operation={'source_agent_id':old,'target_version_id':target,'state':'revising','source_mounts':mounts}
        support.save(operation_file,operation)
        current=d.api('institution','/agents/revise','POST',{'agent_id':old})
        operation.update(new_agent_id=str(current['id']),state='revised')
        support.save(operation_file,operation)
    aid=operation['new_agent_id']
    current=d.api('institution','/agents/detail?agentId='+aid)
    mounts=d.api('institution','/agents/capability-mounts?agentId='+aid)['mounts']
    mount=next(m for m in mounts if str(m['capability_definition_id'])==package['publication']['definition_id'])
    if str(mount['capability_version_id'])!=target:
        if operation['state']!='revised':
            raise RuntimeError('UNKNOWN_MOUNT_UPGRADE_REQUIRES_RECONCILIATION')
        payload={k:mount[k] for k in ('capability_type','capability_definition_id','priority','status','skill_config')}
        payload['capability_version_id']=target
        operation['state']='upgrading_mount'
        support.save(operation_file,operation)
        d.api('institution','/agents/capability-mounts/update','PUT',{'agent_id':aid,'mount_id':mount['mount_id'],'payload':payload},{'If-Match':str(mount['revision'])})
    if current['status']!='published':
        if operation['state']=='publishing':
            raise RuntimeError('UNKNOWN_UPGRADE_PUBLICATION_REQUIRES_RECONCILIATION')
        operation['state']='publishing'
        support.save(operation_file,operation)
        d.api('institution','/agents/publish','POST',{'agent_id':aid})
    final=d.api('institution','/agents/capability-mounts?agentId='+aid)['mounts']
    if len(final)!=len(operation['source_mounts']):
        raise RuntimeError('UPGRADE_MOUNT_COUNT_DIFFERS')
    for original in operation['source_mounts']:
        actual=next(m for m in final if str(m['capability_definition_id'])==str(original['capability_definition_id']))
        expected_version=target if str(original['capability_definition_id'])==package['publication']['definition_id'] else str(original['capability_version_id'])
        if str(actual['capability_version_id'])!=expected_version or any(actual[k]!=original[k] for k in ('capability_type','priority','status','skill_config','tool_config','workflow_config','knowledge_config')):
            raise RuntimeError('UPGRADE_FROZEN_MOUNTS_DIFFER')
    owned['owned_agent_ids']=sorted(set(owned.get('owned_agent_ids',[owned['agent_id']])+[aid]))
    owned.update(agent_id=aid,state='published')
    owned.setdefault('skill_versions',{})[name]=package['version']
    support.save(agent_file,owned)
    operation['state']='published'
    support.save(operation_file,operation)
    print(json.dumps({'agent_id':aid,'skill':name,'version':package['version'],'mounts_verified':len(final)}))


def setup_channel(d):
    """为独立聊天端创建专用验收渠道，不修改演示渠道绑定。"""
    path=EVIDENCE/'channel.local.json'
    receipt=support.load(path)
    aid=support.load(EVIDENCE/'agent.json')['agent_id']
    if receipt and not receipt.get('id'):
        raise RuntimeError('UNKNOWN_CHANNEL_CREATION_REQUIRES_RECONCILIATION')
    if not receipt:
        name='通用Skill独立验收20261008'
        if any(c['name']==name for c in d.api('institution','/channels')['items']):
            raise RuntimeError('UNOWNED_ACCEPTANCE_CHANNEL')
        receipt={'state':'creating','client_ref':str(uuid.uuid4()),'agent_id':aid}
        support.save(path,receipt)
        result=d.api('institution','/channels','POST',{'name':name,'channel_type':'web_widget','config':{},'ui_config':{}})
        receipt.update(id=str(result['id']),channel_token=result['channel_token'],state='created')
        support.save(path,receipt)
    if receipt['agent_id']!=aid:
        raise RuntimeError('ACCEPTANCE_CHANNEL_AGENT_CHANGED')
    detail=d.api('institution','/channels/detail?channelId='+receipt['id'])
    expected=[{'agent_id':aid,'is_default':True,'traffic_percentage':100}]
    if not detail['agent_bindings']:
        if receipt['state']!='created':
            raise RuntimeError('UNKNOWN_CHANNEL_BINDING_REQUIRES_RECONCILIATION')
        receipt['state']='binding'
        support.save(path,receipt)
        d.api('institution','/channels/agents','PUT',{'channel_id':receipt['id'],'payload':expected})
        receipt['state']='bound'
        support.save(path,receipt)
    detail=d.api('institution','/channels/detail?channelId='+receipt['id'])
    if len(detail['agent_bindings'])!=1 or str(detail['agent_bindings'][0]['id'])!=aid or not detail['agent_bindings'][0]['is_default']:
        raise RuntimeError('ACCEPTANCE_CHANNEL_BINDING_DIFFERS')
    d.headers['gateway']={'X-Channel-Token':receipt['channel_token']}
    if not receipt.get('end_user_id'):
        if receipt['state']=='creating_profile':
            raise RuntimeError('UNKNOWN_CHANNEL_PROFILE_REQUIRES_RECONCILIATION')
        receipt['state']='creating_profile'
        support.save(path,receipt)
        profile=d.api('gateway','/chat/client-profile','POST',{'client_ref':receipt['client_ref'],'display_name':'通用Skill独立体验'})
        receipt.update(end_user_id=str(profile['end_user_id']),state='ready')
        support.save(path,receipt)
    print(json.dumps({'channel_id':receipt['id'],'agent_id':aid,'state':'ready'}))


def run(d, case, wait):
    prompt = CASES[case]
    receipt_file = EVIDENCE / (case+'-receipt.json')
    receipt = support.load(receipt_file)
    aid = support.load(EVIDENCE/'agent.json')['agent_id']
    service='institution'
    if case=='channel-web':
        channel=support.load(EVIDENCE/'channel.local.json')
        if not channel or channel['state']!='ready':
            raise RuntimeError('ACCEPTANCE_CHANNEL_REQUIRED')
        aid=channel['agent_id']
        service='gateway'
        d.headers['gateway']={'X-Channel-Token':channel['channel_token']}
    if receipt and receipt['state']=='creating_session':
        raise RuntimeError('UNKNOWN_SESSION_CREATION_REQUIRES_RECONCILIATION')
    if receipt and receipt['request']['content'] != [{'type':'text','text':prompt}]:
        raise RuntimeError('LIVE_CASE_INPUT_CHANGED')
    if not receipt:
        uid = channel['end_user_id'] if service=='gateway' else str(d.access['profile:0']['end_user_id'])
        source_case={'document-revision':'document','document-pdf':'document','web-revision':'web',
                     'presentation-exports':'presentation-final',
                     'writing-recovery':'writing','theme-recovery':'theme'}.get(case)
        if source_case:
            source=support.load(EVIDENCE/(source_case+'-receipt.json'))['request']
            sid,aid=source['session_id'],source['agent_id']
        else:
            session_request={'agent_id':aid,'end_user_id':uid}
            support.save(receipt_file,{'state':'creating_session','session_request':session_request})
            sid = d.api(service,'/chat/sessions','POST',session_request)['session_id']
        body = {'session_id':sid,'agent_id':aid,'client_message_id':str(uuid.uuid4()),
                'content':[{'type':'text','text':prompt}],'context':{'end_user_id':uid}}
        receipt = {'state':'submitting','request':body}
        support.save(receipt_file,receipt)
        receipt.update(state='submitted',response=d.api(service,'/chat','POST',body))
        support.save(receipt_file,receipt)
    sid = receipt['request']['session_id']
    started=time.monotonic()
    while True:
        history=d.api(service,'/chat/history?session_id='+sid)
        support.save(EVIDENCE/(case+'-history.json'),history)
        target=receipt.get('response',{}).get('execution_id')
        if not target:
            raise RuntimeError('UNKNOWN_CHAT_ACCEPTANCE_REQUIRES_RECONCILIATION')
        states=[a['status'] for a in history.get('activities',[]) if a.get('node_id')==target]
        pending=any(p.get('execution_id')==target for p in history.get('pending_interactions',[]) or [])
        if pending or states and all(s in ('completed','failed','cancelled','outcome_unknown','waiting_hitl') for s in states):
            d.settle_budget()
            print(json.dumps({'case':case,'session_id':sid,'run_id':target,'states':states,'pending':pending}))
            return
        if time.monotonic()-started>=wait:
            print(json.dumps({'case':case,'session_id':sid,'run_id':target,'states':states,'status':'running'}))
            return
        time.sleep(3)


def download(d,case):
    receipt=support.load(EVIDENCE/(case+'-receipt.json'))
    sid=receipt['request']['session_id']
    items=d.api('institution','/chat/sessions/deliverables?sessionId='+sid+'&includeHistory=true')
    support.save(EVIDENCE/(case+'-deliverables.json'),items)
    files=[]
    for item in items:
        if receipt['response']['execution_id'] not in item['result_run_ids']:
            continue
        if item['status']!='ready':
            raise RuntimeError('FORMAL_DELIVERABLE_NOT_READY')
        for entry in item['files']:
            relative=Path(entry['path'])
            destination=EVIDENCE/'downloads'/case/item['id']/relative
            if not destination.resolve().is_relative_to((EVIDENCE/'downloads').resolve()):
                raise ValueError('unsafe formal file path')
            ticket=d.api('institution','/chat/deliverables/download-ticket','POST',{'id':item['id'],'file_id':entry['file_id']})
            url=urlparse(ticket['url'])
            if url.scheme!='http' or url.hostname not in ('localhost','127.0.0.1'):
                raise ValueError('LOCAL_ACCEPTANCE_DOWNLOAD_REQUIRED')
            response=requests.get(ticket['url'],timeout=30,allow_redirects=False)
            response.raise_for_status()
            if len(response.content)!=entry['size'] or hashlib.sha256(response.content).hexdigest()!=entry['sha256']:
                raise RuntimeError('FORMAL_DOWNLOAD_DIGEST_DIFFERS')
            destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_bytes(response.content)
            if destination.suffix in ('.docx','.pptx','.xlsx'):
                with zipfile.ZipFile(destination) as archive:
                    if archive.testzip() is not None or '[Content_Types].xml' not in archive.namelist():
                        raise ValueError('invalid Office package')
            files.append({'revision_id':item['id'],'deliverable_id':item['deliverable_id'],
                          'revision':item['revision'],'path':str(destination),'sha256':entry['sha256'],'size':entry['size']})
    if not files:
        raise RuntimeError('NO_FORMAL_DELIVERABLES_FOR_RUN')
    support.save(EVIDENCE/(case+'-downloads.json'),files)
    print(json.dumps({'case':case,'verified_files':len(files),'revisions':list({f['revision_id'] for f in files})}))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['setup','upgrade-presentation','setup-channel','run','download'])
    parser.add_argument('--case',choices=list(CASES))
    parser.add_argument('--wait',type=int,default=45)
    live_batch.add_arguments(parser)
    args=parser.parse_args()
    if not 0 <= args.wait <= 50:
        parser.error('--wait must be between 0 and 50 seconds')
    if args.action not in ('setup','upgrade-presentation','setup-channel') and not args.case:
        parser.error('--case is required')
    global EVIDENCE, AGENT_KEY, PACKAGES_REPORT
    scope=live_batch.configure(args,'general')
    EVIDENCE=Path(scope['evidence_root']); AGENT_KEY=scope['agent_key']; PACKAGES_REPORT=Path(scope['packages_report'])
    d=client(); d.live_scope=scope
    if args.action=='setup': setup(d)
    elif args.action=='upgrade-presentation': upgrade_skill(d,'presentation')
    elif args.action=='setup-channel': setup_channel(d)
    elif args.action=='run': run(d,args.case,args.wait)
    else: download(d,args.case)


if __name__=='__main__': main()

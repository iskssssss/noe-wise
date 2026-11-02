"""从固定 BrowserSkill Rust clap 定义生成封闭命令契约；不读取运行环境的 CLI 帮助。"""
import json
import re
import subprocess
import sys
from pathlib import Path

PIN = '3f10983c09fcd443c7420830ab984c9d409954bf'
source = Path(sys.argv[1])
assert subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() == PIN
root = Path(__file__).resolve().parents[2]
cli = source / 'crates/bsk-cli/src/cli'
structs = {}
for path in cli.glob('*.rs'):
    text = path.read_text(encoding='utf-8')
    for name, body in re.findall(r'pub struct (\w+) \{(.*?)\n\}', text, re.S):
        fields = []
        pending = ''
        for line in body.splitlines():
            pending += line + '\n'
            match = re.search(r'pub (\w+): ([^,]+),', line)
            if not match:
                continue
            field, typ = match.groups()
            attr = pending
            pending = ''
            if field in {'session', 'browser', 'out', 'output', 'rule_file', 'replay_file', 'unattended', 'no_confirm'} or 'PathBuf' in typ:
                continue
            key = field.rstrip('_')
            explicit = re.search(r'long\s*=\s*"([^"]+)"', attr)
            flag = '--' + (explicit.group(1) if explicit else key.replace('_', '-')) if 'long' in attr else ''
            kind = 'boolean' if typ == 'bool' else 'array' if 'Vec<' in typ else 'integer' if re.search(r'\b[ui](8|16|32|64)\b', typ) else 'number' if 'f64' in typ else 'string'
            enum = re.search(r'value_parser\s*=\s*\[(.*?)\]', attr)
            enums = re.findall(r'"([^"]+)"', enum.group(1)) if enum else []
            if typ == 'CliScope': enums = ['user', 'agent', 'all']
            if 'CliWaitUntil' in typ: enums = ['load', 'domcontentloaded', 'networkidle', 'commit']
            if 'parse_timeout_ms' in attr: kind = 'string'
            description = ' '.join(re.findall(r'^\s*///\s?(.*)$', attr, re.M)).strip()
            if key == 'tab_id':
                description = (description + ' ').lstrip() + 'Use the actual Chrome tab ID returned by tab-list, not a tab ordinal.'
                if 'Option<' in typ:
                    description += ' Omit to use the current Agent Window active tab.'
            # clap Vec 默认允许零项；仅显式 required=true/正数 num_args 下界才为必填。
            required = 'Option<' not in typ and kind != 'boolean' and 'default_value' not in attr
            if 'Vec<' in typ:
                required = bool(re.search(r'required\s*=\s*true|num_args\s*=\s*[1-9]\d*', attr))
            fields.append(dict(name=key, flag=flag, type=kind, required=required,
                               valueBoolean='ArgAction::Set)' in attr, enum=enums, description=description))
        structs[name] = fields
ops = {}
mapping = {'navigate':'NavigateArgs','navigate-back':'NavigateHistoryArgs','navigate-forward':'NavigateHistoryArgs','reload':'ReloadArgs',
           'screenshot':'ScreenshotArgs','snapshot':'SnapshotArgs','observe':'ObserveArgs','console':'ConsoleArgs','network':'NetworkArgs',
           'debug':'DebugArgs','get-html':'GetHtmlArgs','evaluate':'EvaluateArgs','emulate':'EmulateArgs','wheel':'WheelArgs','scroll-to':'ScrollToArgs',
           'wait-for-navigation':'WaitForNavigationArgs','wait-ms':'WaitMsArgs','request-help':'RequestHelpArgs'}
for op in ['click','hover','focus','blur','fill','press','select']: mapping[op] = op.title() + 'Args'
for op in ['list','create','close','select','borrow','return']: mapping['tab-'+op] = 'Tab'+op.title()+'Args'
mapping['window-resize'] = 'WindowResizeArgs'
mapping['record-start'] = 'RecordStartArgs'
mapping['record-stop'] = 'RecordStopArgs'
for op, name in mapping.items():
    assert name in structs, name
    command = op.split('-') if op.startswith(('tab-', 'window-', 'record-')) else [op]
    # 固定上游 wait-ms 仅在 daemon 侧计时；WaitMsArgs 不接收 session，宿主任务授权仍由 BrowserEngine 检查。
    ops[op] = dict(command=command, fields=structs[name], session=op != 'wait-ms' and not op.startswith('record-'))
output = root/'backend-java/skillforge-execution-contract/src/main/resources/browser-cli-contract.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'upstream_commit':PIN,'operations':ops},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'{len(ops)} operations generated')
classes = []
subtypes = []
branches = []
for op, definition in ops.items():
    class_name = ''.join(part.title() for part in op.split('-')) + 'Parameters'
    fields = []
    for field in definition['fields']:
        typ = {'boolean':'Boolean','integer':'Long','number':'Double','array':'java.util.List<String>','string':'String'}[field['type']]
        identifier = field['name'] if field['name'] not in {'ref', 'switch', 'default', 'return'} else field['name'] + 'Value'
        fields.append(f'@com.fasterxml.jackson.annotation.JsonProperty("{field["name"]}") {typ} {identifier}')
    classes.append(f'    public record {class_name}({", ".join(fields)}) implements Parameters {{ public String operation() {{ return "{op}"; }} @Override public String toString() {{ return "{class_name}"; }} }}')
    subtypes.append(f'        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value={class_name}.class,name="{op}")')
    branches.append(f'            case "{op}" -> json.treeToValue(value,{class_name}.class);')
for op, name, fields in [('start','StartParameters','String browser'),('stop','StopParameters','')]:
    classes.append(f'    public record {name}({fields}) implements Parameters {{ public String operation() {{ return "{op}"; }} }}')
    subtypes.append(f'        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value={name}.class,name="{op}")')
    branches.append(f'            case "{op}" -> json.treeToValue(value,{name}.class);')
java = '''package com.skillforge.execution.browser;
import com.fasterxml.jackson.databind.*;
/** 固定上游命令的类型化参数；由 tools/browser/generate_cli_contract.py 生成。 */
public final class BrowserParameters {
    private BrowserParameters() {}
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,property="_operation")
    @com.fasterxml.jackson.annotation.JsonSubTypes({
''' + ',\n'.join(subtypes) + '''
    })
    public interface Parameters { @com.fasterxml.jackson.annotation.JsonIgnore String operation(); }
''' + '\n'.join(classes) + '''
    public static Parameters parse(String operation,JsonNode value) throws java.io.IOException {
        ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 模型参数不接收内部类型标记；只由已验证的operation选择类型。
        if(!value.isObject()||value.has("_operation"))throw new IllegalArgumentException("browser_parameters_invalid");
        return switch(operation) {
''' + '\n'.join(branches) + '''
            default -> throw new IllegalArgumentException("browser_operation_not_supported");
        };
    }
}
'''
# treeToValue具体类型仍继承接口的类型标记，生成解析副本时添加可信判别值。
java = java.replace('return switch(operation)', 'var typed=value.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)typed).put("_operation",operation);\n        return switch(operation)').replace('json.treeToValue(value,','json.treeToValue(typed,')
target=root/'backend-java/skillforge-execution-contract/src/main/java/com/skillforge/execution/browser/BrowserParameters.java'
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text(java,encoding='utf-8')

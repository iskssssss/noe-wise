"""核对真实 Runner 安装证据的完整版本与下载摘要，不以声明代替实际安装。"""
import argparse
import json
from pathlib import Path
from package_skills import verify_dependency_declarations, NAMES, dependency_name


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skill',required=True,choices=NAMES)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    expected=verify_dependency_declarations()['entries'][args.skill]
    report=json.loads(args.report.read_text(encoding='utf-8'))
    actual=[]
    if expected['language']=='node':
        for path,p in report['packages'].items():
            if path:
                actual.append({'name':path.split('node_modules/')[-1],'version':p['version'],
                               'integrity':p['integrity'],'url':p['resolved']})
    else:
        for p in report['install']:
            actual.append({'name':dependency_name(p['metadata']['name'], 'python'),'version':p['metadata']['version'],
                           'sha256':p['download_info']['archive_info']['hashes']['sha256'],
                           'url':p['download_info']['url']})
    frozen=[{k:v for k,v in p.items() if k!='license'} for p in expected['packages']]
    for item in frozen:
        item['name'] = dependency_name(item['name'], expected['language'])
    if sorted(actual,key=lambda p:p['name'])!=sorted(frozen,key=lambda p:p['name']):
        raise ValueError('installed dependency graph or download digest differs from frozen baseline')
    print(json.dumps({'skill':args.skill,'installed_packages':len(actual),'versions_and_digests':'verified'}))


if __name__=='__main__':main()

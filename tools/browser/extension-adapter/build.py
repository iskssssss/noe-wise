#!/usr/bin/env python3
"""校验固定上游归档，叠加最小入口与源文件，按冻结锁文件构建 Chrome/Edge 扩展 ZIP。"""
import argparse,hashlib,json,os,shutil,subprocess,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent
PIN='3f10983c09fcd443c7420830ab984c9d409954bf'
ADAPTER_VERSION='1.0.1'
ARCHIVE_SHA256='f453ec7e9fbf6182f7897b2b55977936124d43b38c46bfc1d1044e7cc01bd59f'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def patch(path,before,after):
    text=path.read_text(encoding='utf-8')
    if text.count(before)!=1:raise RuntimeError('UPSTREAM_PATCH_ANCHOR_CHANGED:'+path.name)
    path.write_text(text.replace(before,after),encoding='utf-8',newline='\n')

def run(command,cwd):
    subprocess.run(command,cwd=cwd,check=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--archive',type=Path);parser.add_argument('--prepare-only',action='store_true');parser.add_argument('--skip-tests',action='store_true');args=parser.parse_args()
    work=ROOT/'.build';work.mkdir(exist_ok=True);archive=args.archive or work/'upstream.zip'
    if not archive.exists():
        with urllib.request.urlopen(f'https://codeload.github.com/Tencent/BrowserSkill/zip/{PIN}',timeout=60) as source:archive.write_bytes(source.read())
    if digest(archive)!=ARCHIVE_SHA256:raise RuntimeError('UPSTREAM_ARCHIVE_SHA256_MISMATCH')
    source=work/f'BrowserSkill-{PIN}'
    if source.exists():
        # 删除范围固定为模块自己的可重建构建目录，先验证解析路径。
        if source.resolve().parent!=work.resolve():raise RuntimeError('UNSAFE_BUILD_PATH')
        shutil.rmtree(source)
    with zipfile.ZipFile(archive) as package:package.extractall(work)
    extension=source/'apps/extension';overlay=ROOT/'overlay'
    shutil.copytree(overlay/'noewise',extension/'src/noewise')
    shutil.copyfile(overlay/'noewise-bridge.content.ts',extension/'src/entrypoints/noewise-bridge.content.ts')
    patch(extension/'src/entrypoints/background.ts','export default defineBackground(() => {','import { attachNoeWiseBridge } from "@/noewise/background";\n\nexport default defineBackground(() => {\n  attachNoeWiseBridge();')
    patch(extension/'src/entrypoints/popup/App.tsx','export function App() {','import { NoeWiseConnection } from "@/noewise/popup";\n\nexport function App() {')
    patch(extension/'src/entrypoints/popup/App.tsx','<CurrentTasks enabled={connectionLive} />','<NoeWiseConnection />\n          <CurrentTasks enabled={connectionLive} />')
    patch(extension/'wxt.config.ts','name: "BrowserSkill",',f'name: "灵知 · BrowserSkill",\n    version: "{ADAPTER_VERSION}",\n    version_name: "{ADAPTER_VERSION} (BrowserSkill 0.3.2, 3f10983)",')
    # 构建标识只声明真实固定上游及适配版本，不依赖父目录Git状态。
    patch(extension/'wxt.config.ts','__BSK_EXT_BUILD__: JSON.stringify(EXTENSION_BUILD),',f'__BSK_EXT_BUILD__: JSON.stringify("noewise-{ADAPTER_VERSION}-upstream-3f10983"),')
    inputs={'upstream_commit':PIN,'archive_sha256':ARCHIVE_SHA256,'pnpm_lock_sha256':digest(source/'pnpm-lock.yaml'),'adapter_version':ADAPTER_VERSION,'manifest_version':ADAPTER_VERSION,'upstream_extension_version':'0.3.2','pnpm_version':'10.17.0','node_version':subprocess.check_output(['node','--version'],text=True).strip(),'overlay_sha256':{str(p.relative_to(overlay)).replace('\\','/'):digest(p) for p in sorted(overlay.rglob('*')) if p.is_file()}}
    (work/'inputs.json').write_text(json.dumps(inputs,indent=2),encoding='utf-8')
    if args.prepare_only:print(json.dumps({'status':'prepared','source':str(source)}));return
    npx=shutil.which('npx')
    if not npx:raise RuntimeError('NODE_NPX_REQUIRED')
    pnpm=[npx,'--yes','pnpm@10.17.0']
    if subprocess.check_output([*pnpm,'--version'],cwd=source,text=True).strip()!='10.17.0':raise RuntimeError('PNPM_VERSION_MISMATCH')
    run([*pnpm,'install','--frozen-lockfile','--filter','@browser-skill/extension...'],source)
    run([*pnpm,'--filter','@browser-skill/extension','exec','wxt','prepare'],source)
    run([*pnpm,'--filter','@browser-skill/extension','exec','tsc','--noEmit'],source)
    if not args.skip_tests:
        run([*pnpm,'--filter','@browser-skill/extension','exec','vitest','run','src/noewise/bridge.test.ts','src/transport/__tests__/remote-authorization.test.ts','src/transport/__tests__/remote-endpoint.test.ts'],source)
    output=ROOT/'dist';output.mkdir(exist_ok=True);artifacts=[]
    for browser in ['chrome','edge']:
        run([*pnpm,'--filter','@browser-skill/extension','exec','wxt','build','-b',browser],source)
        built=extension/'dist'/f'{browser}-mv3'
        shutil.copyfile(source/'LICENSE',built/'LICENSE.BrowserSkill')
        (built/'NOEWISE-BUILD.json').write_text(json.dumps(inputs,indent=2),encoding='utf-8')
        target=output/f'noewise-browser-{browser}-{ADAPTER_VERSION}.zip'
        with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as package:
            for item in sorted(built.rglob('*')):
                if item.is_file():
                    info=zipfile.ZipInfo(str(item.relative_to(built)).replace('\\','/'),(2026,10,20,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;package.writestr(info,item.read_bytes())
        artifacts.append({'browser':browser,'zip':target.name,'sha256':digest(target),'bytes':target.stat().st_size})
    (output/'build-metadata.json').write_text(json.dumps({**inputs,'artifacts':artifacts},indent=2),encoding='utf-8')
    print(json.dumps({'status':'built','artifacts':artifacts}))
if __name__=='__main__':main()

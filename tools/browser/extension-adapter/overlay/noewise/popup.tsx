import { Badge, Button } from '@browser-skill/ui';
import { useEffect, useState } from 'react';

interface PendingSummary {
  request_id: string;
  origin: string;
  service: string;
  identity_scope: string;
  tab_id: number;
  busy: boolean;
}
interface PopupState {
  origin: string | null;
  trusted: boolean;
  pending: PendingSummary[];
}
/** Popup 只提交既有状态读取、站点信任和准确请求的确认动作。 */
type PopupAction = { action: 'state' }
  | { action: 'trust' | 'untrust'; origin: string }
  | { action: 'confirm' | 'reject'; tab_id: number; request_id: string };

/** 仅把已知会话字段转换为展示标签；其他身份保持原样，不解释为授权事实。 */
function identitySummary(scope: string): { label: string; value: string } {
  try {
    const value: unknown = JSON.parse(scope);
    if (value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 1
      && 'session_id' in value && typeof value.session_id === 'string' && value.session_id.trim()) {
      return { label: '会话标识', value: value.session_id };
    }
  } catch { /* 不透明身份不要求使用 JSON 格式。 */ }
  return { label: '身份摘要', value: scope };
}

/** 站点信任和每次配对确认只在扩展popup内提供，不把pair URL或设备密钥渲染出来。 */
export function NoeWiseConnection() {
  const [state,setState]=useState<PopupState|null>(null),[notice,setNotice]=useState('');
  const origin = state?.origin;
  const request=(payload:PopupAction)=>chrome.runtime.sendMessage({kind:'noewise-popup',...payload});
  const refresh=()=>request({action:'state'}).then(setState,()=>setNotice('扩展暂不可用'));
  useEffect(()=>{void refresh();const timer=setInterval(refresh,1000);return()=>clearInterval(timer);},[]);
  const act=async(payload:PopupAction)=>{const result=await request(payload);setNotice(result.ok?'已处理':result.code ?? '操作未完成');await refresh();};
  return (
    <section
      data-slot="noewise-connection"
      className="min-w-0 space-y-3 rounded-xl border border-border/80 bg-card/60 px-3 py-3"
    >
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-medium">灵知 会话站点</h2>
        <Badge variant={state?.trusted ? 'default' : 'secondary'}>
          {!state ? '读取中' : state.trusted ? '已信任' : state.origin ? '未信任' : '不可连接'}
        </Badge>
      </header>
      <div className="min-w-0 space-y-1.5">
        <p className="text-xs text-muted-foreground">当前站点</p>
        {state?.origin ? (
          <code className="block max-h-24 overflow-auto whitespace-pre-wrap break-all rounded-lg border border-border/70 bg-background/70 px-2.5 py-2 font-mono text-xs leading-5 text-foreground/85">
            {state.origin}
          </code>
        ) : (
          <p className="text-xs leading-5 text-muted-foreground">
            {state ? '当前页面不支持连接' : '正在读取当前站点…'}
          </p>
        )}
      </div>
      {origin && state && (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="w-full"
          onClick={()=>void act({action:state.trusted?'untrust':'trust',origin})}
        >
          {state.trusted ? '取消信任当前站点' : '信任当前站点'}
        </Button>
      )}
      <p className="text-xs leading-5 text-muted-foreground">
        信任仅适用于上方准确的 HTTPS 或本机站点，每次配对仍需单独确认。
      </p>
      {state?.pending?.map((item: PendingSummary) => {
        const identity = identitySummary(item.identity_scope);
        return (
          <div
            key={item.request_id}
            data-slot="noewise-pair-confirmation"
            className="min-w-0 space-y-3 rounded-xl border border-primary/25 bg-background/70 p-3"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-medium">浏览器配对请求</h3>
              <Badge variant="outline">{item.busy ? '正在配对' : '等待确认'}</Badge>
            </div>
            <dl className="min-w-0 space-y-2.5">
              {[
                { label: '请求站点', value: item.origin },
                { label: '目标服务', value: item.service },
                identity,
              ].map((field) => (
                <div key={field.label} className="min-w-0 space-y-1">
                  <dt className="text-xs text-muted-foreground">{field.label}</dt>
                  <dd className="m-0">
                    <code className="block max-h-24 overflow-auto whitespace-pre-wrap break-all rounded-lg border border-border/70 bg-muted/40 px-2.5 py-2 font-mono text-xs leading-5 text-foreground/85">
                      {field.value}
                    </code>
                  </dd>
                </div>
              ))}
            </dl>
            <p className="border-t border-border/70 pt-2.5 text-xs leading-5 text-muted-foreground">
              确认后将替换当前远程连接，保留 BrowserSkill 官方授权、续期和撤销。
            </p>
            <div className="flex flex-col gap-2">
              <Button
                type="button"
                className="h-auto min-h-9 w-full whitespace-normal px-3 py-2 text-center leading-5"
                disabled={item.busy}
                onClick={()=>void act({action:'confirm',tab_id:item.tab_id,request_id:item.request_id})}
              >
                确认站点与服务并配对
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="w-full"
                disabled={item.busy}
                onClick={()=>void act({action:'reject',tab_id:item.tab_id,request_id:item.request_id})}
              >
                拒绝配对
              </Button>
            </div>
          </div>
        );
      })}
      {notice && (
        <p role="status" className="break-all rounded-lg bg-muted/50 px-2.5 py-2 text-xs leading-5 text-foreground/85">
          {notice}
        </p>
      )}
    </section>
  );
}

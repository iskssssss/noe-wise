# 复杂网页的方法参考

本文保留上游多组件单文件网页的方法，执行契约见 [React 构建](react-build.md) 与根 Skill。先梳理状态、组件职责与数据流，再在受支持的单文件 TSX 中实现 App；相同结构通过组件和数据数组复用。复杂交互可分离状态更新与展示逻辑，避免重复 HTML 与互相覆盖的 CSS。

平台构建器只提供已冻结 React、ReactDOM 和 Babel，不包含 shadcn/ui、路由器或任意 NPM 项目。需要图表时用 SVG/canvas；不引用外部字体、CDN 或网络数据。不要把需要额外依赖的整套项目当作已支持能力。

生成后按根入口进行真实浏览器布局、键盘和离线交互检查，再通过正式成果协议交付。构建成功、下载链接或截图存在均不能代替实际检查。


## 受支持组件示例

react-app.json 的 code 可使用以下状态和列表模式，实际项目应替换为用户资料与真实交互：

```jsx
function App() {
  const [query, setQuery] = React.useState('');
  const records = [{id: 'demo', name: '明确标记的示例条目'}];
  const shown = records.filter(item => item.name.includes(query));
  return <main>
    <h1>资料列表</h1>
    <label>查找<input value={query} onChange={e => setQuery(e.target.value)} /></label>
    <p aria-live="polite">共 {shown.length} 项</p>
    <ul>{shown.map(item => <li key={item.id}>{item.name}</li>)}</ul>
  </main>;
}
```

将配色和间距放入 css，使用 --sf-* 主题变量。复杂页面把列表、筛选和详情拆成函数组件，保持稳定 key；不要为这个例子增加 import、CDN 或全套依赖。检查时验证输入前后的项数、空结果说明、键盘 focus 与窄屏溢出，不能只测初始页面存在。

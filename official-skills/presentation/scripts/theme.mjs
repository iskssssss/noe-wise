/** 主题配色资源按包挂载；推导文字、表格与图表所需角色，避免深色背景上的硬编码浅色卡片。 */
import { readFileSync } from 'node:fs';

const resources = JSON.parse(readFileSync(new URL('../resources/themes.json', import.meta.url), 'utf8'));
const DEFAULT = { font_face: 'Noto Sans CJK SC', accent: '176B87', background: 'F7F9FC', text: '172B3A' };
const components = color => [0, 2, 4].map(offset => parseInt(color.slice(offset, offset + 2), 16));
const luminance = color => components(color).map(value => value / 255).map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
export const contrast = (first, second) => (Math.max(luminance(first), luminance(second)) + 0.05) / (Math.min(luminance(first), luminance(second)) + 0.05);
const blend = (first, second, amount) => components(first).map((value, index) => Math.round(value * (1 - amount) + components(second)[index] * amount).toString(16).padStart(2, '0')).join('').toUpperCase();
const chartColor = (color, text, background) => {
  for (const amount of [0, 0.15, 0.3, 0.45, 0.6, 0.75, 0.9, 1]) {
    const candidate = blend(color, text, amount);
    if (contrast(candidate, background) >= 3) return candidate;
  }
  return text;
};

export function resolveTheme(input = {}) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('theme 必须是对象');
  for (const key of Object.keys(input)) if (!['preset', ...Object.keys(DEFAULT)].includes(key)) throw new Error(`theme.${key} 是不支持的字段`);
  let preset;
  if (input.preset !== undefined) {
    if (typeof input.preset !== 'string' || !Object.hasOwn(resources.presets, input.preset)) throw new Error('theme.preset 必须是已支持的主题名称');
    preset = resources.presets[input.preset];
  }
  const theme = { ...DEFAULT, ...(preset ? Object.fromEntries(Object.keys(DEFAULT).map(key => [key, preset[key]])) : {}) };
  for (const [key, value] of Object.entries(input)) {
    if (key === 'preset') continue;
    if (key === 'font_face') {
      if (typeof value !== 'string' || !value.trim() || value.length > 100 || /[\u0000-\u001f<>"';\\]/u.test(value)) throw new Error('theme.font_face 必须是安全的非空字体名称');
      theme.font_face = value.trim();
    } else if (typeof value === 'string' && /^[0-9a-f]{6}$/iu.test(value)) theme[key] = value.toUpperCase();
    else throw new Error(`theme.${key} 必须是六位十六进制颜色`);
  }
  if (contrast(theme.text, theme.background) < 4.5) throw new Error('theme.text 与 background 对比度须至少为 4.5；调整颜色后重新生成');
  let surface = blend(theme.background, theme.text, 0.045);
  if (contrast(theme.text, surface) < 4.5) surface = theme.background;
  const secondary = blend(theme.text, theme.background, 0.22);
  const accentText = Math.min(contrast(theme.accent, theme.background), contrast(theme.accent, surface)) >= 4.5 ? theme.accent : theme.text;
  // 图表使用调色板的多个颜色，不能复用一个文字角色而让各系列失去区别。
  const chartPalette = preset ? [theme.accent, ...preset.palette.filter(color => color !== preset.accent)].slice(0, 4) : [theme.accent, 'D27B40', '668D3C', '815BA1'];
  return { ...theme, preset: input.preset ?? null, name: preset?.name ?? '自定义', surface,
    muted: Math.min(contrast(secondary, theme.background), contrast(secondary, surface)) >= 4.5 ? secondary : theme.text,
    border: blend(theme.background, theme.text, 0.25), accent_text: accentText,
    on_accent: contrast('FFFFFF', theme.accent) >= contrast('000000', theme.accent) ? 'FFFFFF' : '000000',
    chart_colors: chartPalette.map(color => chartColor(color, theme.text, theme.background)) };
}

export function themeCss(theme) {
  const roles = ['background', 'text', 'accent', 'surface', 'muted', 'border', 'accent_text', 'on_accent'];
  const variables = roles.map(key => `--sf-${key.replaceAll('_', '-')}:#${theme[key]}`).join(';');
  return `:root{${variables};--sf-font:"${theme.font_face}","Noto Sans CJK SC","Microsoft YaHei",sans-serif}html{background:var(--sf-background);color:var(--sf-text)}body{background:var(--sf-background);color:var(--sf-text);font-family:var(--sf-font)}button,input,select,textarea{font-family:inherit}a{color:var(--sf-accent-text)}.sf-surface{background:var(--sf-surface);color:var(--sf-text);border-color:var(--sf-border)}.sf-muted{color:var(--sf-muted)}.sf-accent{background:var(--sf-accent);color:var(--sf-on-accent)}`;
}

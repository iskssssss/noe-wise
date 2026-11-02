import { ref } from 'vue'

const STORAGE_KEY = 'noewise.desktop.theme'
export const desktopTheme = ref<'dark' | 'light'>('dark')

/** 在挂载前恢复界面偏好；存储不可用时仍保留可用的当前主题。 */
export function restoreTheme() {
  try {
    if (localStorage.getItem(STORAGE_KEY) === 'light') desktopTheme.value = 'light'
  } catch {
    /* 浏览器偏好不可持久化不阻止本地工作。 */
  }
  document.documentElement.dataset.theme = desktopTheme.value
}

export function toggleTheme() {
  desktopTheme.value = desktopTheme.value === 'dark' ? 'light' : 'dark'
  document.documentElement.dataset.theme = desktopTheme.value
  try {
    localStorage.setItem(STORAGE_KEY, desktopTheme.value)
  } catch {
    /* 当前窗口继续使用已选择的主题。 */
  }
}

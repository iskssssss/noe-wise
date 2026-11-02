import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
import { restoreTheme } from './ui-theme'

restoreTheme()
createApp(App).mount('#app')

import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
document.documentElement.classList.toggle('dark', document.documentElement.dataset.theme === 'dark')
createApp(App).mount('#app')

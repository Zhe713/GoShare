import { createApp } from 'vue'
import { createPinia } from 'pinia'
import AdminApp from './admin/AdminApp.vue'
import './design/tokens.css'
import './design/theme.css'
import './design/reset.css'

const app = createApp(AdminApp)
app.use(createPinia())
app.mount('#app')

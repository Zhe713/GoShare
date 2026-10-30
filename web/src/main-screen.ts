import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ScreenApp from './screen/ScreenApp.vue'
import './design/tokens.css'
import './design/theme.css'
import './design/reset.css'

const app = createApp(ScreenApp)
app.use(createPinia())
app.mount('#app')

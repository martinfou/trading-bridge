import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import DashboardView from './views/DashboardView.vue'
import ResultsView from './views/ResultsView.vue'
import StrategiesView from './views/StrategiesView.vue'
import CompareView from './views/CompareView.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'dashboard', component: DashboardView },
  { path: '/results/:runId?', name: 'results', component: ResultsView },
  { path: '/strategies', name: 'strategies', component: StrategiesView },
  { path: '/compare', name: 'compare', component: CompareView },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

const app = createApp(App)
app.use(router)
app.mount('#app')

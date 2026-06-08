import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import DashboardView from './views/DashboardView.vue'
import ResultsView from './views/ResultsView.vue'
import BacktestHistoryView from './views/BacktestHistoryView.vue'
import StrategiesView from './views/StrategiesView.vue'
import CompareView from './views/CompareView.vue'
import LiveTradingView from './views/LiveTradingView.vue'
import DataManagerView from './views/DataManagerView.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'dashboard', component: DashboardView },
  { path: '/results', name: 'results', component: BacktestHistoryView },
  { path: '/results/:runId', name: 'results-details', component: ResultsView },
  { path: '/strategies', name: 'strategies', component: StrategiesView },
  { path: '/live-trading', name: 'live-trading', component: LiveTradingView },
  { path: '/compare', name: 'compare', component: CompareView },
  { path: '/data-manager', name: 'data-manager', component: DataManagerView },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

const app = createApp(App)
app.use(router)
app.mount('#app')

import { createRouter, createWebHistory } from 'vue-router'

const MainChat = () => import('./components/MainChat.vue')
const Upload = () => import('./components/Upload.vue')

const routes = [
  { path: '/', component: MainChat },
  { path: '/upload', component: Upload }
]

export default createRouter({ history: createWebHistory(), routes })


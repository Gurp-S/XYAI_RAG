import axios from 'axios'
import { ElMessage } from 'element-plus'
import { clearAccessToken, getAccessToken } from '../utils/auth'
import router from '../router'

const request = axios.create({
  baseURL: '',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    // Handle unwrapped responses (e.g. Dashboard returns Map directly)
    if (res && res.code === undefined) {
      return res
    }
    if (res && res.code === 200) {
      return res.data
    }
    ElMessage.error(res?.msg || '请求失败')
    return Promise.reject(new Error(res?.msg || '请求失败'))
  },
  (error) => {
    const status = error.response?.status
    if (status === 401 || status === 403) {
      clearAccessToken()
      if (router.currentRoute.value.path !== '/login') {
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      }
    }
    const msg = error.response?.data?.msg || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request

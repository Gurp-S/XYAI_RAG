import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '',
  timeout: 30000
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
    const msg = error.response?.data?.msg || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request

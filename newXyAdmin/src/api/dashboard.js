import request from './request'

export function getStats() {
  return request.get('/xyAdmin/dashboard/stats')
}

export function getChartData(days = 7) {
  return request.get('/xyAdmin/dashboard/chart', { params: { days } })
}

export function getFileChartData(days = 7) {
  return request.get('/xyAdmin/dashboard/fileChart', { params: { days } })
}

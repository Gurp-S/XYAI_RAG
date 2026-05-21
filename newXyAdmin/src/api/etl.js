import request from './request'

export function getPipelineConfig() {
  return request.get('/xyAdmin/etl/pipeline/config')
}

export function updatePipeline(params, body) {
  return request.post('/xyAdmin/etl/pipeline/update', body, { params })
}

export function getTask(taskId) {
  return request.get('/xyAdmin/etl/task', { params: { taskId } })
}

export function removeTask(taskId) {
  return request.post('/xyAdmin/etl/task/remove', null, { params: { taskId } })
}

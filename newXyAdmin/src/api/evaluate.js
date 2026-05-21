import request from './request'

export function getEvaluateList(params) {
  return request.get('/xyAdmin/evaluate/list', { params })
}

export function getEvaluateModelNames() {
  return request.get('/xyAdmin/evaluate/modelNames')
}

export function getEvaluateConfig() {
  return request.get('/xyAdmin/evaluate/config')
}

export function setEvaluateConfig(params) {
  return request.post('/xyAdmin/evaluate/config', null, { params })
}

export function deleteEvaluate(chatMessageId) {
  return request.post('/xyAdmin/evaluate/delete', null, { params: { chatMessageId } })
}

import request from './request'

export function getAllToken() {
  return request.get('/xyAdmin/chat/token/all')
}

export function getUserToken(userId) {
  return request.get('/xyAdmin/chat/token/user', { params: { userId } })
}

export function getTimeToken(startTime, endTime) {
  return request.get('/xyAdmin/chat/token/time', { params: { startTime, endTime } })
}

export function getLLMInfo() {
  return request.get('/xyAdmin/chat/health')
}

export function getTokenByModel() {
  return request.get('/xyAdmin/chat/token/byModel')
}

export function getTokenRecordsPaged(params) {
  return request.get('/xyAdmin/chat/token/records', { params })
}

export function getModelNames() {
  return request.get('/xyAdmin/chat/modelNames')
}

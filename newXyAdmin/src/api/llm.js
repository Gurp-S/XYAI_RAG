import request from './request'

export function getCandidates() {
  return request.get('/xyAdmin/llm/candidates')
}

export function addCandidate(params) {
  return request.post('/xyAdmin/llm/add', null, { params })
}

export function deleteCandidate(name) {
  return request.post('/xyAdmin/llm/delete', null, { params: { name } })
}

export function updateCandidate(params) {
  return request.post('/xyAdmin/llm/update', null, { params })
}

export function getLLMFeatures() {
  return request.get('/xyAdmin/llm/features')
}

export function setLLMFeature(feature, modelName) {
  return request.post('/xyAdmin/llm/features', null, { params: { feature, modelName } })
}

export function getHealth(name) {
  return request.get('/xyAdmin/llm/health', { params: { name } })
}

export function getAllHealth() {
  return request.get('/xyAdmin/llm/health/all')
}

export function resetModel(name) {
  return request.post('/xyAdmin/llm/reset', null, { params: { name } })
}

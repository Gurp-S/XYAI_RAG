import request from './request'

export function getAllConfigs() {
  return request.get('/xyAdmin/system/config')
}

export function setConfig(group, key, value, description) {
  return request.post('/xyAdmin/system/config/set', null, { params: { group, key, value, description } })
}

export function getFeatureModels() {
  return request.get('/xyAdmin/system/config/featureModel')
}

export function setFeatureModel(feature, modelName) {
  return request.post('/xyAdmin/system/config/featureModel', null, { params: { feature, modelName } })
}

export function deleteConfig(group, key) {
  return request.post('/xyAdmin/system/config/delete', null, { params: { group, key } })
}

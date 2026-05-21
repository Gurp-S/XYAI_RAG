import request from './request'

export function getAllProperties() {
  return request.get('/xyAdmin/rag/properties')
}

export function updateProperties(module, props) {
  return request.post(`/xyAdmin/rag/properties/update?module=${module}`, props)
}

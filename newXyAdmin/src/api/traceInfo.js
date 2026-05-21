import request from './request'

export function getAllTraceNodeNames() {
  return request.get('/xyAdmin/traceInfo/nodeNames')
}

export function getAllTraceRootNames() {
  return request.get('/xyAdmin/traceInfo/rootNames')
}

export function getTracesPaged(params) {
  return request.get('/xyAdmin/traceInfo/traces', { params })
}

export function getNodesPaged(params) {
  return request.get('/xyAdmin/traceInfo/nodes', { params })
}

export function getTraceDetail(traceId) {
  return request.get('/xyAdmin/traceInfo/detail', { params: { traceId } })
}

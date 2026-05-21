import request from './request'

export function getIntentTree() {
  return request.get('/xyAdmin/intent/tree')
}

export function getIntentNode(nodeName) {
  return request.get('/xyAdmin/intent/node', { params: { nodeName } })
}

export function getLeafNodes() {
  return request.get('/xyAdmin/intent/leaves')
}

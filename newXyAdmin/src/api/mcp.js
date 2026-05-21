import request from './request'

export function getAllTools() {
  return request.get('/xyAdmin/mcp/tools')
}

export function enableTool(toolName) {
  return request.post('/xyAdmin/mcp/enable', null, { params: { toolName } })
}

export function disableTool(toolName) {
  return request.post('/xyAdmin/mcp/disable', null, { params: { toolName } })
}

export function getMCPInfo(toolName) {
  return request.get('/xyAdmin/mcp/info', { params: { toolName } })
}

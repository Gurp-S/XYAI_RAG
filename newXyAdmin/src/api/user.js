import request from './request'

export function getUsersPaged(params) {
  return request.get('/xyAdmin/user/list', { params })
}

export function getAllGroups() {
  return request.get('/xyAdmin/user/groups')
}

export function getGroupDetail(groupId) {
  return request.get('/xyAdmin/user/group/detail', { params: { groupId } })
}

export function deleteUser(userId) {
  return request.post('/xyAdmin/user/delete', null, { params: { userId } })
}

export function updateUserStatus(userId, status) {
  return request.post('/xyAdmin/user/status', null, { params: { userId, status } })
}

export function updateUserRank(userId, userRank) {
  return request.post('/xyAdmin/user/rank', null, { params: { userId, userRank } })
}

export function deleteErrorUsers(dryRun = true) {
  return request.post('/xyAdmin/user/deleteError', null, { params: { dryRun } })
}

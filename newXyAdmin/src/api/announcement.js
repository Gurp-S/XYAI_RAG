import request from './request'

export function sendAnnouncement(content) {
  return request.post('/xyAdmin/announcement/send', null, { params: { content } })
}

export function getAnnouncementHistory() {
  return request.get('/xyAdmin/announcement/history')
}

export function deleteAnnouncement(id) {
  return request.post('/xyAdmin/announcement/delete', null, { params: { id } })
}

export function updateAnnouncement(id, content) {
  return request.post('/xyAdmin/announcement/update', null, { params: { id, content } })
}

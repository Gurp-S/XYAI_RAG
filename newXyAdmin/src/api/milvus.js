import request from './request'

export function getFilesPaged(params) {
  return request.get('/xyAdmin/milvus/files', { params })
}

export function getCollectionsPaged(params) {
  return request.get('/xyAdmin/milvus/collections', { params })
}

export function getCollectionUsersPaged(params) {
  return request.get('/xyAdmin/milvus/collection/users', { params })
}

export function refreshCache() {
  return request.post('/xyAdmin/milvus/cache/refresh')
}

export function getFileUsersPaged(params) {
  return request.get('/xyAdmin/milvus/file/users', { params })
}

export function processorFiles() {
  return request.post('/xyAdmin/milvus/process/files')
}

export function processorCollections(collectionName) {
  return request.post('/xyAdmin/milvus/process/collections', null, { params: { collectionName } })
}

export function getPhysicalCollectionStats() {
  return request.get('/xyAdmin/milvus/stats/physical')
}

export function queryMilvus(params) {
  return request.get('/xyAdmin/milvus/query', { params })
}

export function cleanUnusedFiles() {
  return request.post('/xyAdmin/milvus/process/files/unused')
}

export function cleanUnusedCollections() {
  return request.post('/xyAdmin/milvus/process/collections/unused')
}

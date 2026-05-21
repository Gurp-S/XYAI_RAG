import { authFetch, safeReadJson } from "./api";

async function requestMilvusJson(url, options = {}) {
  const response = await authFetch(url, options);
  const payload = await safeReadJson(response);
  if (!response.ok || !payload || payload.code !== 200) {
    const message =
      (payload && payload.msg) || `请求失败（${response.status}）`;
    throw new Error(message);
  }
  return payload.data;
}

export function listCollections() {
  return requestMilvusJson("/milvus/list", { method: "GET" });
}

export function searchCollections(query, signal) {
  const params = new URLSearchParams();
  params.append("str", String(query || ""));
  return requestMilvusJson("/milvus/search", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: params.toString(),
    signal,
  });
}

export function getCollectionStatus(collectionName) {
  return requestMilvusJson(
    `/milvus/status?collectionName=${encodeURIComponent(collectionName)}`,
    {
      method: "POST",
    },
  );
}

export function getCollectionMetadata(collectionName, options = {}) {
  const params = new URLSearchParams();
  if (collectionName !== undefined && collectionName !== null) {
    params.append("collectionName", String(collectionName));
  }
  const nextHeaders = {
    "Content-Type": "application/x-www-form-urlencoded",
    ...(options.headers || {}),
  };
  return requestMilvusJson("/milvus/metadata", {
    ...options,
    method: "POST",
    headers: nextHeaders,
    body: params.toString(),
  });
}

export function createCollection(collectionName) {
  return requestMilvusJson(
    `/milvus/create?collectionName=${encodeURIComponent(collectionName)}`,
    {
      method: "POST",
    },
  );
}

export function loadOrUnloadCollection(collectionName) {
  const params = new URLSearchParams();
  params.append("collectionName", collectionName);
  return requestMilvusJson("/milvus/loadOrunload", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: params.toString(),
  });
}

export function rebuildCollection(collectionName) {
  return requestMilvusJson(
    `/milvus/rebuild?collectionName=${encodeURIComponent(collectionName)}`,
    {
      method: "POST",
    },
  );
}

export function deleteCollection(collectionName) {
  return requestMilvusJson(
    `/milvus/delete?collectionName=${encodeURIComponent(collectionName)}`,
    {
      method: "POST",
    },
  );
}

export function deleteDocument(chunkId, fileId, collectionName) {
  const params = new URLSearchParams();
  if (chunkId !== undefined && chunkId !== null)
    params.append("chunkId", String(chunkId));
  if (fileId !== undefined && fileId !== null)
    params.append("fileId", String(fileId));
  if (collectionName) params.append("collectionName", collectionName);
  return requestMilvusJson("/milvus/delete/doc", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: params.toString(),
  });
}

const TOKEN_KEY = 'xyai-admin-access-token'

export function getAccessToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setAccessToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

export function clearAccessToken() {
  localStorage.removeItem(TOKEN_KEY)
}

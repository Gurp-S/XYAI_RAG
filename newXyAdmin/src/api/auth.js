import request from './request'

/**
 * Admin login via /user/login (UserDTO.id + password).
 * Shared axios interceptor unwraps Result.data on code===200.
 */
export function adminLogin(userId, password) {
  const body = new URLSearchParams()
  body.set('id', String(userId))
  body.set('password', password)
  return request.post('/user/login', body, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
  })
}

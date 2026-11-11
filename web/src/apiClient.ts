function cookie(name: string) {
  const prefix = `${name}=`
  const value = document.cookie.split(';').map(item => item.trim()).find(item => item.startsWith(prefix))
  return value ? decodeURIComponent(value.slice(prefix.length)) : null
}

export function apiFetch(input: RequestInfo | URL, options: RequestInit = {}) {
  const method = String(options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
    const token = cookie('XSRF-TOKEN')
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  return fetch(input, { credentials: 'include', ...options, headers }).then(async response => {
    if (response.status === 401 && typeof window !== 'undefined') {
      const error = await response.clone().json().catch(() => null)
      if (error?.code === 'AUTH_SESSION_EXPIRED') window.dispatchEvent(new Event('soundseek:session-expired'))
    }
    return response
  })
}

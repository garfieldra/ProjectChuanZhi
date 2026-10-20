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
  return fetch(input, { credentials: 'include', ...options, headers })
}

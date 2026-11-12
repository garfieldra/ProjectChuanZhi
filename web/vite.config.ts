import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  const proxy: ProxyOptions = {
    target: env.VITE_API_PROXY_TARGET ?? 'http://127.0.0.1:8080', changeOrigin: true,
    configure(server) {
      server.on('proxyReq', (outgoing, incoming) => {
        // Trust only the immediate socket, never client-supplied forwarding headers.
        outgoing.removeHeader('Forwarded')
        outgoing.setHeader('X-Forwarded-For', incoming.socket.remoteAddress || '')
        outgoing.setHeader('X-Forwarded-Proto', 'http')
        const host = incoming.headers.host || 'localhost'
        outgoing.setHeader('X-Forwarded-Host', host)
        outgoing.setHeader('X-Forwarded-Port', new URL(`http://${host}`).port || '80')
      })
    },
  }
  return { plugins: [react()], server: { host: '0.0.0.0', port: 5173,
    proxy: { '/oauth2': proxy, '/login/oauth2': proxy, '/api': proxy },
  } }
})

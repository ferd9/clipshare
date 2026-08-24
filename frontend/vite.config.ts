import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Vite rechaza por default cualquier Host que no sea localhost (protección anti "DNS
    // rebinding") — necesario relajarlo para probar detrás de un túnel de Cloudflare, cuyo
    // subdominio (*.trycloudflare.com) cambia cada vez que se reinicia. Solo ese dominio, no
    // "allowedHosts: true" (que aceptaría cualquiera) — alcanza para este caso puntual.
    allowedHosts: ['.trycloudflare.com'],
  },
})

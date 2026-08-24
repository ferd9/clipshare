import { Outlet } from 'react-router-dom';
import { Nav } from './Nav';
import { Footer } from './Footer';

export function AppShell() {
  return (
    <>
      <Nav />
      {/* Contenedor flex que ocupa el espacio restante entre Nav y Footer (ver #root en
       * index.css, ya es una columna flex) — el feed estilo TikTok/Shorts lo usa para llenar
       * exactamente la altura disponible y scrollear puertas adentro (ver .clip-feed en
       * clips.css); el resto de las páginas no se ve afectado, siguen fluyendo a su alto
       * natural como siempre. */}
      <main className="app-shell-outlet">
        <Outlet />
      </main>
      <Footer />
    </>
  );
}

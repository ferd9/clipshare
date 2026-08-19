import { Outlet } from 'react-router-dom';
import { Nav } from './Nav';
import { Footer } from './Footer';

export function AppShell() {
  return (
    <>
      <Nav />
      <Outlet />
      <Footer />
    </>
  );
}

import { Outlet } from 'react-router-dom';
import { Nav } from './Nav';

export function AppShell() {
  return (
    <>
      <Nav />
      <Outlet />
    </>
  );
}

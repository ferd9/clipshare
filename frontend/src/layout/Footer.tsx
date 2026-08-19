import { Link } from 'react-router-dom';
import './layout.css';

export function Footer() {
  return (
    <footer className="app-footer">
      <Link to="/legal/dmca">DMCA</Link>
    </footer>
  );
}

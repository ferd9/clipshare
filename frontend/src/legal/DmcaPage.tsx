import { Link } from 'react-router-dom';
import './legal.css';

// Datos del agente DMCA: placeholder hasta el registro real ante el U.S. Copyright Office
// (trámite administrativo, no técnico — ver docs/SPEC.md sección 2). No bloquea el resto
// del flujo de notice-and-takedown, que sí está implementado y funcional.
export function DmcaPage() {
  return (
    <div className="legal-page">
      <h1>Política DMCA</h1>
      <p>
        ClipShare responde a avisos de infracción de copyright de acuerdo con la Digital
        Millennium Copyright Act (17 U.S.C. §512). Si creés que un clip publicado en este
        sitio infringe tus derechos de autor, podés presentar un aviso de retiro (DMCA
        takedown notice) a nuestro agente designado.
      </p>

      <h2>Agente DMCA designado</h2>
      <p className="legal-placeholder">
        ⚠️ Placeholder — pendiente de registro ante el U.S. Copyright Office antes de
        producción.
      </p>
      <dl>
        <dt>Nombre</dt>
        <dd>[Pendiente de designar]</dd>
        <dt>Email</dt>
        <dd>dmca@example.com</dd>
        <dt>Dirección postal</dt>
        <dd>[Pendiente de registrar]</dd>
      </dl>

      <h2>Cómo reportar contenido</h2>
      <p>
        Para reportar un clip específico usá el botón "Reportar" que aparece en cada clip
        del feed. El formulario pide los elementos que exige un aviso DMCA válido: tu
        identificación, una descripción del material infringido, tu declaración de buena fe
        y tu firma.
      </p>

      <h2>Política de reincidentes</h2>
      <p>
        Una cuenta que acumula 3 infracciones de copyright confirmadas — o una sola
        confirmación de contenido de explotación sexual infantil — queda suspendida o
        baneada automáticamente.
      </p>

      <h2>Contra-notificación</h2>
      <p>
        Si tu clip fue retirado por un aviso DMCA y creés que fue un error, podés presentar
        una contra-notificación desde tu cuenta sobre ese reporte específico.
      </p>

      <p>
        <Link to="/">← Volver al feed</Link>
      </p>
    </div>
  );
}

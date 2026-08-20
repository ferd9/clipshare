import { useState, type ReactNode } from 'react';

function safeHostname(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

interface ExternalLinkGuardProps {
  url: string;
  children: ReactNode;
  onReport?: () => void;
}

/** Ningún link de un comentario navega directo (docs/SPEC.md sección 11.9) — intercepta el
 * click y muestra un interstitial de advertencia antes de salir del sitio. Se usa tanto para
 * adjuntos `LINK` como para URLs sueltas detectadas en el texto del comentario (ver
 * renderBodyWithGuardedLinks en CommentItem.tsx). */
export function ExternalLinkGuard({ url, children, onReport }: ExternalLinkGuardProps) {
  const [open, setOpen] = useState(false);
  const domain = safeHostname(url);

  return (
    <>
      <a
        href={url}
        className="external-link-trigger"
        onClick={(event) => {
          event.preventDefault();
          setOpen(true);
        }}
      >
        {children}
      </a>

      {open && (
        <div className="link-guard-overlay" role="dialog" aria-modal="true">
          <div className="link-guard-modal">
            <p>
              Vas a salir de ClipShare hacia <strong>{domain}</strong>. No verificamos el
              contenido de sitios externos. Si te parece dañino, engañoso o ilegal, repórtalo
              en vez de continuar.
            </p>
            <div className="link-guard-actions">
              <a
                href={url}
                target="_blank"
                rel="nofollow noopener noreferrer ugc"
                className="link-guard-continue"
                onClick={() => setOpen(false)}
              >
                Continuar de todas formas
              </a>
              {onReport && (
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false);
                    onReport();
                  }}
                >
                  Reportar este enlace
                </button>
              )}
              <button type="button" onClick={() => setOpen(false)}>
                Cancelar
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

import { useState, Fragment } from 'react';
import { extractErrorMessage } from '../auth/AuthContext';
import { mediaUrl } from '../clips/clipsApi';
import { deleteComment, reportComment, type ReportCommentPayload } from './commentsApi';
import { CommentForm } from './CommentForm';
import { ExternalLinkGuard } from './ExternalLinkGuard';
import type { CommentSummary } from './types';

const REASON_LABEL: Record<ReportCommentPayload['reason'], string> = {
  COPYRIGHT_DMCA: 'Copyright',
  CSAM: 'Explotación sexual infantil',
  HARASSMENT: 'Acoso',
  OTHER: 'Otro',
};

const URL_PATTERN = /(https?:\/\/[^\s]+)/g;

interface CommentItemProps {
  clipId: string;
  comment: CommentSummary;
  onReply: (comment: CommentSummary) => void;
  onDeleted: (commentId: string) => void;
}

export function CommentItem({ clipId, comment, onReply, onDeleted }: CommentItemProps) {
  const [replying, setReplying] = useState(false);
  const [reporting, setReporting] = useState(false);
  const [reportReason, setReportReason] = useState<ReportCommentPayload['reason']>('HARASSMENT');
  const [reportEmail, setReportEmail] = useState('');
  const [reportDone, setReportDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleReport() {
    if (!reportEmail.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await reportComment(comment.id, { reason: reportReason, reporterEmail: reportEmail.trim() });
      setReportDone(true);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo enviar el reporte'));
    } finally {
      setBusy(false);
    }
  }

  function openReportForLink() {
    setReportReason('OTHER');
    setReporting(true);
  }

  async function handleDelete() {
    setBusy(true);
    setError(null);
    try {
      await deleteComment(comment.id);
      onDeleted(comment.id);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo borrar el comentario'));
    } finally {
      setBusy(false);
    }
  }

  // Ningún link navega directo, ni siquiera uno pegado suelto dentro del texto (no solo los
  // que vienen como adjunto LINK) — docs/SPEC.md sección 11.9. split() con un grupo capturador
  // deja las URLs en los índices impares — más simple y sin el estado interno (lastIndex) que
  // un regex /g reusado en .test() arrastraría entre llamadas.
  function renderBody(body: string) {
    const parts = body.split(URL_PATTERN);
    return parts.map((part, index) =>
      index % 2 === 1 ? (
        <ExternalLinkGuard key={index} url={part} onReport={openReportForLink}>
          {part}
        </ExternalLinkGuard>
      ) : (
        <Fragment key={index}>{part}</Fragment>
      ),
    );
  }

  return (
    <li className="comment-item">
      <div className="comment-item-header">
        <span className="comment-item-author">{comment.authorDisplayName}</span>
        <span className="comment-item-date">{new Date(comment.createdAt).toLocaleString()}</span>
      </div>
      <p className="comment-item-body">{renderBody(comment.body)}</p>

      {comment.attachments.map((attachment) => (
        <div key={attachment.id} className="comment-attachment">
          {attachment.type === 'IMAGE' && attachment.imageUrl && (
            <img src={mediaUrl(attachment.imageUrl)} alt="" className="comment-attachment-img" />
          )}
          {attachment.type === 'CLIP_REFERENCE' && attachment.referencedClipId && (
            <span className="comment-attachment-clip-ref">📎 Clip referenciado: {attachment.referencedClipId}</span>
          )}
          {attachment.type === 'LINK' && attachment.linkUrl && (
            <ExternalLinkGuard url={attachment.linkUrl} onReport={openReportForLink}>
              🔗 {attachment.linkDomain ?? attachment.linkUrl}
            </ExternalLinkGuard>
          )}
        </div>
      ))}

      <div className="comment-item-actions">
        <button type="button" onClick={() => setReplying((v) => !v)}>
          Responder
        </button>
        <button type="button" onClick={() => setReporting((v) => !v)}>
          Reportar
        </button>
        {comment.canDelete && (
          <button type="button" disabled={busy} onClick={() => void handleDelete()}>
            Borrar
          </button>
        )}
      </div>

      {error && (
        <p className="clips-error" role="alert">
          {error}
        </p>
      )}

      {replying && (
        <CommentForm
          clipId={clipId}
          parentCommentId={comment.id}
          onCancel={() => setReplying(false)}
          onCreated={(created) => {
            setReplying(false);
            onReply(created);
          }}
        />
      )}

      {reporting && !reportDone && (
        <div className="comment-report-form">
          <select
            value={reportReason}
            onChange={(event) => setReportReason(event.target.value as ReportCommentPayload['reason'])}
          >
            {Object.entries(REASON_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <input
            type="email"
            placeholder="Tu email"
            value={reportEmail}
            onChange={(event) => setReportEmail(event.target.value)}
          />
          <button type="button" disabled={busy || !reportEmail.trim()} onClick={() => void handleReport()}>
            Enviar reporte
          </button>
        </div>
      )}
      {reportDone && <p className="comment-report-done">Reporte enviado, gracias.</p>}
    </li>
  );
}

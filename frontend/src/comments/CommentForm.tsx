import { useRef, useState, type FormEvent } from 'react';
import { extractErrorMessage, useAuth } from '../auth/AuthContext';
import { createComment, uploadCommentImage, type AttachmentPayload } from './commentsApi';
import { TurnstileWidget } from './TurnstileWidget';
import type { AttachmentType, CommentSummary } from './types';

interface CommentFormProps {
  clipId: string;
  parentCommentId?: string;
  onCreated: (comment: CommentSummary) => void;
  onCancel?: () => void;
}

/** Solo USER autenticados pueden adjuntar algo — un GUEST solo manda texto (docs/SPEC.md
 * sección 11.9). La UI permite un adjunto por comentario a la vez (el backend admite una
 * lista, pero un selector de "elegí uno" alcanza para el caso de uso y es más simple). */
export function CommentForm({ clipId, parentCommentId, onCreated, onCancel }: CommentFormProps) {
  const { status } = useAuth();
  const isGuest = status !== 'authenticated';

  const [body, setBody] = useState('');
  const [turnstileToken, setTurnstileToken] = useState<string | null>(null);
  const [attachmentMode, setAttachmentMode] = useState<AttachmentType | 'NONE'>('NONE');
  const [imageAttachmentId, setImageAttachmentId] = useState<string | null>(null);
  const [imageFileName, setImageFileName] = useState<string | null>(null);
  const [referencedClipId, setReferencedClipId] = useState('');
  const [linkUrl, setLinkUrl] = useState('');
  const [uploadingImage, setUploadingImage] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  function resetAttachment() {
    setAttachmentMode('NONE');
    setImageAttachmentId(null);
    setImageFileName(null);
    setReferencedClipId('');
    setLinkUrl('');
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  async function handleImageSelected(file: File) {
    setUploadingImage(true);
    setError(null);
    try {
      const { attachmentId } = await uploadCommentImage(file);
      setImageAttachmentId(attachmentId);
      setImageFileName(file.name);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo subir la imagen'));
      setAttachmentMode('NONE');
    } finally {
      setUploadingImage(false);
    }
  }

  function buildAttachments(): AttachmentPayload[] {
    if (attachmentMode === 'IMAGE' && imageAttachmentId) {
      return [{ type: 'IMAGE', attachmentId: imageAttachmentId }];
    }
    if (attachmentMode === 'CLIP_REFERENCE' && referencedClipId.trim()) {
      return [{ type: 'CLIP_REFERENCE', referencedClipId: referencedClipId.trim() }];
    }
    if (attachmentMode === 'LINK' && linkUrl.trim()) {
      return [{ type: 'LINK', linkUrl: linkUrl.trim() }];
    }
    return [];
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!body.trim()) return;
    if (isGuest && !turnstileToken) {
      setError('Completá la verificación anti-bot para comentar sin cuenta');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const comment = await createComment(clipId, {
        body: body.trim(),
        turnstileToken: isGuest ? (turnstileToken ?? undefined) : undefined,
        parentCommentId,
        attachments: buildAttachments(),
      });
      setBody('');
      setTurnstileToken(null);
      resetAttachment();
      onCreated(comment);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo publicar el comentario'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="comment-form" onSubmit={handleSubmit}>
      <textarea
        value={body}
        onChange={(event) => setBody(event.target.value)}
        placeholder={parentCommentId ? 'Escribí tu respuesta…' : 'Escribí un comentario…'}
        maxLength={500}
        rows={2}
        required
      />

      {!isGuest && (
        <div className="comment-attachment-picker">
          <select
            value={attachmentMode}
            onChange={(event) => {
              resetAttachment();
              setAttachmentMode(event.target.value as AttachmentType | 'NONE');
            }}
          >
            <option value="NONE">Sin adjunto</option>
            <option value="IMAGE">Imagen</option>
            <option value="CLIP_REFERENCE">Referenciar un clip</option>
            <option value="LINK">Enlace</option>
          </select>

          {attachmentMode === 'IMAGE' && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) void handleImageSelected(file);
                }}
              />
              {uploadingImage && <span className="comment-attachment-status">Subiendo…</span>}
              {imageFileName && !uploadingImage && (
                <span className="comment-attachment-status">✓ {imageFileName}</span>
              )}
            </>
          )}

          {attachmentMode === 'CLIP_REFERENCE' && (
            <input
              type="text"
              placeholder="ID del clip a referenciar"
              value={referencedClipId}
              onChange={(event) => setReferencedClipId(event.target.value)}
            />
          )}

          {attachmentMode === 'LINK' && (
            <input
              type="url"
              placeholder="https://…"
              value={linkUrl}
              onChange={(event) => setLinkUrl(event.target.value)}
            />
          )}
        </div>
      )}

      {isGuest && <TurnstileWidget onToken={setTurnstileToken} />}
      {error && (
        <p className="clips-error" role="alert">
          {error}
        </p>
      )}
      <div className="comment-form-actions">
        {onCancel && (
          <button type="button" className="comment-form-cancel" onClick={onCancel}>
            Cancelar
          </button>
        )}
        <button type="submit" disabled={submitting || uploadingImage}>
          {submitting ? 'Publicando…' : 'Comentar'}
        </button>
      </div>
    </form>
  );
}

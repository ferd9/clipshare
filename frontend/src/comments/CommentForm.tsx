import { useState, type FormEvent } from 'react';
import { extractErrorMessage, useAuth } from '../auth/AuthContext';
import { createComment } from './commentsApi';
import { TurnstileWidget } from './TurnstileWidget';
import type { CommentSummary } from './types';

interface CommentFormProps {
  clipId: string;
  parentCommentId?: string;
  onCreated: (comment: CommentSummary) => void;
  onCancel?: () => void;
}

export function CommentForm({ clipId, parentCommentId, onCreated, onCancel }: CommentFormProps) {
  const { status } = useAuth();
  const isGuest = status !== 'authenticated';

  const [body, setBody] = useState('');
  const [turnstileToken, setTurnstileToken] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
      });
      setBody('');
      setTurnstileToken(null);
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
        <button type="submit" disabled={submitting}>
          {submitting ? 'Publicando…' : 'Comentar'}
        </button>
      </div>
    </form>
  );
}

import { useCallback, useEffect, useState } from 'react';
import { errorMessage } from './utils';

export function useResource<T>(loader: (signal: AbortSignal) => Promise<T>) {
  const [result, setResult] = useState<{ data?: T; error?: string; loading: boolean }>({ loading: true });
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setResult({ loading: true });
    loader(controller.signal).then((data) => {
      if (!controller.signal.aborted) setResult({ data, loading: false });
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setResult({ error: errorMessage(error), loading: false });
    });
    return () => controller.abort();
  }, [loader, revision]);
  return { ...result, reload: useCallback(() => setRevision((value) => value + 1), []) };
}

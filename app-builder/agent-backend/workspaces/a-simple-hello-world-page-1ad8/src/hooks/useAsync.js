import { useCallback, useState } from 'react';

export function useAsync(initialData = null) {
  const [state, setState] = useState({ data: initialData, loading: false, error: null });
  const run = useCallback(async (operation) => {
    setState(previous => ({ ...previous, loading: true, error: null }));
    try {
      const data = await operation();
      setState({ data, loading: false, error: null });
      return data;
    } catch (error) {
      setState(previous => ({ ...previous, loading: false, error }));
      throw error;
    }
  }, []);
  const reset = useCallback(() => setState({ data: initialData, loading: false, error: null }), [initialData]);
  return { ...state, run, reset, setData: data => setState({ data, loading: false, error: null }) };
}

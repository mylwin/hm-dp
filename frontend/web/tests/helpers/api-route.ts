// Only intercept backend endpoints; Vite source modules may live under /src/api/.
export const isBackendApi = (url: URL) => url.pathname.startsWith('/api/');

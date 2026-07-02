/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_BASE_PATH?: string;
  readonly VITE_PUBLIC_CONTOUR?: 'demo' | 'work';
  readonly VITE_ENABLE_SW?: 'true' | 'false';
}
